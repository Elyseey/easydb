package com.easydb.drivers.dameng

import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.IndexInfo
import com.easydb.common.MetadataAdapter
import com.easydb.common.SessionPair
import com.easydb.common.SyncAdapter
import com.easydb.common.SyncConfig
import com.easydb.common.SyncKeyPolicy
import com.easydb.common.SyncPreview
import com.easydb.common.SyncTablePreview
import com.easydb.common.TableDefinition
import com.easydb.common.TableInfo
import com.easydb.common.TableVerifyResult
import com.easydb.common.TaskReporter
import com.easydb.common.TaskResult
import com.easydb.common.TaskStatus
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/** One-shot, key-based Dameng -> Dameng table synchronization. */
class DamengSyncAdapter(
    private val metadata: MetadataAdapter = DamengMetadataAdapter(),
    private val dialect: DamengDialectAdapter = DamengDialectAdapter()
) : SyncAdapter {

    override fun preview(config: SyncConfig, sessions: SessionPair): SyncPreview {
        val targetTables = tableObjects(sessions.target, config.targetDatabase).associateBy { it.name }
        val previews = syncObjects(sessions.source, config).map { objectInfo ->
            if (!objectInfo.type.equals("table", ignoreCase = true)) {
                return@map SyncTablePreview(
                    tableName = objectInfo.name,
                    canSync = false,
                    reason = "达梦一次性数据同步当前仅支持表"
                )
            }

            val sourceDefinition = metadata.getTableDesign(
                sessions.source,
                config.sourceDatabase,
                objectInfo.name
            )
            val keyColumns = SyncKeyPolicy.reliableKey(sourceDefinition)
            val targetTable = targetTables[objectInfo.name]
            val reason = when {
                keyColumns == null -> "表没有非空主键或唯一键，无法安全执行 UPSERT"
                targetTable != null -> {
                    val targetDefinition = metadata.getTableDesign(
                        sessions.target,
                        config.targetDatabase,
                        targetTable.name
                    )
                    if (!SyncKeyPolicy.hasReliableKey(targetDefinition, keyColumns)) {
                        "目标表缺少与源表一致的非空主键或唯一键"
                    } else null
                }
                else -> null
            }
            val estimatedRows = objectInfo.rowCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
            SyncTablePreview(
                tableName = objectInfo.name,
                insertCount = if (targetTable == null && reason == null) estimatedRows else 0,
                updateCount = if (targetTable != null && reason == null) estimatedRows else 0,
                canSync = reason == null,
                reason = reason ?: if (targetTable == null) "目标表不存在，将按达梦方言自动创建" else null
            )
        }
        return SyncPreview(
            totalTables = previews.size,
            tables = previews,
            warnings = previews.mapNotNull { preview -> preview.reason?.let { "${preview.tableName}：$it" } }
        )
    }

    override fun execute(config: SyncConfig, sessions: SessionPair, reporter: TaskReporter): TaskResult {
        val sourceConnection = sessions.source.getJdbcConnection()
        val targetConnection = sessions.target.getJdbcConnection()
        switchSchema(targetConnection, config.targetDatabase)

        val objects = syncObjects(sessions.source, config)
        val tables = objects.filter { it.type.equals("table", ignoreCase = true) }
        val unsupported = objects.filterNot { it.type.equals("table", ignoreCase = true) }
        unsupported.forEach { reporter.onLog("WARN", "[${it.name}] 达梦一次性数据同步仅支持表，已跳过 ${it.type}") }
        if (tables.isEmpty()) {
            return TaskResult(
                success = true,
                skippedCount = unsupported.size,
                payload = mapOf("message" to "没有可同步的表对象")
            )
        }

        val initialTargets = tableObjects(sessions.target, config.targetDatabase).associateBy { it.name }.toMutableMap()
        val transferredRows = linkedMapOf<String, Long>()
        val errors = linkedMapOf<String, String>()
        var successCount = 0
        var failureCount = 0

        reporter.onLog("INFO", "开始达梦数据同步：${config.sourceDatabase} → ${config.targetDatabase}，共 ${tables.size} 张表")
        tables.forEachIndexed { index, table ->
            if (reporter.isCancelled()) return@forEachIndexed
            val tableName = table.name
            reporter.onProgress(((index.toDouble() / tables.size) * 100).toInt().coerceAtMost(99), "同步表 $tableName (${index + 1}/${tables.size})")
            reporter.onStep(tableName, TaskStatus.RUNNING, "校验同步键")
            try {
                val sourceDefinition = metadata.getTableDesign(sessions.source, config.sourceDatabase, tableName)
                val keyColumns = SyncKeyPolicy.reliableKey(sourceDefinition)
                    ?: error("表没有非空主键或唯一键，已拒绝同步")

                var targetTable = initialTargets[tableName]
                if (targetTable == null) {
                    reporter.onStep(tableName, TaskStatus.RUNNING, "创建目标表")
                    createTargetTable(targetConnection, sourceDefinition)
                    targetTable = table.copy(schema = config.targetDatabase)
                    initialTargets[tableName] = targetTable
                }

                val targetDefinition = metadata.getTableDesign(sessions.target, config.targetDatabase, targetTable.name)
                require(SyncKeyPolicy.hasReliableKey(targetDefinition, keyColumns)) {
                    "目标表缺少与源表一致的非空主键或唯一键，已拒绝同步"
                }
                requireMatchingColumns(sourceDefinition.columns, targetDefinition.columns)

                reporter.onStep(tableName, TaskStatus.RUNNING, "同步数据")
                transferredRows[tableName] = syncTable(
                    sourceConnection,
                    targetConnection,
                    config.sourceDatabase,
                    config.targetDatabase,
                    tableName,
                    sourceDefinition.columns,
                    keyColumns,
                    reporter
                )
                reporter.onStep(tableName, TaskStatus.COMPLETED, "同步 ${transferredRows[tableName]} 行")
                successCount++
            } catch (e: Exception) {
                transferredRows[tableName] = 0
                errors[tableName] = e.message ?: "未知错误"
                failureCount++
                reporter.onStep(tableName, TaskStatus.FAILED, e.message)
                reporter.onLog("ERROR", "[$tableName] 同步失败：${e.message}")
            }
        }

        val verification = verify(targetConnection, config.targetDatabase, transferredRows, errors, reporter)
        if (!reporter.isCancelled()) reporter.onProgress(100, "同步完成")
        return TaskResult(
            success = failureCount == 0 && !reporter.isCancelled(),
            successCount = successCount,
            failureCount = failureCount,
            skippedCount = unsupported.size,
            errorMessage = if (failureCount > 0) "部分表同步失败" else null,
            verification = verification
        )
    }

    private fun syncObjects(session: DatabaseSession, config: SyncConfig): List<TableInfo> {
        val selected = config.tables.takeIf { it.isNotEmpty() }?.toSet()
        val tablesAndViews = metadata.listTables(session, config.sourceDatabase)
        val routines = metadata.listRoutines(session, config.sourceDatabase).map {
            TableInfo(it.name, config.sourceDatabase, it.type.lowercase(), comment = it.comment)
        }
        val triggers = metadata.listTriggers(session, config.sourceDatabase).map {
            TableInfo(it.name, config.sourceDatabase, "trigger", comment = it.comment)
        }
        return (tablesAndViews + routines + triggers).filter { selected == null || it.name in selected }
    }

    private fun tableObjects(session: DatabaseSession, database: String): List<TableInfo> =
        metadata.listTables(session, database).filter { it.type.equals("table", ignoreCase = true) }

    private fun switchSchema(connection: Connection, database: String) {
        dialect.buildSwitchDatabaseSql(database)?.let { executeSql(connection, it) }
    }

    private fun createTargetTable(connection: Connection, source: TableDefinition) {
        val tableOnly = source.copy(indexes = source.indexes.filter { it.isPrimary })
        dialect.buildCreateTableStatements(tableOnly).forEach { executeSql(connection, it) }
        source.indexes.filterNot { it.isPrimary }.filter { it.columns.isNotEmpty() }.forEach { index ->
            executeSql(connection, createIndexSql(source.table.name, index))
        }
    }

    private fun createIndexSql(tableName: String, index: IndexInfo): String {
        val unique = if (index.isUnique) "UNIQUE " else ""
        val columns = index.columns.joinToString(", ") { dialect.quoteIdentifier(it) }
        return "CREATE ${unique}INDEX ${dialect.quoteIdentifier(index.name)} ON ${dialect.quoteIdentifier(tableName)} ($columns)"
    }

    private fun requireMatchingColumns(source: List<ColumnInfo>, target: List<ColumnInfo>) {
        val targetNames = target.map { it.name }.toSet()
        val missing = source.map { it.name }.filterNot { it in targetNames }
        require(missing.isEmpty()) { "目标表缺少字段：${missing.joinToString(", ")}" }
    }

    private fun syncTable(
        sourceConnection: Connection,
        targetConnection: Connection,
        sourceDatabase: String,
        targetDatabase: String,
        tableName: String,
        columns: List<ColumnInfo>,
        keyColumns: List<String>,
        reporter: TaskReporter
    ): Long {
        require(columns.isNotEmpty()) { "表没有可同步字段" }
        val columnNames = columns.map { it.name }
        val sourceTable = qualified(sourceDatabase, tableName)
        val targetTable = qualified(targetDatabase, tableName)
        val selectColumns = columnNames.joinToString(", ") { dialect.quoteIdentifier(it) }
        val mergeSql = buildMergeSql(targetTable, columnNames, keyColumns)
        val previousAutoCommit = targetConnection.autoCommit
        var transferred = 0L
        var identityInsertEnabled = false

        targetConnection.autoCommit = false
        try {
            sourceConnection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { select ->
                select.fetchSize = BATCH_SIZE
                select.executeQuery("SELECT $selectColumns FROM $sourceTable").use { rows ->
                    val jdbcTypes = (1..rows.metaData.columnCount).map { rows.metaData.getColumnType(it) }
                    val hasIdentity = (1..rows.metaData.columnCount).any { column ->
                        runCatching { rows.metaData.isAutoIncrement(column) }.getOrDefault(false)
                    }
                    if (hasIdentity) {
                        executeSql(targetConnection, "SET IDENTITY_INSERT ${dialect.quoteIdentifier(tableName)} ON")
                        identityInsertEnabled = true
                    }
                    targetConnection.prepareStatement(mergeSql).use { merge ->
                        var batchSize = 0
                        while (rows.next() && !reporter.isCancelled()) {
                            jdbcTypes.forEachIndexed { offset, type -> bind(merge, offset + 1, rows, offset + 1, type) }
                            merge.addBatch()
                            transferred++
                            batchSize++
                            if (batchSize == BATCH_SIZE) {
                                merge.executeBatch()
                                targetConnection.commit()
                                batchSize = 0
                            }
                        }
                        if (batchSize > 0) {
                            merge.executeBatch()
                            targetConnection.commit()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { targetConnection.rollback() }
            throw e
        } finally {
            targetConnection.autoCommit = previousAutoCommit
            if (identityInsertEnabled) {
                runCatching { executeSql(targetConnection, "SET IDENTITY_INSERT ${dialect.quoteIdentifier(tableName)} OFF") }
                    .onFailure { reporter.onLog("WARN", "[$tableName] 关闭 IDENTITY_INSERT 失败：${it.message}") }
            }
        }
        return transferred
    }

    internal fun buildMergeSql(targetTable: String, columns: List<String>, keyColumns: List<String>): String {
        val aliases = columns.joinToString(", ") { "? AS ${dialect.quoteIdentifier(it)}" }
        val match = keyColumns.joinToString(" AND ") {
            "t.${dialect.quoteIdentifier(it)} = s.${dialect.quoteIdentifier(it)}"
        }
        val updateColumns = columns.filterNot { it in keyColumns }
        val update = if (updateColumns.isEmpty()) "" else updateColumns.joinToString(", ", prefix = " WHEN MATCHED THEN UPDATE SET ") {
            "t.${dialect.quoteIdentifier(it)} = s.${dialect.quoteIdentifier(it)}"
        }
        val insertColumns = columns.joinToString(", ") { dialect.quoteIdentifier(it) }
        val insertValues = columns.joinToString(", ") { "s.${dialect.quoteIdentifier(it)}" }
        return "MERGE INTO $targetTable t USING (SELECT $aliases FROM DUAL) s ON ($match)$update " +
            "WHEN NOT MATCHED THEN INSERT ($insertColumns) VALUES ($insertValues)"
    }

    private fun bind(statement: PreparedStatement, parameter: Int, rows: ResultSet, column: Int, jdbcType: Int) {
        when (jdbcType) {
            Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> statement.setString(parameter, rows.getString(column))
            Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> statement.setBytes(parameter, rows.getBytes(column))
            else -> statement.setObject(parameter, rows.getObject(column))
        }
    }

    private fun verify(
        connection: Connection,
        database: String,
        sourceRows: Map<String, Long>,
        errors: Map<String, String>,
        reporter: TaskReporter
    ): List<TableVerifyResult> = sourceRows.map { (tableName, transferred) ->
        val error = errors[tableName]
        val targetRows = if (error != null) -1L else runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM ${qualified(database, tableName)}").use { rows ->
                    if (rows.next()) rows.getLong(1) else 0L
                }
            }
        }.getOrElse {
            reporter.onLog("WARN", "[$tableName] 行数验证失败：${it.message}")
            -1L
        }
        TableVerifyResult(
            tableName = tableName,
            sourceRows = transferred,
            targetRows = targetRows.coerceAtLeast(0),
            status = when {
                error != null || targetRows < 0 -> "failed"
                targetRows >= transferred -> "match"
                else -> "mismatch"
            },
            errorMessage = error ?: if (targetRows < 0) "验证查询失败" else null
        )
    }

    private fun qualified(database: String, tableName: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(tableName)}"

    private fun executeSql(connection: Connection, sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    companion object {
        private const val BATCH_SIZE = 1000
    }
}
