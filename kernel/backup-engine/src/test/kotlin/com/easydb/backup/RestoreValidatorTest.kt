package com.easydb.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RestoreValidatorTest {

    @TempDir
    lateinit var tempDir: File

    private val json = Json { encodeDefaults = true }

    @Test
    fun `non-existent file is invalid`() {
        val result = RestoreValidator(File(tempDir, "missing.edbkp")).inspect()

        assertFalse(result.fileValid)
        assertFalse(result.checksumValid)
    }

    @Test
    fun `zip without manifest is invalid`() {
        val result = RestoreValidator(writeZip("no-manifest.edbkp", mapOf("data/a.sql" to "SELECT 1"))).inspect()

        assertFalse(result.fileValid)
        assertTrue(result.warnings.any { it.contains("manifest.json") })
    }

    @Test
    fun `missing checksums blocks restore`() {
        val manifest = manifest()
        val files = packagePayload(manifest)
        val result = RestoreValidator(writeZip("no-checksum.edbkp", files)).inspect()

        assertTrue(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.any { it.contains("checksums.json") })
    }

    @Test
    fun `validates every file in package`() {
        val manifest = manifest(
            tables = listOf(
                BackupTableEntry("users", "schema/010_tables/table_00000.sql", 1, listOf("data/table_00000.part001.sql.gz"))
            ),
            objects = listOf(
                BackupObjectEntry("active_users", "view", "schema/030_views/view_00000.sql")
            )
        )
        val payload = packagePayload(manifest).toMutableMap().apply {
            put("schema/010_tables/table_00000.sql", "CREATE TABLE users(id INT)")
            put("data/table_00000.part001.sql.gz", "compressed-placeholder")
            put("schema/030_views/view_00000.sql", "CREATE VIEW active_users AS SELECT 1")
        }
        val result = RestoreValidator(writeSignedPackage("valid.edbkp", payload)).inspect()

        assertTrue(result.fileValid)
        assertTrue(result.checksumValid)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `detects checksum mismatch in non-manifest file`() {
        val manifest = manifest()
        val payload = packagePayload(manifest)
        val checksums = payload.mapValues { sha256(it.value.toByteArray()) }.toMutableMap()
        checksums["schema/000_database.sql"] = "0".repeat(64)
        val files = payload + ("checksums.json" to json.encodeToString(checksums))
        val result = RestoreValidator(writeZip("mismatch.edbkp", files)).inspect()

        assertTrue(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.any { it.contains("校验和不匹配") })
    }

    @Test
    fun `missing manifest reference is structurally invalid`() {
        val manifest = manifest(
            tables = listOf(BackupTableEntry("users", "schema/010_tables/missing.sql", 0, emptyList()))
        )
        val result = RestoreValidator(writeSignedPackage("missing-ref.edbkp", packagePayload(manifest))).inspect()

        assertFalse(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.any { it.contains("缺少 manifest 引用文件") })
    }

    @Test
    fun `unchecksummed extra payload file blocks restore`() {
        val manifest = manifest()
        val payload = packagePayload(manifest).toMutableMap().apply { put("extra.sql", "SELECT 1") }
        val checksums = packagePayload(manifest).mapValues { sha256(it.value.toByteArray()) }
        val result = RestoreValidator(
            writeZip("unsigned-extra.edbkp", payload + ("checksums.json" to json.encodeToString(checksums)))
        ).inspect()

        assertTrue(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.any { it.contains("缺少校验和") })
    }

    @Test
    fun `unsafe referenced path is invalid`() {
        val manifest = manifest(
            objects = listOf(BackupObjectEntry("danger", "view", "../escape.sql"))
        )
        val result = RestoreValidator(writeSignedPackage("unsafe.edbkp", packagePayload(manifest))).inspect()

        assertFalse(result.fileValid)
        assertTrue(result.warnings.any { it.contains("不安全路径") })
    }

    @Test
    fun `unsupported format version is invalid`() {
        val manifest = manifest(formatVersion = 99)
        val result = RestoreValidator(writeSignedPackage("future.edbkp", packagePayload(manifest))).inspect()

        assertFalse(result.fileValid)
        assertFalse(result.checksumValid)
        assertTrue(result.warnings.any { it.contains("不支持的备份格式版本") })
    }

    private fun manifest(
        formatVersion: Int = 1,
        tables: List<BackupTableEntry> = emptyList(),
        objects: List<BackupObjectEntry> = emptyList()
    ) = BackupManifest(
        formatVersion = formatVersion,
        appVersion = "1.3.2",
        dbType = "dameng",
        serverVersion = "8",
        database = "SOURCE_SCHEMA",
        mode = "full",
        startedAt = "2026-07-14T10:00:00",
        consistency = "snapshot",
        tables = tables,
        objects = objects
    )

    private fun packagePayload(manifest: BackupManifest): Map<String, String> = mapOf(
        "manifest.json" to json.encodeToString(manifest),
        "schema/000_database.sql" to "CREATE SCHEMA \"SOURCE_SCHEMA\";"
    )

    private fun writeSignedPackage(name: String, payload: Map<String, String>): File {
        val checksums = payload.mapValues { sha256(it.value.toByteArray()) }
        return writeZip(name, payload + ("checksums.json" to json.encodeToString(checksums)))
    }

    private fun writeZip(name: String, files: Map<String, String>): File {
        val file = File(tempDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
