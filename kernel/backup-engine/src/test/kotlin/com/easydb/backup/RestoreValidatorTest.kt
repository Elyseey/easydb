package com.easydb.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RestoreValidatorTest {

    @TempDir
    lateinit var tempDir: File

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `inspect returns invalid for non-existent file`() {
        val nonExistent = File(tempDir, "nonexistent.edbkp")
        val validator = RestoreValidator(nonExistent)
        val result = validator.inspect()

        assertFalse(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.warnings.any { it.contains("does not exist") })
    }

    @Test
    fun `inspect returns invalid for zip without manifest`() {
        val backupFile = createZipWithoutManifest()
        val validator = RestoreValidator(backupFile)
        val result = validator.inspect()

        assertFalse(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.any { it.contains("manifest.json not found") })
    }

    @Test
    fun `inspect returns valid for proper backup package`() {
        val backupFile = createValidBackupPackage()
        val validator = RestoreValidator(backupFile)
        val result = validator.inspect()

        assertTrue(result.fileValid)
        assertEquals(1, result.manifest.formatVersion)
        assertEquals("mysql", result.manifest.dbType)
        assertEquals("snapshot", result.manifest.consistency)
    }

    @Test
    fun `inspect validates checksum when present`() {
        val backupFile = createBackupPackageWithChecksum()
        val validator = RestoreValidator(backupFile)
        val result = validator.inspect()

        assertTrue(result.fileValid)
        assertTrue(result.checksumValid)
        assertTrue(result.warnings.isEmpty() || !result.warnings.any { it.contains("checksum mismatch") })
    }

    @Test
    fun `inspect detects checksum mismatch`() {
        val backupFile = createBackupPackageWithInvalidChecksum()
        val validator = RestoreValidator(backupFile)
        val result = validator.inspect()

        assertTrue(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.any { it.contains("checksum mismatch") })
    }

    private fun createZipWithoutManifest(): File {
        val file = File(tempDir, "no_manifest.edbkp")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("data/users.sql"))
            zip.write("INSERT INTO users VALUES (1, 'test');".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun createValidBackupPackage(): File {
        val manifest = BackupManifest(
            formatVersion = 1,
            appVersion = "1.3.1",
            dbType = "mysql",
            serverVersion = "8.0.32",
            database = "testdb",
            mode = "full",
            startedAt = "2026-04-18T15:00:00",
            consistency = "snapshot",
            tables = emptyList(),
            objects = emptyList()
        )

        val file = File(tempDir, "valid_backup.edbkp")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun createBackupPackageWithChecksum(): File {
        val manifest = BackupManifest(
            formatVersion = 1,
            appVersion = "1.3.1",
            dbType = "mysql",
            serverVersion = "8.0.32",
            database = "testdb",
            mode = "full",
            startedAt = "2026-04-18T15:00:00",
            consistency = "snapshot",
            tables = emptyList(),
            objects = emptyList()
        )

        val manifestContent = json.encodeToString(manifest)
        val manifestSha256 = computeSha256(manifestContent.toByteArray())
        val checksums = mapOf("manifest.json" to manifestSha256)

        val file = File(tempDir, "checksum_backup.edbkp")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestContent.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("checksums.json"))
            zip.write(json.encodeToString(checksums).toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun createBackupPackageWithInvalidChecksum(): File {
        val manifest = BackupManifest(
            formatVersion = 1,
            appVersion = "1.3.1",
            dbType = "mysql",
            serverVersion = "8.0.32",
            database = "testdb",
            mode = "full",
            startedAt = "2026-04-18T15:00:00",
            consistency = "snapshot",
            tables = emptyList(),
            objects = emptyList()
        )

        val manifestContent = json.encodeToString(manifest)
        val invalidChecksums = mapOf("manifest.json" to "invalid_checksum_value")

        val file = File(tempDir, "invalid_checksum.edbkp")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestContent.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("checksums.json"))
            zip.write(json.encodeToString(invalidChecksums).toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}