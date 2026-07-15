package com.easydb.backup

import com.easydb.common.ConnectionConfig
import com.easydb.common.DatabaseAdapter
import com.easydb.common.TaskReporter
import com.easydb.common.TaskResult
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

class RestoreService(
    @Suppress("unused") private val storageDir: File = File(System.getProperty("user.home"), ".easydb")
) {

    fun execute(
        config: RestoreConfig,
        connectionConfig: ConnectionConfig,
        reporter: TaskReporter,
        adapter: DatabaseAdapter
    ): TaskResult {
        val backupFile = File(config.backupFilePath)
        val inspectResult = RestoreValidator(backupFile).inspect()
        require(inspectResult.fileValid) {
            "备份文件无效：${inspectResult.warnings.joinToString()}"
        }
        require(inspectResult.checksumValid) {
            "备份包完整性校验失败：${inspectResult.warnings.joinToString()}"
        }

        val manifest = inspectResult.manifest
        val dialect = adapter.dialectAdapter()
        val metadata = adapter.metadataAdapter()
        val connectionAdapter = adapter.connectionAdapter()
        val targetNamespace = dialect.normalizeNewNamespaceName(config.targetDatabase)
        val adminConfig = connectionConfig.copy(database = null)
        val restoreSession = connectionAdapter.open(adminConfig)
        val restoreConnection = restoreSession.getJdbcConnection()
        restoreConnection.autoCommit = true

        var createdTarget = false
        var restoreHooksStarted = false
        var restoredObjectCount = 0
        try {
            val targetExists = metadata.listDatabases(restoreSession).any { it.name == targetNamespace }
            RestorePolicy.validateOrThrow(config, manifest, adapter, targetExists)

            if (config.strategy == "overwrite_existing" && targetExists) {
                metadata.dropDatabase(restoreSession, targetNamespace)
            }

            reporter.onProgress(5, "Preparing target namespace")
            metadata.createDatabase(
                restoreSession,
                targetNamespace,
                safeCharset(manifest.charset),
                safeCollation(manifest.collation)
            )
            createdTarget = true

            dialect.buildSwitchDatabaseSql(targetNamespace)?.let { sql ->
                restoreConnection.createStatement().use { it.execute(sql) }
            }
            restoreHooksStarted = true
            dialect.beforeLogicalRestore(restoreConnection)

            ZipFile(backupFile).use { zip ->
                val tablesToRestore = if (config.selectedTables.isEmpty()) {
                    manifest.tables
                } else {
                    manifest.tables.filter { it.tableName in config.selectedTables }
                }
                val restoreStructure = config.mode == "restore_all" || config.mode == "structure_only"
                val restoreData = config.mode == "restore_all" || config.mode == "data_only"

                if (restoreStructure) {
                    restoreTableStructures(
                        zip,
                        tablesToRestore,
                        manifest.database,
                        targetNamespace,
                        dialect,
                        restoreConnection,
                        reporter,
                        config.mode
                    )
                    restoredObjectCount += tablesToRestore.size
                } else {
                    reporter.onLog("INFO", "Skipping structure restore (data_only mode)")
                }

                if (restoreData) {
                    restoreTableData(zip, tablesToRestore, restoreConnection, reporter, config.mode)
                    if (!restoreStructure) restoredObjectCount += tablesToRestore.size
                } else {
                    reporter.onLog("INFO", "Skipping data restore (structure_only mode)")
                }

                if (restoreStructure) {
                    restoreObjects(
                        zip,
                        manifest,
                        targetNamespace,
                        dialect,
                        restoreConnection,
                        reporter,
                        config.mode
                    )
                    restoredObjectCount += manifest.objects.size
                }
            }

            reporter.onProgress(100, "Restore completed")
            return TaskResult(
                success = true,
                successCount = restoredObjectCount,
                payload = mapOf("database" to targetNamespace)
            )
        } catch (error: Exception) {
            if (createdTarget && config.strategy == "restore_to_new") {
                cleanupCreatedNamespace(adminConfig, targetNamespace, adapter, reporter, error)
            }
            throw error
        } finally {
            if (restoreHooksStarted) {
                try {
                    dialect.afterLogicalRestore(restoreConnection)
                } catch (hookError: Exception) {
                    reporter.onLog("WARN", "恢复数据库约束状态失败：${safeMessage(hookError)}")
                }
            }
            try {
                restoreSession.close()
            } catch (_: Exception) {
                // 任务结果不应被关闭连接失败覆盖。
            }
        }
    }

    private fun restoreTableStructures(
        zip: ZipFile,
        tables: List<BackupTableEntry>,
        sourceNamespace: String,
        targetNamespace: String,
        dialect: com.easydb.common.DialectAdapter,
        connection: java.sql.Connection,
        reporter: TaskReporter,
        mode: String
    ) {
        val progressEnd = if (mode == "structure_only") 40 else 15
        val progressRange = progressEnd - 5
        tables.forEachIndexed { index, table ->
            checkNotCancelled(reporter)
            val progress = 5 + (progressRange * (index + 1)) / tables.size.coerceAtLeast(1)
            reporter.onProgress(progress, "Restoring table structure: ${table.tableName}")
            reporter.onLog("INFO", "Creating table ${table.tableName}...")

            val ddl = readEntryText(zip, table.ddlFile)
            val remapped = dialect.remapNamespaceInDdl(ddl, sourceNamespace, targetNamespace)
            dialect.executeLogicalRestoreDdl(connection, remapped, "table")
        }
    }

    private fun restoreTableData(
        zip: ZipFile,
        tables: List<BackupTableEntry>,
        connection: java.sql.Connection,
        reporter: TaskReporter,
        mode: String
    ) {
        val progressStart = if (mode == "data_only") 5 else 15
        val progressEnd = if (mode == "data_only") 95 else 85
        val progressRange = progressEnd - progressStart

        tables.forEachIndexed { tableIndex, table ->
            checkNotCancelled(reporter)
            val tableBase = progressStart + (progressRange * tableIndex) / tables.size.coerceAtLeast(1)
            val tableRange = progressRange / tables.size.coerceAtLeast(1)
            reporter.onProgress(tableBase, "Restoring table data: ${table.tableName}")
            reporter.onLog("INFO", "Restoring table ${table.tableName}...")

            var batchCount = 0L
            table.dataFiles.forEachIndexed { fileIndex, dataFile ->
                checkNotCancelled(reporter)
                val entry = zip.getEntry(dataFile)
                    ?: throw IllegalArgumentException("备份包缺少数据文件：$dataFile")
                val zipInput = zip.getInputStream(entry)
                val dataInput = if (dataFile.endsWith(".gz", ignoreCase = true)) {
                    GZIPInputStream(zipInput)
                } else {
                    zipInput
                }
                LimitedInputStream(dataInput, MAX_EXPANDED_SQL_FILE_BYTES).bufferedReader(Charsets.UTF_8).use { reader ->
                    val statement = StringBuilder()
                    while (true) {
                        checkNotCancelled(reporter)
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.endsWith(';')) {
                            statement.append(trimmed.dropLast(1)).append('\n')
                            executeStatement(connection, statement, dataFile)
                            batchCount++
                            if (batchCount % 100L == 0L) {
                                val fileProgress = ((fileIndex + 1) * 100) / table.dataFiles.size.coerceAtLeast(1)
                                val progress = tableBase + (tableRange * fileProgress) / 100
                                reporter.onProgress(progress.coerceAtMost(progressEnd - 1), "Restoring ${table.tableName}: $batchCount batches...")
                            }
                        } else {
                            statement.append(line).append('\n')
                            require(statement.length <= MAX_SQL_STATEMENT_CHARS) {
                                "SQL statement in $dataFile exceeds safety limit"
                            }
                        }
                    }
                    if (statement.isNotBlank()) executeStatement(connection, statement, dataFile)
                }
            }

            val complete = progressStart + (progressRange * (tableIndex + 1)) / tables.size.coerceAtLeast(1)
            reporter.onProgress(complete.coerceAtMost(progressEnd - 1), "Table ${table.tableName} restored")
            reporter.onLog("INFO", "Table ${table.tableName} restored ($batchCount batches)")
        }
    }

    private fun restoreObjects(
        zip: ZipFile,
        manifest: BackupManifest,
        targetNamespace: String,
        dialect: com.easydb.common.DialectAdapter,
        connection: java.sql.Connection,
        reporter: TaskReporter,
        mode: String
    ) {
        val groups = listOf(
            setOf("procedure", "function") to (if (mode == "structure_only") 40 else 85),
            setOf("view") to (if (mode == "structure_only") 50 else 90),
            setOf("trigger") to (if (mode == "structure_only") 60 else 95)
        )

        groups.forEach { (types, startProgress) ->
            val objects = manifest.objects.filter { it.type in types }
            if (objects.isEmpty()) return@forEach
            reporter.onProgress(startProgress, "Restoring ${types.joinToString("/")}")
            objects.forEachIndexed { index, obj ->
                checkNotCancelled(reporter)
                val progress = startProgress + ((100 - startProgress) * (index + 1)) / objects.size.coerceAtLeast(1)
                reporter.onProgress(progress.coerceAtMost(99), "Restoring ${obj.type}: ${obj.name}")
                val ddl = readEntryText(zip, obj.ddlFile)
                val remapped = dialect.remapNamespaceInDdl(ddl, manifest.database, targetNamespace)
                dialect.executeLogicalRestoreDdl(connection, remapped, obj.type)
            }
        }
    }

    private fun executeStatement(connection: java.sql.Connection, sql: StringBuilder, source: String) {
        if (sql.isBlank()) return
        require(sql.length <= MAX_SQL_STATEMENT_CHARS) { "SQL statement in $source exceeds safety limit" }
        connection.createStatement().use { it.execute(sql.toString()) }
        sql.setLength(0)
    }

    private fun readEntryText(zip: ZipFile, path: String): String {
        val entry = zip.getEntry(path) ?: throw IllegalArgumentException("备份包缺少 DDL 文件：$path")
        require(entry.size < 0 || entry.size <= MAX_DDL_BYTES) { "DDL file exceeds safety limit: $path" }
        return LimitedInputStream(zip.getInputStream(entry), MAX_DDL_BYTES)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    private fun cleanupCreatedNamespace(
        adminConfig: ConnectionConfig,
        targetNamespace: String,
        adapter: DatabaseAdapter,
        reporter: TaskReporter,
        originalError: Exception
    ) {
        val cleanupSession = try {
            adapter.connectionAdapter().open(adminConfig)
        } catch (cleanupError: Exception) {
            originalError.addSuppressed(cleanupError)
            reporter.onLog("ERROR", "无法打开清理连接，部分 Schema 可能保留：${safeMessage(cleanupError)}")
            return
        }
        try {
            adapter.metadataAdapter().dropDatabase(cleanupSession, targetNamespace)
            reporter.onLog("WARN", "恢复失败，已清理由本任务创建的 namespace：$targetNamespace")
        } catch (cleanupError: Exception) {
            originalError.addSuppressed(cleanupError)
            reporter.onLog("ERROR", "清理新建 namespace 失败：${safeMessage(cleanupError)}")
        } finally {
            try {
                cleanupSession.close()
            } catch (_: Exception) {
                // 保留原始恢复异常。
            }
        }
    }

    private fun checkNotCancelled(reporter: TaskReporter) {
        if (reporter.isCancelled()) throw IllegalStateException("Task cancelled")
    }

    private fun safeCharset(value: String?): String =
        value?.takeIf { it.matches(SAFE_OPTION_PATTERN) } ?: "utf8mb4"

    private fun safeCollation(value: String?): String =
        value?.takeIf { it.matches(SAFE_OPTION_PATTERN) } ?: "utf8mb4_general_ci"

    private fun safeMessage(error: Exception): String =
        error.message?.lineSequence()?.firstOrNull()?.take(200) ?: error.javaClass.simpleName

    private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) record(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) record(read.toLong())
            return read
        }

        private fun record(bytes: Long) {
            consumed += bytes
            require(consumed <= maxBytes) { "Expanded SQL file exceeds safety limit" }
        }
    }

    companion object {
        private const val MAX_DDL_BYTES = 64L * 1024 * 1024
        private const val MAX_EXPANDED_SQL_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_SQL_STATEMENT_CHARS = 64 * 1024 * 1024
        private val SAFE_OPTION_PATTERN = Regex("[A-Za-z0-9_]+")
    }
}
