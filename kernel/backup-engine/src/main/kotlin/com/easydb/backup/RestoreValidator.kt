package com.easydb.backup

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class RestoreValidator(private val backupFile: File) {

    private val json = Json { ignoreUnknownKeys = true }

    fun inspect(): RestoreInspectResult {
        if (!backupFile.exists() || !backupFile.isFile) {
            return invalid("备份文件不存在或不是有效文件")
        }
        if (!backupFile.name.endsWith(".edbkp", ignoreCase = true)) {
            return invalid("备份文件扩展名必须为 .edbkp")
        }

        return try {
            ZipFile(backupFile).use(::inspectZip)
        } catch (error: Exception) {
            invalid("读取备份包失败：${safeMessage(error)}")
        }
    }

    private fun inspectZip(zip: ZipFile): RestoreInspectResult {
        val entries = zip.entries().asSequence().toList()
        val warnings = mutableListOf<String>()

        if (entries.size > MAX_ENTRY_COUNT) {
            return invalid("备份包文件数量超过安全上限 $MAX_ENTRY_COUNT")
        }

        val oversizedEntry = entries.firstOrNull { !it.isDirectory && it.size > MAX_ARCHIVE_ENTRY_BYTES }
        if (oversizedEntry != null) {
            return invalid("备份包文件超过安全大小限制：${oversizedEntry.name}")
        }
        val declaredTotalSize = entries.asSequence()
            .filter { !it.isDirectory && it.size >= 0 }
            .sumOf { it.size }
        if (declaredTotalSize > MAX_TOTAL_UNCOMPRESSED_BYTES) {
            return invalid("备份包解压后总大小超过安全限制")
        }

        val duplicateNames = entries.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            return invalid("备份包包含重复文件：${duplicateNames.first()}")
        }

        val unsafeEntry = entries.firstOrNull { !isSafeEntryName(it.name) }
        if (unsafeEntry != null) {
            return invalid("备份包包含不安全路径：${unsafeEntry.name}")
        }

        val files = entries.filterNot(ZipEntry::isDirectory).associateBy { it.name }
        val manifestEntry = files[MANIFEST_FILE]
            ?: return invalid("备份包缺少 manifest.json")
        if (manifestEntry.size > MAX_METADATA_BYTES) {
            return invalid("manifest.json 超过安全大小限制")
        }

        val manifestContent = readLimitedText(zip, manifestEntry, MAX_METADATA_BYTES)
        val manifest = try {
            json.decodeFromString<BackupManifest>(manifestContent)
        } catch (error: Exception) {
            return invalid("manifest.json 格式无效：${safeMessage(error)}")
        }

        if (manifest.formatVersion != SUPPORTED_FORMAT_VERSION) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("不支持的备份格式版本：${manifest.formatVersion}")
            )
        }
        if (manifest.dbType.isBlank() || manifest.database.isBlank()) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("manifest 缺少数据库类型或 namespace")
            )
        }
        if (manifest.mode !in VALID_BACKUP_MODES) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("manifest 包含无效备份模式：${manifest.mode}")
            )
        }

        val duplicateTable = manifest.tables.groupingBy { it.tableName }.eachCount()
            .entries.firstOrNull { it.value > 1 }?.key
        if (duplicateTable != null) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("manifest 包含重复表：$duplicateTable")
            )
        }

        val referencedFileList = buildList {
            add(MANIFEST_FILE)
            add(NAMESPACE_DDL_FILE)
            manifest.tables.forEach { table ->
                add(table.ddlFile)
                addAll(table.dataFiles)
            }
            manifest.objects.forEach { add(it.ddlFile) }
        }
        val duplicateReference = referencedFileList.groupingBy { it }.eachCount()
            .entries.firstOrNull { it.value > 1 }?.key
        if (duplicateReference != null) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("manifest 重复引用文件：$duplicateReference")
            )
        }
        val referencedFiles = referencedFileList.toSet()
        val unsafeReference = referencedFiles.firstOrNull { !isSafeEntryName(it) }
        if (unsafeReference != null) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("manifest 引用了不安全路径：$unsafeReference")
            )
        }
        val missingReference = referencedFiles.firstOrNull { files[it] == null }
        if (missingReference != null) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("备份包缺少 manifest 引用文件：$missingReference")
            )
        }

        val checksumsEntry = files[CHECKSUMS_FILE]
        if (checksumsEntry == null) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = true,
                checksumValid = false,
                warnings = listOf("备份包缺少 checksums.json，禁止恢复")
            )
        }
        if (checksumsEntry.size > MAX_METADATA_BYTES) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("checksums.json 超过安全大小限制")
            )
        }

        val expectedChecksums = try {
            json.decodeFromString<Map<String, String>>(
                readLimitedText(zip, checksumsEntry, MAX_METADATA_BYTES)
            )
        } catch (error: Exception) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("checksums.json 格式无效：${safeMessage(error)}")
            )
        }

        val unsafeChecksumPath = expectedChecksums.keys.firstOrNull { !isSafeEntryName(it) }
        if (unsafeChecksumPath != null) {
            return RestoreInspectResult(
                manifest = manifest,
                fileValid = false,
                checksumValid = false,
                warnings = listOf("checksums.json 包含不安全路径：$unsafeChecksumPath")
            )
        }

        val payloadFiles = files.keys - CHECKSUMS_FILE
        val missingChecksum = payloadFiles.firstOrNull { it !in expectedChecksums }
        if (missingChecksum != null) {
            warnings.add("文件缺少校验和：$missingChecksum")
        }
        val unknownChecksum = expectedChecksums.keys.firstOrNull { it !in payloadFiles }
        if (unknownChecksum != null) {
            warnings.add("校验和引用了不存在的文件：$unknownChecksum")
        }

        var actualTotalSize = 0L
        if (warnings.isEmpty()) {
            for ((path, expected) in expectedChecksums) {
                if (!expected.matches(SHA256_PATTERN)) {
                    warnings.add("文件校验和格式无效：$path")
                    break
                }
                val entry = files[path] ?: continue
                val (actual, actualSize) = zip.getInputStream(entry).use { computeSha256(it) }
                actualTotalSize += actualSize
                if (actualSize > MAX_ARCHIVE_ENTRY_BYTES || actualTotalSize > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    warnings.add("备份包解压大小超过安全限制：$path")
                    break
                }
                if (!expected.equals(actual, ignoreCase = true)) {
                    warnings.add("文件校验和不匹配：$path")
                    break
                }
            }
        }

        return RestoreInspectResult(
            manifest = manifest,
            fileValid = true,
            checksumValid = warnings.isEmpty(),
            warnings = warnings
        )
    }

    private fun readLimitedText(zip: ZipFile, entry: ZipEntry, limit: Long): String =
        zip.getInputStream(entry).use { input ->
            val bytes = input.readNBytes((limit + 1).toInt())
            require(bytes.size.toLong() <= limit) { "${entry.name} 超过安全大小限制" }
            bytes.toString(Charsets.UTF_8)
        }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.startsWith('\\') || '\u0000' in name) return false
        if ('\\' in name) return false
        return name.split('/').none { it == ".." || it.isBlank() }
    }

    private fun invalid(message: String) = RestoreInspectResult(
        manifest = emptyManifest(),
        fileValid = false,
        checksumValid = false,
        warnings = listOf(message)
    )

    private fun emptyManifest() = BackupManifest(
        formatVersion = 0,
        appVersion = "",
        dbType = "",
        serverVersion = "",
        database = "",
        mode = "",
        startedAt = "",
        consistency = "",
        tables = emptyList(),
        objects = emptyList()
    )

    private fun computeSha256(input: InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_ARCHIVE_ENTRY_BYTES) { "备份包文件解压大小超过安全限制" }
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to total
    }

    private fun safeMessage(error: Exception): String =
        error.message?.lineSequence()?.firstOrNull()?.take(200) ?: error.javaClass.simpleName

    companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
        private const val MANIFEST_FILE = "manifest.json"
        private const val CHECKSUMS_FILE = "checksums.json"
        private const val NAMESPACE_DDL_FILE = "schema/000_database.sql"
        private const val MAX_ENTRY_COUNT = 100_000
        private const val MAX_METADATA_BYTES = 16L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024 * 1024
        private val VALID_BACKUP_MODES = setOf("full", "structure_only", "data_only")
        private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
    }
}
