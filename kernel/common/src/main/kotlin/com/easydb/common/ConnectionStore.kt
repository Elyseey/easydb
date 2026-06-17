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

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 连接配置持久化存储（v3：凭据分离 —— 元数据与密钥独立存储）
 *
 * ## 存储架构
 * ```
 * ~/.easydb/
 *   connections.json     ← 元数据（host/port/username + passwordRef），无明文密码
 *   credentials.json     ← 加密凭据（由 CredentialVault 管理）
 *   credential.key       ← 随机主密钥（由 CredentialVault 管理）
 * ```
 *
 * ## 安全策略
 * - ConnectionConfig.password / ssh.password 在落盘时清空，改为通过 passwordRef 引用 vault
 * - 内存中 getById() / getAll() 返回的密码为**明文**（自动从 vault 解析）
 * - 旧版 ENCv1: 密码在首次加载时自动迁移到 vault
 * - 旧版明文密码在首次加载时自动迁移到 vault
 * - 迁移前自动创建带时间戳的备份，防止数据丢失
 *
 * ## 调用方透明
 * - getById() / getAll() 返回的 ConnectionConfig 中密码为**明文**（供业务直接使用）
 * - 磁盘文件中密码字段为空，凭据存储在 vault 中
 */
class ConnectionStore(
    private val storageDir: File = File(System.getProperty("user.home"), ".easydb"),
    private val vault: CredentialVault = LocalFileCredentialVault(storageDir)
) {
    private val storageFile = File(storageDir, "connections.json")
    private val cache = ConcurrentHashMap<String, ConnectionConfig>()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        loadFromDisk()
    }

    /** 获取所有连接（内存明文，密码已从 vault 解析） */
    fun getAll(): List<ConnectionConfig> = cache.values.toList()

    /** 根据 ID 获取（内存明文，密码已从 vault 解析） */
    fun getById(id: String): ConnectionConfig? = cache[id]

    /**
     * 保存连接（新建或更新）。
     *
     * 密码处理：
     *   - password 字段非空且非 "***" → 存入 vault，元数据记录 passwordRef
     *   - password 字段为空 → vault 中无记录（密码未设置）
     *   - password 字段为 "***" → 视为未修改，保持原有 passwordRef
     *   - SSH password 同规则
     */
    fun save(config: ConnectionConfig): ConnectionConfig {
        val existing = cache[config.id]

        val finalConfig = normalizePasswordIntent(config, existing)
        cache[finalConfig.id] = finalConfig
        saveToDisk()
        return finalConfig
    }

    /** 删除连接（同时删除关联的凭据） */
    fun delete(id: String): Boolean {
        val config = cache.remove(id)
        if (config != null) {
            // 清理 vault 中的凭据
            config.passwordRef?.let { vault.delete(it) }
            config.ssh?.passwordRef?.let { vault.delete(it) }
            saveToDisk()
        }
        return config != null
    }

    /** 更新连接状态 */
    fun updateStatus(id: String, status: String) {
        cache[id]?.let {
            cache[id] = it.copy(status = status)
            saveToDisk()
        }
    }

    /** 是否包含该连接 */
    fun contains(id: String): Boolean = cache.containsKey(id)

    /** 连接数量 */
    fun count(): Int = cache.size

    // ─── 密码意图规范化 ──────────────────────────────────────

    /**
     * 根据密码意图更新配置并同步 vault：
     *   - 密码为 "***" → 保留已有 passwordRef（未修改）
     *   - 密码非空非 "***" → 存入 vault，更新 passwordRef
     *   - 密码为空且已有 passwordRef → 保留（省略密码 = 保持）
     *   - 密码为空且无 passwordRef → 无密码
     *
     * 调用 saveToDisk() 前必须调用此方法。
     */
    private fun normalizePasswordIntent(
        config: ConnectionConfig,
        existing: ConnectionConfig?
    ): ConnectionConfig {
        // ── 数据库密码 ─────────────────────────────────
        val (resolvedPassword, resolvedPasswordRef) = resolvePassword(
            incomingPassword = config.password,
            existingRef = existing?.passwordRef
        )

        // ── SSH 密码 ───────────────────────────────────
        // 当 config.ssh 显式为 null 时，视为禁用 SSH，不保留旧配置
        val resolvedSsh: SshConfig? = when {
            // 用户禁用了 SSH：清除旧 SSH 配置和凭据
            config.ssh == null && existing?.ssh != null -> {
                existing.ssh.passwordRef?.let { vault.delete(it) }
                null
            }
            // SSH 未变化且之前也没有 → 保持 null
            config.ssh == null -> null
            // SSH 变化或保持启用 → 解析密码意图
            else -> {
                val baseSsh = config.ssh ?: existing?.ssh
                val (sshPassword, sshPasswordRef) = resolvePassword(
                    incomingPassword = config.ssh?.password ?: "",
                    existingRef = existing?.ssh?.passwordRef
                )
                baseSsh?.copy(password = sshPassword, passwordRef = sshPasswordRef)
            }
        }

        return config.copy(
            password = resolvedPassword,
            passwordRef = resolvedPasswordRef,
            ssh = resolvedSsh
        )
    }

    /**
     * 解析密码意图。
     *
     * @param incomingPassword 请求中传入的密码值
     * @param existingRef 已有连接的 vault 引用（新建时为 null）
     * @return Pair(plaintextPassword, passwordRef)
     */
    private fun resolvePassword(
        incomingPassword: String,
        existingRef: String?
    ): Pair<String, String?> {
        return when {
            // "***" → 保持已有凭据不变
            incomingPassword == "***" -> {
                val plaintext = existingRef?.let { resolveVault(it) } ?: ""
                plaintext to existingRef
            }
            // 非空新密码 → 存入 vault（新建或更新）
            incomingPassword.isNotBlank() -> {
                val ref = existingRef ?: "cred_${java.util.UUID.randomUUID().toString().take(8)}"
                vault.put(ref, incomingPassword)
                incomingPassword to ref
            }
            // 空密码 + 有历史 ref → 保持（省略 = 未修改）
            existingRef != null -> {
                val plaintext = resolveVault(existingRef)
                plaintext to existingRef
            }
            // 空密码 + 无历史 ref → 无密码
            else -> "" to null
        }
    }

    /** 从 vault 解析凭据，失败时返回空字符串。 */
    private fun resolveVault(ref: String): String = when (val result = vault.get(ref)) {
        is CredentialResult.Found -> result.value
        is CredentialResult.Missing -> ""
        is CredentialResult.Unavailable -> {
            System.err.println("[ConnectionStore] Credential unavailable: $ref — ${result.reason}")
            ""
        }
    }

    // ─── 磁盘 I/O ────────────────────────────────────────────

    private fun loadFromDisk() {
        if (!storageFile.exists()) return
        try {
            val text = storageFile.readText()
            if (text.isBlank()) return

            val list = json.decodeFromString<List<ConnectionConfig>>(text)
            val needsMigration = list.any { config ->
                // 有明文密码或 ENCv1: 密码但无 passwordRef → 需要迁移
                val hasLegacyPassword = config.password.isNotBlank() && config.passwordRef == null
                val hasLegacySshPassword = config.ssh?.password?.isNotBlank() == true && config.ssh?.passwordRef == null
                hasLegacyPassword || hasLegacySshPassword
            }

            if (needsMigration) {
                migrateToVault(list)
            } else {
                // 正常加载：从 vault 解析密码
                list.forEach { config ->
                    cache[config.id] = resolveFromVault(config).copy(status = "disconnected")
                }
            }
        } catch (e: Exception) {
            System.err.println("[ConnectionStore] Failed to load connections: ${e.message}")
        }
    }

    @Synchronized
    private fun saveToDisk() {
        try {
            storageDir.mkdirs()
            val list = cache.values.toList().map { config ->
                // 清空密码字段，只保留 passwordRef（元数据不含明文/密文）
                config.copy(
                    password = "",
                    ssh = config.ssh?.copy(password = null)
                ).copy(status = "disconnected")
            }
            storageFile.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            System.err.println("[ConnectionStore] Failed to save connections: ${e.message}")
        }
    }

    // ─── 迁移 ────────────────────────────────────────────────

    /**
     * 将旧版 connections.json（含明文或 ENCv1: 密码）迁移到 vault 方案。
     *
     * 迁移步骤：
     *   1. 创建带时间戳的备份
     *   2. 尝试解密 ENCv1: 密码 → 存入 vault
     *   3. 明文密码 → 直接存入 vault
     *   4. 无法解密的 ENCv1: → 记录警告，密码字段留空
     *   5. 写入新的 metadata-only connections.json
     */
    private fun migrateToVault(list: List<ConnectionConfig>) {
        try {
            // 带时间戳的备份（覆盖旧 .bak 文件的问题）
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val backupFile = File(storageDir, "connections.json.${timestamp}.bak")
            if (storageFile.exists()) {
                storageFile.copyTo(backupFile, overwrite = false)
                System.out.println("[ConnectionStore] Backed up config to ${backupFile.absolutePath}")
            }

            var migratedCount = 0
            var failedCount = 0

            list.forEach { config ->
                val migrated = migrateConfig(config)
                cache[config.id] = migrated.copy(status = "disconnected")
                if (migrated.passwordRef != null && migrated.password.isNotBlank()) migratedCount++
                if (migrated.ssh?.passwordRef != null && (migrated.ssh?.password?.isNotBlank() == true)) migratedCount++
                if (config.password.startsWith("ENCv1:") && migrated.password.isBlank()) {
                    failedCount++
                    System.err.println("[ConnectionStore] Migration: cannot decrypt database password for ${config.name} (${config.id}) — password must be re-entered")
                }
                if ((config.ssh?.password?.startsWith("ENCv1:") == true) && (migrated.ssh?.password?.isBlank() == true)) {
                    failedCount++
                    System.err.println("[ConnectionStore] Migration: cannot decrypt SSH password for ${config.name} (${config.id}) — SSH password must be re-entered")
                }
            }

            saveToDisk()
            System.out.println("[ConnectionStore] Migration complete: $migratedCount credentials migrated, $failedCount failed (needs re-entry)")
        } catch (e: Exception) {
            System.err.println("[ConnectionStore] Migration failed: ${e.message}")
        }
    }

    /**
     * 迁移单个配置：
     *   - ENCv1: 密码 → 尝试用 CredentialCipher 解密，成功则存入 vault
     *   - 明文密码 → 直接存入 vault
     *   - SSH 密码同理
     */
    private fun migrateConfig(config: ConnectionConfig): ConnectionConfig {
        // ── 数据库密码迁移 ──────────────────────────────
        val (migratedPassword, migratedPasswordRef) = migratePassword(
            config.password,
            "pwd_${config.id}"
        )

        // ── SSH 密码迁移 ────────────────────────────────
        val migratedSsh = config.ssh?.let { ssh ->
            val (sshPwd, sshRef) = migratePassword(
                ssh.password ?: "",
                "ssh_${config.id}"
            )
            ssh.copy(password = sshPwd, passwordRef = sshRef)
        }

        return config.copy(
            password = migratedPassword,
            passwordRef = migratedPasswordRef,
            ssh = migratedSsh
        )
    }

    /**
     * 迁移单个密码值。
     *
     * @return Pair(plaintextPassword, vaultRef)
     */
    private fun migratePassword(value: String, ref: String): Pair<String, String?> {
        if (value.isBlank()) return "" to null

        return when {
            // ENCv1: 加密密码 → 尝试解密
            value.startsWith("ENCv1:") -> {
                val decrypted = CredentialCipher.decrypt(value)
                if (decrypted == value) {
                    // 解密失败（返回了原密文）→ 无法迁移，保留 ref 以触发 CREDENTIAL_UNAVAILABLE
                    "" to ref
                } else {
                    vault.put(ref, decrypted)
                    decrypted to ref
                }
            }
            // 明文密码 → 直接迁移
            else -> {
                vault.put(ref, value)
                value to ref
            }
        }
    }

    // ─── Vault 解析 ──────────────────────────────────────────

    /** 从 vault 解析凭据填充到 config 的 password 字段（内存明文）。 */
    private fun resolveFromVault(config: ConnectionConfig): ConnectionConfig {
        val plaintextPassword = config.passwordRef?.let { resolveVault(it) } ?: ""
        val resolvedSsh = config.ssh?.let { ssh ->
            val sshPassword = ssh.passwordRef?.let { resolveVault(it) }
            ssh.copy(password = sshPassword, passwordRef = ssh.passwordRef)
        }
        return config.copy(
            password = plaintextPassword,
            passwordRef = config.passwordRef,
            ssh = resolvedSsh
        )
    }
}
