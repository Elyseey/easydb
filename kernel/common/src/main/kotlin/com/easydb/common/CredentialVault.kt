/*
 * Copyright (c) 2024-2026 EasyDB Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.easydb.common

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── Vault Interface ────────────────────────────────────────

/**
 * Credential vault — separates secret storage from connection metadata.
 *
 * Connection metadata lives in `connections.json` with credential references
 * (e.g. `passwordRef`). Actual secrets are stored and retrieved through this
 * interface, keeping the two concerns independent.
 *
 * Implementations:
 *   - [LocalFileCredentialVault]: file-backed AES-GCM vault (MVP)
 *   - Future: macOS Keychain, Windows Credential Manager, Linux Secret Service
 */
interface CredentialVault {
    /** Store a secret by reference id. Empty/blank values remove the entry. */
    fun put(ref: String, plaintext: String)

    /** Retrieve a secret by reference id. */
    fun get(ref: String): CredentialResult

    /** Remove a secret. Idempotent — no error if the ref doesn't exist. */
    fun delete(ref: String)

    /** Check whether a secret exists for the given ref. */
    fun contains(ref: String): Boolean
}

/** Result of a credential lookup. */
sealed class CredentialResult {
    /** Credential found and decrypted. */
    data class Found(val value: String) : CredentialResult()

    /** No credential stored for this reference. */
    data object Missing : CredentialResult()

    /** Credential exists but cannot be read (e.g. key missing, corruption). */
    data class Unavailable(val reason: String) : CredentialResult()
}

// ─── Local File Vault ───────────────────────────────────────

/**
 * File-backed credential vault using AES-256-GCM.
 *
 * ## Storage layout
 * ```
 * ~/.easydb/
 *   credential.key      ← random 32-byte master key (Base64), stable across launches
 *   credentials.json    ← { ref: encrypted-blob } for each secret
 *   connections.json    ← connection metadata with passwordRef / ssh.passwordRef
 * ```
 *
 * ## Key properties
 * - The master key is randomly generated once, NOT derived from machine-id.
 * - Copying `~/.easydb/` to another machine restores all credentials.
 * - Encryption: AES-256-GCM, random 12-byte IV per entry, 128-bit auth tag.
 * - On-disk format per entry: `Base64( IV[12] | ciphertext | tag[16] )`.
 */
class LocalFileCredentialVault(
    private val storageDir: File = File(System.getProperty("user.home"), ".easydb")
) : CredentialVault {

    private val credentialsFile = File(storageDir, "credentials.json")
    private val keyFile = File(storageDir, "credential.key")
    private val cache = ConcurrentHashMap<String, String>()

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** AES-256 key (32 bytes), lazy-loaded or generated on first access. */
    private val masterKey: ByteArray by lazy { loadOrGenerateKey() }

    init {
        loadFromDisk()
    }

    // ─── Public API ─────────────────────────────────────

    override fun put(ref: String, plaintext: String) {
        if (plaintext.isBlank()) {
            delete(ref)
            return
        }
        cache[ref] = plaintext
        saveToDisk()
    }

    override fun get(ref: String): CredentialResult {
        cache[ref]?.let { return CredentialResult.Found(it) }
        return CredentialResult.Missing
    }

    override fun delete(ref: String) {
        cache.remove(ref)
        saveToDisk()
    }

    override fun contains(ref: String): Boolean = cache.containsKey(ref)

    // ─── Disk I/O ───────────────────────────────────────

    private fun loadFromDisk() {
        if (!credentialsFile.exists()) return
        try {
            val text = credentialsFile.readText()
            if (text.isBlank()) return
            val entries: CredentialEntries = json.decodeFromString(text)
            entries.entries.forEach { (ref, encrypted) ->
                try {
                    cache[ref] = decrypt(encrypted)
                } catch (e: Exception) {
                    System.err.println("[CredentialVault] Failed to decrypt credential $ref: ${e.message}")
                }
            }
        } catch (e: Exception) {
            System.err.println("[CredentialVault] Failed to load credentials: ${e.message}")
        }
    }

    @Synchronized
    private fun saveToDisk() {
        try {
            storageDir.mkdirs()
            val entries = CredentialEntries(
                entries = cache.mapValues { (_, plaintext) -> encrypt(plaintext) }
            )
            credentialsFile.writeText(json.encodeToString(entries))
        } catch (e: Exception) {
            System.err.println("[CredentialVault] Failed to save credentials: ${e.message}")
        }
    }

    // ─── Key Management ────────────────────────────────

    private fun loadOrGenerateKey(): ByteArray {
        if (keyFile.exists()) {
            try {
                val encoded = keyFile.readText().trim()
                val key = Base64.getDecoder().decode(encoded)
                if (key.size == 32) return key
                System.err.println("[CredentialVault] Master key has wrong length (${key.size}), regenerating")
            } catch (e: Exception) {
                System.err.println("[CredentialVault] Failed to load master key, regenerating: ${e.message}")
            }
        }
        // Generate new random 256-bit key
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        try {
            storageDir.mkdirs()
            keyFile.writeText(Base64.getEncoder().encodeToString(key))
            // Restrictive permissions: owner-only read
            runCatching { keyFile.setReadable(false, false) }
            runCatching { keyFile.setReadable(true, true) }
        } catch (e: Exception) {
            System.err.println("[CredentialVault] Failed to persist master key: ${e.message}")
        }
        return key
    }

    // ─── Encryption ────────────────────────────────────

    private fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val keySpec = SecretKeySpec(masterKey, "AES")
        val paramSpec = GCMParameterSpec(128, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec)
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherBytes)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        val iv = combined.copyOfRange(0, 12)
        val cipherBytes = combined.copyOfRange(12, combined.size)
        val keySpec = SecretKeySpec(masterKey, "AES")
        val paramSpec = GCMParameterSpec(128, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }
}

@Serializable
private data class CredentialEntries(
    val entries: Map<String, String> = emptyMap()
)
