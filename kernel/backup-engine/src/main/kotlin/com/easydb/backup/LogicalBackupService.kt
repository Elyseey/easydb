package com.easydb.backup

import com.easydb.common.*
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

class LogicalBackupService(
    private val storageDir: File = File(System.getProperty("user.home"), ".easydb")
) {
    fun execute(config: BackupConfig, connectionConfig: ConnectionConfig, reporter: TaskReporter, adapter: DatabaseAdapter): TaskResult {
        BackupPolicy.validateOrThrow(config, adapter)
        val dbType = adapter.dbType()
        val metadataAdapter = adapter.metadataAdapter()
        val dialectAdapter = adapter.dialectAdapter()
        val connectionAdapter = adapter.connectionAdapter()
        val logicalBackupAdapter = adapter.logicalBackupAdapter()
            ?: throw UnsupportedOperationException("${dbType.displayName} 暂不支持逻辑备份")

        // Create dedicated connection for backup
        val backupSession = connectionAdapter.open(connectionConfig.copy(database = config.database))
        val backupConn = backupSession.getJdbcConnection()
        var backupContextStarted = false
        var cleanupWriter: BackupPackageWriter? = null
        try {
            dialectAdapter.buildSwitchDatabaseSql(config.database)?.let { sql ->
                backupConn.createStatement().use { it.execute(sql) }
            }
            val backupContext = logicalBackupAdapter.begin(backupConn)
            backupContextStarted = true
            backupContext.warnings.forEach { reporter.onLog("WARN", it) }

            val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS")
            val backupsDir = if (config.outputPath?.isNotBlank() == true) {
                File(config.outputPath).apply { mkdirs() }
            } else {
                File(storageDir, "backups").apply { mkdirs() }
            }
            val timestamp = timestampFormat.format(Date())
            val workDir = File(backupsDir, "tmp_${UUID.randomUUID()}")
            val writer = BackupPackageWriter(workDir)
            cleanupWriter = writer

            val dbCharset = backupContext.charset
            val dbCollation = backupContext.collation
            val serverVersion = try {
                backupConn.metaData.databaseProductVersion?.takeIf { it.isNotBlank() } ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }

            val dbDdl = dialectAdapter.buildCreateNamespaceSql(
                config.database,
                dbCharset,
                dbCollation
            ) + ";"
            writer.writeString("schema/000_database.sql", dbDdl)
            
            // Generate list of tables to backup
            val tablesToBackup = metadataAdapter.listTables(backupSession, config.database)
                .filter { it.type == "table" }
                .filter { config.tables.isEmpty() || config.tables.contains(it.name) }
                
            val tableEntries = mutableListOf<BackupTableEntry>()
            
            for ((idx, table) in tablesToBackup.withIndex()) {
                if (reporter.isCancelled()) throw Exception("Task cancelled by user")

                // Give table progress
                val baseProgress = (idx * 80) / tablesToBackup.size.coerceAtLeast(1)
                val tableProgressRange = 80 / tablesToBackup.size.coerceAtLeast(1)
                reporter.onProgress(baseProgress, "Exporting table: ${table.name}")

                // Save Schema
                val ddl = metadataAdapter.getDdl(backupSession, config.database, table.name)
                val ddlPath = BackupPackagePaths.tableDdl(idx)
                writer.writeString(ddlPath, ddl)

                // Save Data (Streaming + chunking)
                val dataPaths = mutableListOf<String>()
                if (config.mode != "structure_only") {
                    dataPaths.addAll(
                        exportTableData(
                            backupConn, table.name, writer, reporter,
                            baseProgress, tableProgressRange, idx, table.rowCount ?: 0L,
                            dialectAdapter, logicalBackupAdapter
                        )
                    )
                }
                
                tableEntries.add(BackupTableEntry(
                    tableName = table.name,
                    ddlFile = ddlPath,
                    rowEstimate = table.rowCount ?: 0L,
                    dataFiles = dataPaths
                ))
            }
            
            // Save Routines, Views, Triggers
            val objectEntries = mutableListOf<BackupObjectEntry>()
            
            if (config.includeRoutines) {
                reporter.onProgress(85, "Exporting routines")
                val routines = metadataAdapter.listRoutines(backupSession, config.database)
                for ((index, rt) in routines.withIndex()) {
                    val ddl = metadataAdapter.getObjectDdl(backupSession, config.database, rt.name, rt.type.lowercase())
                    if (ddl.isNotEmpty()) {
                        val path = BackupPackagePaths.routineDdl(index)
                        writer.writeString(path, ddl)
                        objectEntries.add(BackupObjectEntry(rt.name, rt.type.lowercase(), path))
                    }
                }
            }
            
            if (config.includeViews) {
                reporter.onProgress(90, "Exporting views")
                val views = metadataAdapter.listTables(backupSession, config.database).filter { it.type == "view" }
                for ((index, v) in views.withIndex()) {
                    val ddl = metadataAdapter.getDdl(backupSession, config.database, v.name)
                    if (ddl.isNotEmpty()) {
                        val path = BackupPackagePaths.viewDdl(index)
                        writer.writeString(path, ddl)
                        objectEntries.add(BackupObjectEntry(v.name, "view", path))
                    }
                }
            }
            
            if (config.includeTriggers) {
                reporter.onProgress(95, "Exporting triggers")
                val triggers = metadataAdapter.listTriggers(backupSession, config.database)
                for ((index, tg) in triggers.withIndex()) {
                    val ddl = metadataAdapter.getObjectDdl(backupSession, config.database, tg.name, "trigger")
                    if (ddl.isNotEmpty()) {
                        val path = BackupPackagePaths.triggerDdl(index)
                        writer.writeString(path, ddl)
                        objectEntries.add(BackupObjectEntry(tg.name, "trigger", path))
                    }
                }
            }
            
            // Build manifest and checksums
            val manifest = BackupManifest(
                formatVersion = 1,
                appVersion = "1.0",
                dbType = dbType.name.lowercase(),
                serverVersion = serverVersion,
                database = config.database,
                mode = config.mode,
                charset = dbCharset,
                collation = dbCollation,
                startedAt = timestamp,
                completedAt = timestampFormat.format(Date()),
                consistency = backupContext.consistency,
                binlogFile = backupContext.binlogFile,
                binlogPosition = backupContext.binlogPosition,
                tables = tableEntries,
                objects = objectEntries,
                warnings = backupContext.warnings
            )
            
            writer.writeManifest(manifest)
            writer.writeChecksums()
            
            // Output package: 简洁专业命名 database_YYYYMMDD_HHMM.edbkp
            val zipFile = File(backupsDir, BackupPackagePaths.outputFileName(config.database, timestamp))
            reporter.onProgress(98, "Packing ZIP archive")
            writer.packToZip(zipFile)
            
            reporter.onProgress(100, "Backup completed")
            
            return TaskResult(
                success = true,
                successCount = tableEntries.size + objectEntries.size,
                payload = mapOf(
                    "filePath" to zipFile.absolutePath,
                    "fileName" to zipFile.name,
                    "database" to config.database,
                    "manifestVersion" to "1",
                    "backupMode" to config.mode
                )
            )
            
        } finally {
            if (backupContextStarted) {
                try { logicalBackupAdapter.finish(backupConn) } catch (_: Exception) {}
            }
            try { backupSession.close() } catch (_: Exception) {}
            cleanupWriter?.cleanup()
        }
    }
    
    private fun exportTableData(
        conn: Connection,
        tableName: String,
        writer: BackupPackageWriter,
        reporter: TaskReporter,
        baseProgress: Int,
        progressRange: Int,
        tableIdx: Int,
        rowEstimate: Long,
        dialect: DialectAdapter,
        logicalBackupAdapter: LogicalBackupAdapter
    ): List<String> {
        val dataPaths = mutableListOf<String>()
        var partIndex = 1
        var currentRows = 0L
        var totalRows = 0L
        
        val maxRowsPerChunk = 100_000L
        
        // STREAMING Mode: use Integer.MIN_VALUE for MySQL streaming, 1000 for other DBs
        conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
            logicalBackupAdapter.configureStreamingStatement(stmt)
            stmt.queryTimeout = 14400

            stmt.executeQuery("SELECT * FROM ${dialect.quoteIdentifier(tableName)}").use { rs ->
                val meta = rs.metaData
                val colCount = meta.columnCount
                val cols = (1..colCount).joinToString(", ") { dialect.quoteIdentifier(meta.getColumnName(it)) }
                
                var dataWriter: BackupPackageWriter.DataWriter? = null
                var batchCount = 0
                
                fun openWriter() {
                    dataWriter?.close()
                    val path = BackupPackagePaths.tableData(tableIdx, partIndex)
                    dataWriter = writer.createGzipDataWriter(path)
                    dataPaths.add(path)
                    partIndex++
                    currentRows = 0
                    batchCount = 0
                }
                
                try {
                    openWriter()

                    while (rs.next()) {
                        if (reporter.isCancelled()) throw Exception("Task cancelled by user")
                        if (currentRows >= maxRowsPerChunk) {
                            if (batchCount > 0) dataWriter!!.write(";\n")
                            openWriter()
                        }

                        if (batchCount == 0) {
                            dataWriter!!.write("INSERT INTO ${dialect.quoteIdentifier(tableName)} ($cols) VALUES\n(")
                        } else {
                            dataWriter!!.write(",\n(")
                        }

                        for (i in 1..colCount) {
                            if (i > 1) dataWriter!!.write(", ")
                            val obj = rs.getObject(i)

                        val strVal = formatBackupSqlValue(meta.getColumnType(i), obj, rs, i, dialect)
                            dataWriter!!.write(strVal)
                        }
                        dataWriter!!.write(")")

                        batchCount++
                        currentRows++
                        totalRows++

                        if (batchCount >= 500) {
                            dataWriter!!.write(";\n")
                            batchCount = 0
                        }

                        // Update progress every 10,000 rows
                        if (totalRows % 10_000 == 0L) {
                            val tableProgress = if (rowEstimate > 0) {
                                ((totalRows.toFloat() / rowEstimate) * progressRange).toInt().coerceAtMost(progressRange)
                            } else {
                                (progressRange * 0.5).toInt() // assume halfway if no estimate
                            }
                            reporter.onProgress(baseProgress + tableProgress, "Exporting table $tableName: $totalRows rows...")
                        }

                        if (totalRows % 50_000 == 0L) {
                            reporter.onLog("INFO", "Table $tableName: exported $totalRows rows...")
                        }
                    }

                    if (batchCount > 0) {
                        dataWriter!!.write(";\n")
                    }
                } finally {
                    dataWriter?.close()
                }
            }
        }
        return dataPaths
    }
    
}

internal fun formatBackupSqlValue(
    type: Int,
    obj: Any?,
    resultSet: ResultSet,
    columnIndex: Int,
    dialect: DialectAdapter
): String {
    if (obj == null) return "NULL"
    return when (type) {
        java.sql.Types.TINYINT, java.sql.Types.SMALLINT,
        java.sql.Types.INTEGER, java.sql.Types.BIGINT,
        java.sql.Types.FLOAT, java.sql.Types.REAL,
        java.sql.Types.DOUBLE, java.sql.Types.NUMERIC,
        java.sql.Types.DECIMAL, java.sql.Types.BIT, java.sql.Types.BOOLEAN -> obj.toString()
        java.sql.Types.BINARY, java.sql.Types.VARBINARY,
        java.sql.Types.LONGVARBINARY, java.sql.Types.BLOB -> {
            val bytes = resultSet.getBytes(columnIndex)
            if (bytes == null) "NULL" else "X'" + bytes.joinToString("") { "%02X".format(it) } + "'"
        }
        else -> resultSet.getString(columnIndex)
            ?.let(dialect::formatExportStringLiteral)
            ?: "NULL"
    }
}
