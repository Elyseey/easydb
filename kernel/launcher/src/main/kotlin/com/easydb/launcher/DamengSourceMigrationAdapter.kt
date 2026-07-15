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
import com.easydb.common.SessionPair
import com.easydb.common.TableDefinition
import com.easydb.common.TableInfo
import com.easydb.common.TableVerifyResult
import com.easydb.common.TaskReporter
import com.easydb.common.TaskResult
import com.easydb.common.TaskStatus
import com.easydb.drivers.dameng.DamengDialectAdapter
import com.easydb.drivers.dameng.DamengMetadataAdapter
import com.easydb.drivers.mysql.MysqlDialectAdapter
import com.easydb.drivers.mysql.MysqlMetadataAdapter
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

/**
 * Shared table-migration orchestration for the two supported Dameng source pairs.
 *
 * Dameng catalog identifiers are kept byte-for-byte. Target-specific DDL and type
 * conversion stay behind [Target] instead of leaking into the orchestration.
 */
class DamengSourceMigrationAdapter private constructor(
    private val target: Target,
    private val sourceMetadata: MetadataAdapter = DamengMetadataAdapter(),
    private val sourceDialect: DialectAdapter = DamengDialectAdapter(),
    private val targetMetadata: MetadataAdapter = target.metadata,
    private val targetDialect: DialectAdapter = target.dialect
) : MigrationAdapter {

    override fun preview(config: MigrationConfig, sessions: SessionPair): MigrationPreview {
        validateMode(config.mode)
        val objects = sourceObjects(sessions.source, config)
        val tables = objects.filter { it.type.equals("table", ignoreCase = true) }
        val skipped = objects.filterNot { it.type.equals("table", ignoreCase = true) }
        val targetTables = targetTables(sessions.target, config.targetDatabase)
        val hasStructure = config.mode != MODE_DATA_ONLY
        val hasData = config.mode != MODE_STRUCTURE_ONLY
        val previews = tables.map { table ->
            val exists = findExactTargetName(targetTables, table.name) != null
            MigrationTablePreview(
                tableName = table.name,
                rowCount = table.rowCount,
                hasStructure = hasStructure,
                hasData = hasData,
                risk = if (exists && hasStructure) "目标${target.label}中已存在同名表 ${table.name}，将被覆盖" else null
            )
        }
        return MigrationPreview(
            totalTables = previews.size,
            totalRows = previews.sumOf { it.rowCount ?: 0L },
            tables = previews,
            warnings = buildList {
                addAll(previews.mapNotNull { it.risk })
                if (target == Target.MYSQL && tables.isNotEmpty()) {
                    add("达梦 → MySQL 使用保守类型映射；时区、区间及达梦专属类型会降级为字符串或大文本，请在执行前核对目标结构")
                }
                if (skipped.isNotEmpty()) {
                    add("达梦源迁移当前仅支持表，已跳过：${skipped.joinToString { "${it.name}(${it.type})" }}")
                }
            }
        )
    }

    override fun execute(config: MigrationConfig, sessions: SessionPair, reporter: TaskReporter): TaskResult {
        validateMode(config.mode)
        val sourceConnection = sessions.source.getJdbcConnection()
        val targetConnection = sessions.target.getJdbcConnection()
        val objects = sourceObjects(sessions.source, config)
        val tables = objects.filter { it.type.equals("table", ignoreCase = true) }
        val skipped = objects.filterNot { it.type.equals("table", ignoreCase = true) }

        skipped.forEach { reporter.onLog("WARN", "[${it.name}] 达梦源迁移当前仅支持表，已跳过 ${it.type}") }
        if (tables.isEmpty()) {
            return TaskResult(true, skippedCount = skipped.size, payload = mapOf("message" to "没有可迁移的表对象"))
        }

        switchTargetDatabase(targetConnection, config.targetDatabase)
        if (target == Target.MYSQL) {
            reporter.onLog("WARN", "达梦 → MySQL 使用保守类型映射；达梦专属类型可能降级为 VARCHAR/LONGTEXT")
        }
        reporter.onLog("INFO", "开始达梦 → ${target.label}迁移：${config.sourceDatabase} → ${config.targetDatabase}，共 ${tables.size} 张表")

        val initialTargetTables = targetTables(sessions.target, config.targetDatabase)
        var successCount = 0
        var failureCount = 0
        val rowCounts = linkedMapOf<String, Long>()
        val errors = linkedMapOf<String, String>()

        for ((index, table) in tables.withIndex()) {
            if (reporter.isCancelled()) {
                reporter.onLog("WARN", "迁移已被取消")
                break
            }
            val tableName = table.name
            reporter.onProgress((((index + 1.0) / tables.size) * 100).toInt().coerceAtMost(99), "迁移表 $tableName (${index + 1}/${tables.size})")
            try {
                val targetTableName = if (config.mode == MODE_DATA_ONLY) {
                    findExactTargetName(initialTargetTables, tableName)
                        ?: error("目标${target.label}中不存在精确匹配的表 $tableName，无法执行仅数据迁移")
                } else {
                    tableName
                }

                val definition = if (config.mode != MODE_DATA_ONLY) {
                    reporter.onStep(tableName, TaskStatus.RUNNING, "迁移结构")
                    enrichAutoIncrement(
                        sourceConnection,
                        config.sourceDatabase,
                        tableName,
                        sourceMetadata.getTableDefinition(sessions.source, config.sourceDatabase, tableName)
                    ).also {
                        migrateStructure(targetConnection, config.targetDatabase, targetTableName, it)
                    }
                } else null

                if (config.mode != MODE_STRUCTURE_ONLY) {
                    reporter.onStep(tableName, TaskStatus.RUNNING, "迁移数据")
                    if (config.mode == MODE_DATA_ONLY) {
                        executeSql(targetConnection, "TRUNCATE TABLE ${target.qualified(config.targetDatabase, targetTableName)}")
                    }
                    rowCounts[tableName] = migrateData(
                        sourceConnection, targetConnection, config, tableName, targetTableName, reporter
                    )
                    if (reporter.isCancelled()) {
                        reporter.onStep(tableName, TaskStatus.CANCELLED, "迁移已取消，目标表可能包含已提交批次")
                        reporter.onLog("WARN", "[$tableName] 迁移已取消；已提交的批次保留在目标表中")
                        break
                    }
                } else {
                    rowCounts[tableName] = 0L
                }

                if (definition != null) createIndexes(targetConnection, config.targetDatabase, targetTableName, definition.indexes)
                reporter.onStep(tableName, TaskStatus.COMPLETED)
                successCount++
            } catch (e: Exception) {
                rowCounts[tableName] = 0L
                errors[tableName] = e.message ?: "未知错误"
                failureCount++
                reporter.onStep(tableName, TaskStatus.FAILED, e.message)
                reporter.onLog("ERROR", "[$tableName] 迁移失败：${e.message}")
            }
        }

        val verification = verify(targetConnection, config.targetDatabase, rowCounts, errors, reporter)
        if (!reporter.isCancelled()) reporter.onProgress(100, "迁移完成")
        return TaskResult(
            success = failureCount == 0 && !reporter.isCancelled(),
            successCount = successCount,
            failureCount = failureCount,
            skippedCount = skipped.size,
            errorMessage = if (failureCount > 0) "部分表迁移失败" else null,
            verification = verification
        )
    }

    private fun sourceObjects(session: DatabaseSession, config: MigrationConfig): List<TableInfo> {
        val selected = config.tables.takeIf { it.isNotEmpty() }?.toSet()
        return sourceMetadata.listTables(session, config.sourceDatabase)
            .filter { selected == null || it.name in selected }
    }

    private fun targetTables(session: DatabaseSession, database: String): List<TableInfo> = runCatching {
        targetMetadata.listTables(session, database).filter { it.type.equals("table", ignoreCase = true) }
    }.getOrDefault(emptyList())

    private fun findExactTargetName(tables: List<TableInfo>, sourceName: String): String? =
        tables.firstOrNull { it.name == sourceName }?.name

    private fun switchTargetDatabase(connection: Connection, database: String) {
        targetDialect.buildSwitchDatabaseSql(database)?.takeIf { it.isNotBlank() }?.let { executeSql(connection, it) }
    }

    private fun migrateStructure(connection: Connection, database: String, tableName: String, definition: TableDefinition) {
        executeSql(connection, "DROP TABLE IF EXISTS ${target.qualified(database, tableName)}")
        target.createStatements(database, tableName, definition).forEach { executeSql(connection, it) }
    }

    private fun createIndexes(connection: Connection, database: String, tableName: String, indexes: List<IndexInfo>) {
        indexes.filterNot { it.isPrimary }.filter { it.columns.isNotEmpty() }.forEach { index ->
            executeSql(connection, target.createIndexSql(database, tableName, index))
        }
    }

    private fun migrateData(
        sourceConnection: Connection,
        targetConnection: Connection,
        config: MigrationConfig,
        sourceTable: String,
        targetTable: String,
        reporter: TaskReporter
    ): Long {
        val sourceQualified = "${sourceDialect.quoteIdentifier(config.sourceDatabase)}.${sourceDialect.quoteIdentifier(sourceTable)}"
        val targetQualified = target.qualified(config.targetDatabase, targetTable)
        val columns = mutableListOf<String>()
        val jdbcTypes = mutableListOf<Int>()
        var hasAutoIncrement = false
        sourceConnection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $sourceQualified WHERE 1 = 0").use { result ->
                for (index in 1..result.metaData.columnCount) {
                    columns += result.metaData.getColumnName(index)
                    jdbcTypes += result.metaData.getColumnType(index)
                    hasAutoIncrement = hasAutoIncrement || runCatching {
                        result.metaData.isAutoIncrement(index)
                    }.getOrDefault(false)
                }
            }
        }
        if (columns.isEmpty()) return 0

        val insertSql = "INSERT INTO $targetQualified (${columns.joinToString(", ") { targetDialect.quoteIdentifier(it) }}) VALUES (${columns.joinToString(", ") { "?" }})"
        val previousAutoCommit = targetConnection.autoCommit
        var migrated = 0L
        val identityInsertEnabled = target == Target.DAMENG && hasAutoIncrement && runCatching {
            executeSql(targetConnection, "SET IDENTITY_INSERT ${targetDialect.quoteIdentifier(targetTable)} ON")
            true
        }.getOrDefault(false)
        targetConnection.autoCommit = false
        try {
            targetConnection.prepareStatement(insertSql).use { insert ->
                sourceConnection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { select ->
                    select.fetchSize = BATCH_SIZE
                    select.executeQuery("SELECT * FROM $sourceQualified").use { rows ->
                        var batchCount = 0
                        while (rows.next() && !reporter.isCancelled()) {
                            jdbcTypes.forEachIndexed { offset, jdbcType ->
                                val index = offset + 1
                                when (jdbcType) {
                                    Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> insert.setString(index, rows.getString(index))
                                    Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> insert.setBytes(index, rows.getBytes(index))
                                    Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE, Types.OTHER -> if (target == Target.MYSQL) {
                                        insert.setString(index, rows.getString(index))
                                    } else {
                                        insert.setObject(index, rows.getObject(index))
                                    }
                                    else -> insert.setObject(index, rows.getObject(index))
                                }
                            }
                            insert.addBatch()
                            batchCount++
                            migrated++
                            if (batchCount == BATCH_SIZE) {
                                insert.executeBatch()
                                targetConnection.commit()
                                batchCount = 0
                            }
                        }
                        if (batchCount > 0) {
                            insert.executeBatch()
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
                runCatching { executeSql(targetConnection, "SET IDENTITY_INSERT ${targetDialect.quoteIdentifier(targetTable)} OFF") }
                    .onFailure { reporter.onLog("WARN", "[$targetTable] 关闭达梦 IDENTITY_INSERT 失败：${it.message}") }
            }
        }
        return migrated
    }

    private fun enrichAutoIncrement(
        sourceConnection: Connection,
        schema: String,
        table: String,
        definition: TableDefinition
    ): TableDefinition {
        val autoIncrementNames = linkedSetOf<String>()
        val qualified = "${sourceDialect.quoteIdentifier(schema)}.${sourceDialect.quoteIdentifier(table)}"
        sourceConnection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM $qualified WHERE 1 = 0").use { result ->
                for (index in 1..result.metaData.columnCount) {
                    if (runCatching { result.metaData.isAutoIncrement(index) }.getOrDefault(false)) {
                        autoIncrementNames += result.metaData.getColumnName(index)
                    }
                }
            }
        }
        if (autoIncrementNames.isEmpty()) return definition
        return definition.copy(columns = definition.columns.map { column ->
            if (column.name in autoIncrementNames) column.copy(isAutoIncrement = true) else column
        })
    }

    private fun verify(
        connection: Connection,
        database: String,
        rowCounts: Map<String, Long>,
        errors: Map<String, String>,
        reporter: TaskReporter
    ): List<TableVerifyResult> = rowCounts.map { (table, sourceRows) ->
        val error = errors[table]
        val targetRows = if (error != null) 0L else runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM ${target.qualified(database, table)}").use { result ->
                    if (result.next()) result.getLong(1) else 0L
                }
            }
        }.getOrElse {
            reporter.onLog("WARN", "[$table] 行数验证失败：${it.message}")
            -1L
        }
        TableVerifyResult(
            tableName = table,
            sourceRows = sourceRows,
            targetRows = targetRows.coerceAtLeast(0),
            status = when {
                error != null || targetRows < 0 -> "failed"
                sourceRows == targetRows -> "match"
                else -> "mismatch"
            },
            errorMessage = error ?: if (targetRows < 0) "验证查询失败" else null
        )
    }

    private fun executeSql(connection: Connection, sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    internal enum class Target(
        val label: String,
        val metadata: MetadataAdapter,
        val dialect: DialectAdapter
    ) {
        MYSQL("MySQL", MysqlMetadataAdapter(), MysqlDialectAdapter()),
        DAMENG("达梦", DamengMetadataAdapter(), DamengDialectAdapter());

        fun qualified(database: String, table: String): String =
            "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"

        fun createStatements(database: String, table: String, source: TableDefinition): List<String> {
            require(source.columns.isNotEmpty()) { "源表 $table 没有可迁移字段" }
            val targetKind = this
            val columnLines = source.columns.map { column ->
                buildString {
                    append("  ${dialect.quoteIdentifier(column.name)} ${mapType(column.type)}")
                    if (!column.nullable || column.isPrimaryKey || column.isAutoIncrement) append(" NOT NULL")
                    if (!column.isAutoIncrement) {
                        normalizeDefault(column.defaultValue)?.let { append(" DEFAULT $it") }
                    }
                    if (column.isAutoIncrement) append(" AUTO_INCREMENT")
                    if (targetKind == MYSQL && !column.comment.isNullOrBlank()) append(" COMMENT ${dialect.escapeValue(column.comment)}")
                }
            }.toMutableList()
            source.columns.filter { it.isPrimaryKey }.takeIf { it.isNotEmpty() }?.let { primary ->
                columnLines += "  PRIMARY KEY (${primary.joinToString(", ") { dialect.quoteIdentifier(it.name) }})"
            }
            val qualified = qualified(database, table)
            return if (this == MYSQL) {
                listOf(buildString {
                    append("CREATE TABLE $qualified (\n${columnLines.joinToString(",\n")}\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
                    source.table.comment?.takeIf { it.isNotBlank() }?.let { append(" COMMENT=${dialect.escapeValue(it)}") }
                })
            } else {
                buildList {
                    add("CREATE TABLE $qualified (\n${columnLines.joinToString(",\n")}\n)")
                    source.table.comment?.takeIf { it.isNotBlank() }?.let {
                        add("COMMENT ON TABLE $qualified IS ${dialect.escapeValue(it)}")
                    }
                    source.columns.filter { !it.comment.isNullOrBlank() }.forEach {
                        add("COMMENT ON COLUMN $qualified.${dialect.quoteIdentifier(it.name)} IS ${dialect.escapeValue(it.comment)}")
                    }
                }
            }
        }

        fun createIndexSql(database: String, table: String, index: IndexInfo): String {
            val unique = if (index.isUnique) "UNIQUE " else ""
            return "CREATE ${unique}INDEX ${dialect.quoteIdentifier(index.name)} ON ${qualified(database, table)} (${index.columns.joinToString(", ") { dialect.quoteIdentifier(it) }})"
        }

        fun mapType(rawType: String): String = when (this) {
            MYSQL -> mapDamengTypeToMysql(rawType)
            DAMENG -> preserveDamengType(rawType)
        }

        fun normalizeDefault(value: String?): String? {
            val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (raw.equals("NULL", ignoreCase = true)) return null
            // DM -> DM is same-dialect migration. The catalog expression is the
            // source of truth, including precision-bearing functions such as
            // CURRENT_TIMESTAMP(6); do not reinterpret or quote it.
            if (this == DAMENG) return raw
            val upper = raw.uppercase()
            if (upper in setOf("CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME")) return upper
            if (Regex("""CURRENT_TIMESTAMP\(\d+\)""").matches(upper)) return upper
            if (upper == "SYSDATE") return if (this == MYSQL) "CURRENT_TIMESTAMP" else "SYSDATE"
            if (Regex("""[-+]?\d+(\.\d+)?""").matches(raw)) return raw
            if (raw.startsWith("'") && raw.endsWith("'")) return raw
            if (raw.contains("(")) return null
            return "'${raw.replace("'", "''")}'"
        }
    }

    companion object {
        private const val BATCH_SIZE = 1000
        internal const val MODE_STRUCTURE_AND_DATA = "structure_and_data"
        internal const val MODE_STRUCTURE_ONLY = "structure_only"
        internal const val MODE_DATA_ONLY = "data_only"

        fun toMysql(): MigrationAdapter = DamengSourceMigrationAdapter(Target.MYSQL)
        fun toDameng(): MigrationAdapter = DamengSourceMigrationAdapter(Target.DAMENG)

        internal fun validateMode(mode: String) {
            require(mode in setOf(MODE_STRUCTURE_AND_DATA, MODE_STRUCTURE_ONLY, MODE_DATA_ONLY)) {
                "不支持的迁移模式：$mode"
            }
        }

        internal fun mapDamengTypeToMysql(rawType: String): String {
            val clean = rawType.trim().replace(Regex("\\s+"), " ")
            val base = clean.substringBefore("(").trim().uppercase()
            val args = Regex("""\((.*)\)""").find(clean)?.groupValues?.get(1)?.trim()
            val length = args?.substringBefore(",")?.trim()?.toLongOrNull()
            return when (base) {
                "CHAR", "NCHAR" -> "CHAR(${(length ?: 1).coerceAtMost(255)})"
                "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHARACTER VARYING" ->
                    if (length != null && length <= 16383) "VARCHAR($length)" else "LONGTEXT"
                "TEXT", "CLOB", "NCLOB", "LONG", "LONGVARCHAR" -> "LONGTEXT"
                "BINARY" -> "BINARY(${(length ?: 1).coerceAtMost(255)})"
                "VARBINARY" -> if (length != null && length <= 65535) "VARBINARY($length)" else "LONGBLOB"
                "BLOB", "IMAGE", "LONGVARBINARY" -> "LONGBLOB"
                "BIT" -> "BIT(${length ?: 1})"
                "BOOL", "BOOLEAN" -> "BOOLEAN"
                "BYTE", "TINYINT" -> "TINYINT"
                "SMALLINT" -> "SMALLINT"
                "INT", "INTEGER", "PLS_INTEGER" -> "INT"
                "BIGINT" -> "BIGINT"
                "NUMBER", "NUMERIC", "DECIMAL", "DEC" -> args?.let { "DECIMAL($it)" } ?: "DECIMAL(65,27)"
                "FLOAT", "REAL" -> "FLOAT"
                "DOUBLE", "DOUBLE PRECISION" -> "DOUBLE"
                "DATE" -> "DATE"
                "TIME" -> "TIME"
                "DATETIME", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE" -> "DATETIME"
                "INTERVAL YEAR", "INTERVAL DAY", "BFILE", "ROWID" -> "VARCHAR(255)"
                else -> "LONGTEXT"
            }
        }

        internal fun preserveDamengType(rawType: String): String = rawType.trim().ifBlank { "VARCHAR(255)" }
    }
}
