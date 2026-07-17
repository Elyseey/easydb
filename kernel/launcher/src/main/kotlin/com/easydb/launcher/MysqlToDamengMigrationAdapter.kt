package com.easydb.launcher

import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.DialectAdapter
import com.easydb.common.IndexInfo
import com.easydb.common.MetadataAdapter
import com.easydb.common.MigrationAdapter
import com.easydb.common.MigrationConfig
import com.easydb.common.MigrationPreview
import com.easydb.common.MigrationTablePreview
import com.easydb.common.RoutineInfo
import com.easydb.common.SessionPair
import com.easydb.common.TableDefinition
import com.easydb.common.TableInfo
import com.easydb.common.TableVerifyResult
import com.easydb.common.TaskReporter
import com.easydb.common.TaskResult
import com.easydb.common.TaskStatus
import com.easydb.common.TriggerInfo
import com.easydb.drivers.dameng.DamengDialectAdapter
import com.easydb.drivers.dameng.DamengMetadataAdapter
import com.easydb.drivers.mysql.MysqlDialectAdapter
import com.easydb.drivers.mysql.MysqlMetadataAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * MySQL → 达梦表级迁移适配器。
 *
 * 这个适配器是跨驱动 pair adapter：源端使用 MySQL 元数据和查询语法，
 * 目标端使用达梦标识符、schema 切换和 DDL 规则，避免把 MySQL DDL 直接发给达梦。
 */
class MysqlToDamengMigrationAdapter(
    private val sourceMetadata: MetadataAdapter = MysqlMetadataAdapter(),
    private val sourceDialect: DialectAdapter = MysqlDialectAdapter(),
    private val targetMetadata: MetadataAdapter = DamengMetadataAdapter(),
    private val targetDialect: DialectAdapter = DamengDialectAdapter()
) : MigrationAdapter {

    override fun preview(config: MigrationConfig, sessions: SessionPair): MigrationPreview {
        val sourceObjects = getSourceObjects(sessions.source, config.sourceDatabase, config.tables)
        val sourceTables = sourceObjects.filter { it.type.equals("table", ignoreCase = true) }
        val skippedObjects = sourceObjects.filterNot { it.type.equals("table", ignoreCase = true) }
        val targetTables = loadTargetTables(sessions.target, config.targetDatabase)

        val previews = sourceTables.map { table ->
            val targetName = findTargetTableName(targetTables, table.name)
            val hasStructure = config.mode != "data_only"
            val hasData = config.mode != "structure_only"
            MigrationTablePreview(
                tableName = table.name,
                rowCount = table.rowCount,
                hasStructure = hasStructure,
                hasData = hasData,
                risk = if (targetName != null && hasStructure) "目标达梦 schema 已存在同名表 $targetName，将被覆盖" else null
            )
        }

        val warnings = buildList {
            addAll(previews.mapNotNull { it.risk })
            if (skippedObjects.isNotEmpty()) {
                add("MySQL → 达梦 MVP 仅支持表迁移，已跳过：${skippedObjects.joinToString { "${it.name}(${typeLabel(it.type)})" }}")
            }
        }

        return MigrationPreview(
            totalTables = previews.size,
            totalRows = previews.sumOf { it.rowCount ?: 0L },
            tables = previews,
            warnings = warnings
        )
    }

    override fun execute(
        config: MigrationConfig,
        sessions: SessionPair,
        reporter: TaskReporter
    ): TaskResult {
        val sourceConn = sessions.source.getJdbcConnection()
        val targetConn = sessions.target.getJdbcConnection()
        val sourceObjects = getSourceObjects(sessions.source, config.sourceDatabase, config.tables)
        val tables = sourceObjects.filter { it.type.equals("table", ignoreCase = true) }
        val skippedObjects = sourceObjects.filterNot { it.type.equals("table", ignoreCase = true) }

        if (tables.isEmpty()) {
            skippedObjects.forEach {
                reporter.onLog("WARN", "[${it.name}] MySQL → 达梦 MVP 暂不支持${typeLabel(it.type)}迁移，已跳过")
            }
            return TaskResult(
                success = true,
                successCount = 0,
                skippedCount = skippedObjects.size,
                payload = mapOf("message" to "没有可迁移的表对象")
            )
        }

        switchTargetSchema(targetConn, config.targetDatabase)

        val targetTables = loadTargetTables(sessions.target, config.targetDatabase)
        var successCount = 0
        var failureCount = 0
        val skippedCount = skippedObjects.size
        val tableRowCounts = linkedMapOf<String, Long>()
        val tableErrors = linkedMapOf<String, String>()
        val migratedTargetNames = linkedMapOf<String, String>()

        skippedObjects.forEach {
            reporter.onLog("WARN", "[${it.name}] MySQL → 达梦 MVP 暂不支持${typeLabel(it.type)}迁移，已跳过")
        }
        reporter.onLog("INFO", "开始 MySQL → 达梦迁移：${config.sourceDatabase} → ${config.targetDatabase}，共 ${tables.size} 张表")

        for ((index, table) in tables.withIndex()) {
            if (reporter.isCancelled()) {
                reporter.onLog("WARN", "迁移已被取消")
                break
            }

            val tableName = table.name
            val progress = (((index + 1).toDouble() / tables.size) * 100).toInt().coerceAtMost(99)
            reporter.onProgress(progress, "迁移表 $tableName (${index + 1}/${tables.size})")

            try {
                val targetTableName = if (config.mode == "data_only") {
                    findTargetTableName(targetTables, tableName)
                        ?: throw IllegalStateException("目标达梦 schema 中不存在表 $tableName，无法执行仅数据迁移")
                } else {
                    tableName
                }
                migratedTargetNames[tableName] = targetTableName

                val tableDefinition = if (config.mode != "data_only") {
                    reporter.onStep(tableName, TaskStatus.RUNNING, "迁移结构")
                    val definition = sourceMetadata.getTableDefinition(sessions.source, config.sourceDatabase, tableName)
                    val existingTargetName = findTargetTableName(targetTables, tableName)
                    migrateStructure(targetConn, config.targetDatabase, targetTableName, existingTargetName, definition)
                    reporter.onLog("INFO", "[$tableName] 结构迁移完成")
                    definition
                } else {
                    null
                }

                if (config.mode != "structure_only") {
                    reporter.onStep(tableName, TaskStatus.RUNNING, "迁移数据")
                    if (config.mode == "data_only") {
                        truncateTargetTable(targetConn, config.targetDatabase, targetTableName)
                        reporter.onLog("INFO", "[$tableName] 已清空目标表数据")
                    }
                    val sourceColumns = tableDefinition?.columns
                        ?: sourceMetadata.getColumns(sessions.source, config.sourceDatabase, tableName)
                    val rowCount = migrateData(sourceConn, targetConn, config, tableName, targetTableName, sourceColumns, reporter)
                    tableRowCounts[tableName] = rowCount
                    reporter.onLog("INFO", "[$tableName] 数据迁移完成，共 $rowCount 行")
                } else {
                    tableRowCounts[tableName] = 0L
                }

                if (config.mode != "data_only" && tableDefinition != null) {
                    reporter.onStep(tableName, TaskStatus.RUNNING, "创建索引")
                    createSecondaryIndexes(targetConn, config.targetDatabase, targetTableName, tableDefinition.indexes, reporter)
                }

                reporter.onStep(tableName, TaskStatus.COMPLETED)
                successCount++
            } catch (e: Exception) {
                tableRowCounts[tableName] = 0L
                tableErrors[tableName] = e.message ?: "未知错误"
                failureCount++
                reporter.onStep(tableName, TaskStatus.FAILED, e.message)
                reporter.onLog("ERROR", "[$tableName] 迁移失败：${e.message}")
            }
        }

        val verification = buildVerification(targetConn, config.targetDatabase, tableRowCounts, tableErrors, migratedTargetNames, reporter)
        reporter.onProgress(100, "迁移完成")
        reporter.onLog("INFO", "MySQL → 达梦迁移结束：成功 $successCount，失败 $failureCount，跳过 $skippedCount")

        return TaskResult(
            success = failureCount == 0,
            successCount = successCount,
            failureCount = failureCount,
            skippedCount = skippedCount,
            errorMessage = if (failureCount > 0) "部分表迁移失败" else null,
            verification = verification
        )
    }

    private fun getSourceObjects(session: DatabaseSession, database: String, selectedNames: List<String>): List<TableInfo> {
        val selected = selectedNames.takeIf { it.isNotEmpty() }?.toSet()
        val tablesAndViews = sourceMetadata.listTables(session, database)
        val routines = runCatching { sourceMetadata.listRoutines(session, database) }.getOrDefault(emptyList())
        val triggers = runCatching { sourceMetadata.listTriggers(session, database) }.getOrDefault(emptyList())
        val objects = tablesAndViews + routines.map { it.toTableInfo(database) } + triggers.map { it.toTableInfo(database) }
        return objects.filter { selected == null || it.name in selected }
    }

    private fun RoutineInfo.toTableInfo(database: String): TableInfo = TableInfo(
        name = name,
        schema = database,
        type = type.lowercase(),
        comment = comment
    )

    private fun TriggerInfo.toTableInfo(database: String): TableInfo = TableInfo(
        name = name,
        schema = database,
        type = "trigger",
        comment = listOfNotNull(timing, event, table?.let { "ON $it" }).joinToString(" ").ifBlank { comment }
    )

    private fun loadTargetTables(session: DatabaseSession, database: String): List<TableInfo> =
        runCatching {
            targetMetadata.listTables(session, database).filter { it.type.equals("table", ignoreCase = true) }
        }.getOrDefault(emptyList())

    private fun findTargetTableName(targetTables: List<TableInfo>, sourceTableName: String): String? =
        targetTables.firstOrNull { it.name == sourceTableName }?.name
            ?: targetTables.firstOrNull { it.name.equals(sourceTableName, ignoreCase = true) }?.name

    private fun switchTargetSchema(targetConn: Connection, targetDatabase: String) {
        val switchSql = targetDialect.buildSwitchDatabaseSql(targetDatabase)
        if (!switchSql.isNullOrBlank()) {
            targetConn.createStatement().use { it.execute(switchSql) }
        }
    }

    private fun migrateStructure(
        targetConn: Connection,
        targetDatabase: String,
        targetTableName: String,
        existingTargetName: String?,
        sourceDefinition: TableDefinition
    ) {
        dropTargetTable(targetConn, targetDatabase, existingTargetName ?: targetTableName)
        buildCreateTableStatements(targetDatabase, targetTableName, sourceDefinition)
            .forEach { sql -> targetConn.createStatement().use { it.execute(sql) } }
    }

    private fun dropTargetTable(targetConn: Connection, targetDatabase: String, targetTableName: String) {
        targetConn.createStatement().use {
            it.execute("DROP TABLE IF EXISTS ${quoteQualified(targetDatabase, targetTableName)}")
        }
    }

    private fun truncateTargetTable(targetConn: Connection, targetDatabase: String, targetTableName: String) {
        targetConn.createStatement().use {
            it.execute("TRUNCATE TABLE ${quoteQualified(targetDatabase, targetTableName)}")
        }
    }

    private fun buildCreateTableStatements(
        targetDatabase: String,
        targetTableName: String,
        sourceDefinition: TableDefinition
    ): List<String> {
        require(sourceDefinition.columns.isNotEmpty()) { "源表 $targetTableName 没有可迁移字段" }

        val lines = mutableListOf<String>()
        for (column in sourceDefinition.columns) {
            lines.add(buildColumnDefinition(column))
        }

        val primaryColumns = sourceDefinition.columns.filter { it.isPrimaryKey }
        if (primaryColumns.isNotEmpty()) {
            val pkColumns = primaryColumns.joinToString(", ") { targetDialect.quoteIdentifier(it.name) }
            lines.add("  PRIMARY KEY ($pkColumns)")
        }

        val tableName = quoteQualified(targetDatabase, targetTableName)
        val statements = mutableListOf("CREATE TABLE $tableName (\n${lines.joinToString(",\n")}\n)")
        val tableComment = sourceDefinition.table.comment?.takeIf { it.isNotBlank() }
        if (tableComment != null) {
            statements.add("COMMENT ON TABLE $tableName IS ${targetDialect.escapeValue(tableComment)}")
        }
        sourceDefinition.columns
            .filter { !it.comment.isNullOrBlank() }
            .forEach { column ->
                statements.add("COMMENT ON COLUMN $tableName.${targetDialect.quoteIdentifier(column.name)} IS ${targetDialect.escapeValue(column.comment)}")
            }
        return statements
    }

    private fun buildColumnDefinition(column: ColumnInfo): String {
        val targetType = mapMysqlTypeToDameng(column.type)
        val defaultLiteral = normalizeDamengDefault(column.defaultValue)
        return buildString {
            append("  ${targetDialect.quoteIdentifier(column.name)} $targetType")
            if (column.isAutoIncrement) append(" $DAMENG_AUTO_INCREMENT_CLAUSE")
            if (!column.nullable || column.isPrimaryKey || column.isAutoIncrement) append(" NOT NULL")
            if (!defaultLiteral.isNullOrBlank() && !column.isAutoIncrement) append(" DEFAULT $defaultLiteral")
        }
    }

    private fun createSecondaryIndexes(
        targetConn: Connection,
        targetDatabase: String,
        targetTableName: String,
        indexes: List<IndexInfo>,
        reporter: TaskReporter
    ) {
        val failures = mutableListOf<String>()
        indexes
            .filterNot { it.isPrimary }
            .filter { it.columns.isNotEmpty() }
            .forEach { index ->
                val prefix = if (index.isUnique) "UNIQUE " else ""
                val indexName = targetDialect.quoteIdentifier(buildDamengIndexName(targetTableName, index.name))
                val tableName = quoteQualified(targetDatabase, targetTableName)
                val columns = index.columns.joinToString(", ") { targetDialect.quoteIdentifier(it) }
                val sql = "CREATE ${prefix}INDEX $indexName ON $tableName ($columns)"
                try {
                    targetConn.createStatement().use { it.execute(sql) }
                } catch (e: Exception) {
                    failures.add("${index.name}: ${e.message}")
                    reporter.onLog("WARN", "[$targetTableName] 索引 ${index.name} 创建失败：${e.message}")
                }
            }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("部分索引创建失败：${failures.joinToString("; ")}")
        }
    }

    private fun migrateData(
        sourceConn: Connection,
        targetConn: Connection,
        config: MigrationConfig,
        sourceTableName: String,
        targetTableName: String,
        sourceColumns: List<ColumnInfo>,
        reporter: TaskReporter
    ): Long {
        val fullSourceTable = "${sourceDialect.quoteIdentifier(config.sourceDatabase)}.${sourceDialect.quoteIdentifier(sourceTableName)}"
        val fullTargetTable = quoteQualified(config.targetDatabase, targetTableName)
        val batchSize = 1000
        val columns = mutableListOf<String>()
        val timeColumns = mutableSetOf<Int>()

        sourceConn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM $fullSourceTable LIMIT 0").use { rs ->
                val meta = rs.metaData
                for (i in 1..meta.columnCount) {
                    columns.add(meta.getColumnName(i))
                    if (meta.getColumnType(i) == Types.TIME) {
                        timeColumns.add(i)
                    }
                }
            }
        }

        if (columns.isEmpty()) return 0L

        val columnCount = columns.size
        val columnSql = columns.joinToString(", ") { targetDialect.quoteIdentifier(it) }
        val placeholders = columns.joinToString(", ") { "?" }
        val insertSql = "INSERT INTO $fullTargetTable ($columnSql) VALUES ($placeholders)"
        val channel = Channel<List<Array<Any?>>>(capacity = 2)
        val totalRows = AtomicLong(0)
        val sourceAutoIncrementColumns = sourceColumns
            .filter { column -> column.isAutoIncrement && columns.any { it.equals(column.name, ignoreCase = true) } }

        runBlocking {
            launch(Dispatchers.IO) {
                val identityInsertEnabled = if (sourceAutoIncrementColumns.isNotEmpty()) {
                    enableIdentityInsertIfSupported(targetConn, targetTableName, reporter)
                } else {
                    false
                }
                val previousAutoCommit = targetConn.autoCommit
                targetConn.autoCommit = false
                try {
                    targetConn.prepareStatement(insertSql).use { ps ->
                        for (batch in channel) {
                            if (reporter.isCancelled()) break
                            for (row in batch) {
                                for (i in 0 until columnCount) {
                                    if ((i + 1) in timeColumns && row[i] is String) {
                                        ps.setString(i + 1, row[i] as String)
                                    } else {
                                        ps.setObject(i + 1, row[i])
                                    }
                                }
                                ps.addBatch()
                                totalRows.incrementAndGet()
                            }
                            ps.executeBatch()
                            targetConn.commit()
                        }
                    }
                } catch (e: Exception) {
                    runCatching { targetConn.rollback() }
                    throw e
                } finally {
                    targetConn.autoCommit = previousAutoCommit
                    if (identityInsertEnabled) {
                        disableIdentityInsert(targetConn, targetTableName, reporter)
                    }
                }
            }

            try {
                sourceConn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
                    stmt.fetchSize = Integer.MIN_VALUE
                    stmt.executeQuery("SELECT * FROM $fullSourceTable").use { rs ->
                        var currentBatch = ArrayList<Array<Any?>>(batchSize)
                        while (rs.next()) {
                            if (reporter.isCancelled()) break
                            val row = Array<Any?>(columnCount) { null }
                            for (i in 1..columnCount) {
                                row[i - 1] = if (i in timeColumns) rs.getString(i) else rs.getObject(i)
                            }
                            currentBatch.add(row)
                            if (currentBatch.size >= batchSize) {
                                channel.send(currentBatch)
                                currentBatch = ArrayList(batchSize)
                            }
                        }
                        if (currentBatch.isNotEmpty()) {
                            channel.send(currentBatch)
                        }
                    }
                }
            } finally {
                channel.close()
            }
        }

        return totalRows.get()
    }

    private fun enableIdentityInsertIfSupported(
        targetConn: Connection,
        targetTableName: String,
        reporter: TaskReporter
    ): Boolean {
        val sql = buildIdentityInsertSql(targetTableName, enabled = true)
        return try {
            targetConn.createStatement().use { it.execute(sql) }
            reporter.onLog("INFO", "[$targetTableName] 已开启达梦 IDENTITY_INSERT，允许保留源库自增列值")
            true
        } catch (_: Exception) {
            // 新建表默认使用 AUTO_INCREMENT，不需要 IDENTITY_INSERT；已有 IDENTITY 表会在插入时暴露真实错误。
            false
        }
    }

    private fun disableIdentityInsert(
        targetConn: Connection,
        targetTableName: String,
        reporter: TaskReporter
    ) {
        val sql = buildIdentityInsertSql(targetTableName, enabled = false)
        try {
            targetConn.createStatement().use { it.execute(sql) }
            reporter.onLog("INFO", "[$targetTableName] 已关闭达梦 IDENTITY_INSERT")
        } catch (e: Exception) {
            reporter.onLog("WARN", "[$targetTableName] 关闭达梦 IDENTITY_INSERT 失败：${e.message}")
        }
    }

    private fun buildIdentityInsertSql(targetTableName: String, enabled: Boolean): String {
        val state = if (enabled) "ON" else "OFF"
        return "SET IDENTITY_INSERT ${targetDialect.quoteIdentifier(targetTableName)} $state"
    }

    private fun buildVerification(
        targetConn: Connection,
        targetDatabase: String,
        tableRowCounts: Map<String, Long>,
        tableErrors: Map<String, String>,
        migratedTargetNames: Map<String, String>,
        reporter: TaskReporter
    ): List<TableVerifyResult> {
        return tableRowCounts.map { (sourceTableName, sourceRows) ->
            val targetTableName = migratedTargetNames[sourceTableName] ?: sourceTableName
            val error = tableErrors[sourceTableName]
            val targetRows = if (error != null) {
                0L
            } else {
                try {
                    targetConn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT COUNT(*) FROM ${quoteQualified(targetDatabase, targetTableName)}").use { rs ->
                            if (rs.next()) rs.getLong(1) else 0L
                        }
                    }
                } catch (e: Exception) {
                    reporter.onLog("WARN", "[$sourceTableName] COUNT(*) 查询失败：${e.message}")
                    -1L
                }
            }

            val status = when {
                error != null -> "failed"
                targetRows < 0L -> "failed"
                sourceRows == targetRows -> "match"
                else -> "mismatch"
            }

            TableVerifyResult(
                tableName = sourceTableName,
                sourceRows = sourceRows,
                targetRows = if (targetRows < 0L) 0L else targetRows,
                status = status,
                errorMessage = error ?: if (targetRows < 0L) "验证查询失败" else null
            )
        }.sortedWith(compareBy(
            { if (it.status == "failed") 0 else if (it.status == "mismatch") 1 else 2 },
            { it.tableName }
        ))
    }

    private fun quoteQualified(schema: String, name: String): String =
        "${targetDialect.quoteIdentifier(schema)}.${targetDialect.quoteIdentifier(name)}"

    private fun typeLabel(type: String): String = when (type.lowercase()) {
        "table" -> "表"
        "view" -> "视图"
        "procedure" -> "存储过程"
        "function" -> "函数"
        "trigger" -> "触发器"
        else -> type
    }

    companion object {
        internal const val DAMENG_AUTO_INCREMENT_CLAUSE = "AUTO_INCREMENT"

        internal fun mapMysqlTypeToDameng(rawType: String): String {
            val clean = rawType.trim().replace(Regex("\\s+"), " ")
            val upper = clean.uppercase()
            val unsigned = Regex("""\bUNSIGNED\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)
            val withoutUnsigned = clean.replace(Regex("""\s+UNSIGNED\b""", RegexOption.IGNORE_CASE), "").trim()
            val base = withoutUnsigned.substringBefore("(").trim().uppercase()
            val args = Regex("""\((.*)\)""").find(withoutUnsigned)?.groupValues?.get(1)?.trim()
            val length = firstNumericArg(args)

            return when (base) {
                "CHAR" -> "CHAR(${length ?: 1} char)"
                "VARCHAR", "CHARACTER VARYING" -> "VARCHAR(${length ?: 255} char)"
                "TINYTEXT", "MEDIUMTEXT", "LONG VARCHAR", "LONG", "LONGTEXT" -> "CLOB"
                "TEXT" -> "TEXT"
                "BINARY" -> "BINARY(${length ?: 1})"
                "VARBINARY" -> "VARBINARY(${length ?: 255})"
                "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONG VARBINARY", "LONGBLOB" -> "BLOB"
                "ENUM", "SET" -> "VARCHAR(${enumMaxLength(args) ?: 255} char)"
                "BIT" -> mapBitType(length)
                "BOOL", "BOOLEAN" -> "TINYINT"
                "TINYINT", "INT1" -> if (unsigned) "SMALLINT" else "TINYINT"
                "SMALLINT", "INT2" -> if (unsigned) "INT" else "SMALLINT"
                "MEDIUMINT", "MIDDLEINT", "INT3" -> "INT"
                "INT", "INTEGER", "INT4" -> if (unsigned) "BIGINT" else "INT"
                "BIGINT", "INT8" -> if (unsigned) "DECIMAL(20,0)" else "BIGINT"
                "SERIAL" -> "BIGINT"
                "DECIMAL", "DEC", "NUMERIC", "FIXED" -> args?.let { "DECIMAL($it)" } ?: "DECIMAL"
                "FLOAT", "FLOAT4" -> mapFloatType(args, upper)
                "DOUBLE", "DOUBLE PRECISION", "REAL", "FLOAT8" -> {
                    if (args?.contains(",") == true) "NUMBER($args)" else "DOUBLE"
                }
                "DATE" -> "DATE"
                "TIME" -> "TIME"
                "DATETIME", "TIMESTAMP" -> "TIMESTAMP"
                "YEAR" -> "INT"
                "JSON" -> "CLOB"
                "GEOMETRY" -> "ST_GEOMETRY"
                "POINT" -> "ST_POINT"
                "LINESTRING" -> "ST_LINESTRING"
                "POLYGON" -> "ST_POLYGON"
                "MULTIPOINT" -> "ST_MULTIPOINT"
                "MULTILINESTRING" -> "ST_MULTILINESTRING"
                "MULTIPOLYGON" -> "ST_MULTIPOLYGON"
                "GEOMETRYCOLLECTION" -> "ST_GEOMCOLLECTION"
                else -> base.ifBlank { rawType }
            }
        }

        internal fun normalizeDamengDefault(defaultValue: String?): String? {
            val raw = defaultValue?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (raw.equals("NULL", ignoreCase = true)) return null
            val upper = raw.uppercase()
            if (upper == "NOW()") return "CURRENT_TIMESTAMP"
            if (upper == "TRUE") return "1"
            if (upper == "FALSE") return "0"
            if (upper in setOf("CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME", "SYSDATE")) return upper
            if (Regex("""CURRENT_TIMESTAMP\(\d+\)""").matches(upper)) return upper
            if (Regex("""b'[01]+'""", RegexOption.IGNORE_CASE).matches(raw)) return raw.substringAfter("'").substringBefore("'").toInt(2).toString()
            if (Regex("""[-+]?\d+(\.\d+)?""").matches(raw)) return raw
            if (raw.startsWith("'") && raw.endsWith("'")) return raw
            if (raw.contains("(")) return null
            return "'${raw.replace("'", "''")}'"
        }

        private fun firstNumericArg(args: String?): Int? =
            args?.split(",")?.firstOrNull()?.trim()?.toIntOrNull()

        private fun enumMaxLength(args: String?): Int? {
            if (args.isNullOrBlank()) return null
            val maxLength = Regex("""'((?:''|[^'])*)'""")
                .findAll(args)
                .map { it.groupValues[1].replace("''", "'").length }
                .maxOrNull()
            return maxLength?.let { max(1, it) }
        }

        private fun mapBitType(length: Int?): String = when (length ?: 1) {
            1 -> "BIT"
            in 2..7 -> "BYTE"
            in 8..15 -> "SMALLINT"
            in 16..31 -> "INT"
            in 32..63 -> "BIGINT"
            else -> "NUMBER"
        }

        private fun mapFloatType(args: String?, upper: String): String {
            val precision = firstNumericArg(args)
            return when {
                args?.contains(",") == true -> "FLOAT"
                precision != null && !upper.contains(",") -> if (precision <= 24) "FLOAT" else "DOUBLE"
                else -> "FLOAT"
            }
        }

        private fun buildDamengIndexName(tableName: String, sourceIndexName: String): String {
            val raw = "IDX_${tableName}_${sourceIndexName}"
            if (raw.length <= 120) return raw
            val hash = Integer.toHexString(raw.hashCode())
            return raw.take(120 - hash.length - 1) + "_" + hash
        }
    }
}
