package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesDataWriteAdapter
import com.easydb.common.TimeSeriesLifecycleField
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagValueDraft
import com.easydb.common.TimeSeriesWriteCell
import com.easydb.common.TimeSeriesWriteLimits
import com.easydb.common.TimeSeriesWriteParameter
import com.easydb.common.TimeSeriesWritePlan
import com.easydb.common.TimeSeriesWriteRequest
import com.easydb.common.TimeSeriesWriteSnapshot
import com.easydb.common.TimeSeriesWriteTargetKind
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.sql.Timestamp

class TdengineTimeSeriesDataWriteAdapter(
    private val metadata: MetadataAdapter,
    private val timeSeriesMetadata: TimeSeriesMetadataAdapter,
    private val dialect: TdengineDialectAdapter = TdengineDialectAdapter(),
    private val valueRenderer: TdengineTimeSeriesObjectAdapter = TdengineTimeSeriesObjectAdapter(timeSeriesMetadata, dialect)
) : TimeSeriesDataWriteAdapter {

    override fun inspectWriteTarget(session: DatabaseSession, database: String, request: TimeSeriesWriteRequest): TimeSeriesWriteSnapshot {
        validateIdentifier(database, "数据库名", 64)
        validateIdentifier(request.table, "目标表名", 192)
        val (stableName, columns, tags) = when (request.targetKind) {
            TimeSeriesWriteTargetKind.BASIC_TABLE -> {
                require(request.stableName == null && request.tagValues.isEmpty()) { "普通表写入不能包含超级表或 Tags" }
                require(metadata.getTableInfo(session, database, request.table).tableKind == TableKind.BASIC_TABLE) { "目标不是普通表" }
                Triple(null, fields(session, database, request.table), emptyList())
            }
            TimeSeriesWriteTargetKind.EXISTING_CHILD_TABLE -> {
                require(request.stableName == null && request.tagValues.isEmpty()) { "已有子表写入不接受超级表或 Tags" }
                require(metadata.getTableInfo(session, database, request.table).tableKind == TableKind.CHILD_TABLE) { "目标不是子表" }
                val stable = timeSeriesMetadata.inspectChildTable(session, database, request.table).stableName
                Triple(stable, fields(session, database, request.table), emptyList())
            }
            TimeSeriesWriteTargetKind.NEW_CHILD_TABLE -> {
                val stable = requireNotNull(request.stableName) { "新建子表写入必须提供 stableName" }
                validateIdentifier(stable, "超级表名", 192)
                require(metadata.getTableInfo(session, database, stable).tableKind == TableKind.SUPER_TABLE) { "父对象不是超级表" }
                try {
                    metadata.getTableInfo(session, database, request.table)
                    throw IllegalArgumentException("目标子表已存在，请改用已有子表写入")
                } catch (error: IllegalArgumentException) {
                    if (error.message?.startsWith("目标子表已存在") == true) throw error
                }
                val tagFields = timeSeriesMetadata.listTagDefinitions(session, database, stable).map(::field)
                Triple(stable, fields(session, database, stable), tagFields)
            }
        }
        return TimeSeriesWriteSnapshot(
            database, request.targetKind, request.table, stableName, columns, tags,
            fingerprint(database, request.targetKind, request.table, stableName, columns, tags)
        )
    }

    override fun buildWritePlan(database: String, snapshot: TimeSeriesWriteSnapshot, request: TimeSeriesWriteRequest): TimeSeriesWritePlan {
        return buildPlan(database, snapshot, request, TimeSeriesWriteLimits.MAX_ROWS, includeChildCreation = true)
    }

    internal fun buildCsvWritePlan(database: String, snapshot: TimeSeriesWriteSnapshot, request: TimeSeriesWriteRequest): TimeSeriesWritePlan =
        buildPlan(database, snapshot, request, com.easydb.common.TimeSeriesCsvImportLimits.MAX_BATCH_ROWS, includeChildCreation = false)

    internal fun prepareCsvTarget(session: DatabaseSession, database: String, snapshot: TimeSeriesWriteSnapshot, request: TimeSeriesWriteRequest) {
        if (request.targetKind != TimeSeriesWriteTargetKind.NEW_CHILD_TABLE) return
        val values = request.tagValues.associateByUniqueName("Tag")
        require(values.keys == snapshot.tags.map { it.name }.toSet()) { "Tags 必须与超级表定义完全一致" }
        val tagsSql = snapshot.tags.joinToString(", ") { tag ->
            valueRenderer.renderTagLiteral(TimeSeriesTagDefinition(tag.name, renderType(tag)), requireNotNull(values[tag.name]))
        }
        val sql = "CREATE TABLE ${qualified(database, request.table)} USING ${qualified(database, requireNotNull(snapshot.stableName))} TAGS ($tagsSql)"
        session.getJdbcConnection().createStatement().use { it.execute(sql) }
    }

    private fun buildPlan(
        database: String,
        snapshot: TimeSeriesWriteSnapshot,
        request: TimeSeriesWriteRequest,
        maxRows: Int,
        includeChildCreation: Boolean
    ): TimeSeriesWritePlan {
        require(snapshot.database == database && snapshot.targetKind == request.targetKind && snapshot.table == request.table) { "写入快照与目标不匹配" }
        require(request.rows.size in 1..maxRows) { "单次必须写入 1 到 $maxRows 行" }
        require(request.columns.isNotEmpty() && request.columns.size <= TimeSeriesWriteLimits.MAX_COLUMNS) { "写入列清单无效" }
        require(request.columns.distinct().size == request.columns.size) { "写入列不能重复" }
        val definitions = snapshot.columns.associateBy { it.name }
        request.columns.forEach { require(definitions.containsKey(it)) { "字段不存在：$it" } }
        val primary = snapshot.columns.firstOrNull { it.primaryTimestamp } ?: throw IllegalArgumentException("目标缺少主时间戳列")
        require(primary.name in request.columns) { "必须显式填写主时间戳列 ${primary.name}" }

        val parameters = mutableListOf<TimeSeriesWriteParameter>()
        val previewRows = request.rows.mapIndexed { rowIndex, row ->
            val cells = row.cells.associateByUniqueName(rowIndex)
            require(cells.keys == request.columns.toSet()) { "第 ${rowIndex + 1} 行必须与列清单完全一致" }
            request.columns.map { name ->
                val cell = requireNotNull(cells[name])
                require(!(name == primary.name && (cell.isNull || cell.value == null))) { "第 ${rowIndex + 1} 行主时间戳不能为空" }
                val type = requireNotNull(definitions[name])
                val literal = renderCell(type, cell, rowIndex)
                parameters += TimeSeriesWriteParameter(type.type, if (cell.isNull) null else cell.value)
                literal
            }
        }

        val tagsSql = if (request.targetKind == TimeSeriesWriteTargetKind.NEW_CHILD_TABLE) {
            val values = request.tagValues.associateByUniqueName("Tag")
            require(values.keys == snapshot.tags.map { it.name }.toSet()) { "Tags 必须与超级表定义完全一致" }
            snapshot.tags.joinToString(", ") { tag ->
                valueRenderer.renderTagLiteral(TimeSeriesTagDefinition(tag.name, renderType(tag)), requireNotNull(values[tag.name]))
            }
        } else null
        val target = qualified(database, request.table)
        val prefix = if (tagsSql == null || !includeChildCreation) {
            "INSERT INTO $target"
        } else {
            "INSERT INTO $target USING ${qualified(database, requireNotNull(snapshot.stableName))} TAGS ($tagsSql)"
        }
        val columnSql = request.columns.joinToString(", ") { dialect.quoteIdentifier(it) }
        val placeholders = request.rows.joinToString(", ") { "(${request.columns.joinToString(", ") { "?" }})" }
        val previewValues = previewRows.joinToString(", ") { row -> "(${row.joinToString(", ")})" }
        return TimeSeriesWritePlan(
            sql = "$prefix ($columnSql) VALUES $placeholders",
            previewSql = "$prefix ($columnSql) VALUES $previewValues",
            parameters = parameters,
            rowCount = request.rows.size,
            createsChildTable = request.targetKind == TimeSeriesWriteTargetKind.NEW_CHILD_TABLE && includeChildCreation
        )
    }

    override fun executeWritePlan(session: DatabaseSession, plan: TimeSeriesWritePlan) {
        session.getJdbcConnection().prepareStatement(plan.sql).use { statement ->
            plan.parameters.forEachIndexed { index, parameter ->
                val position = index + 1
                val value = parameter.value
                if (value == null) {
                    statement.setObject(position, null)
                } else if (parameter.type.equals("TIMESTAMP", ignoreCase = true)) {
                    val normalized = value.trim()
                    val epoch = normalized.toLongOrNull()
                    if (epoch != null) {
                        statement.setLong(position, epoch)
                    } else {
                        statement.setTimestamp(position, Timestamp.valueOf(normalized.replace('T', ' ')))
                    }
                } else {
                    statement.setObject(position, boundValue(parameter.type, value))
                }
            }
            statement.executeUpdate()
        }
    }

    private fun fields(session: DatabaseSession, database: String, table: String) =
        metadata.getColumns(session, database, table).map { column ->
            val parsed = parseType(column.type)
            TimeSeriesLifecycleField(column.name, parsed.first, parsed.second, column.isPrimaryKey)
        }

    private fun field(tag: TimeSeriesTagDefinition): TimeSeriesLifecycleField {
        val parsed = parseType(tag.type)
        return TimeSeriesLifecycleField(tag.name, parsed.first, parsed.second)
    }

    private fun renderCell(type: TimeSeriesLifecycleField, cell: TimeSeriesWriteCell, rowIndex: Int): String {
        require(!(cell.isNull && cell.value != null)) { "第 ${rowIndex + 1} 行字段 ${cell.name} 的 NULL 状态冲突" }
        if (cell.isNull) return "NULL"
        val value = cell.value ?: throw IllegalArgumentException("第 ${rowIndex + 1} 行字段 ${cell.name} 未填写值或 NULL")
        require(value.length <= TimeSeriesWriteLimits.MAX_CELL_CHARS) { "字段 ${cell.name} 输入过长" }
        return valueRenderer.renderTagLiteral(
            TimeSeriesTagDefinition(type.name, renderType(type)),
            TimeSeriesTagValueDraft(type.name, value, false)
        )
    }

    private fun boundValue(type: String, value: String): Any = when (type.uppercase()) {
        "BOOL" -> value.trim().lowercase().let { if (it == "true") true else false }
        "TINYINT", "SMALLINT", "INT", "BIGINT" -> BigInteger(value.trim()).let { if (it.bitLength() < 63) it.toLong() else BigDecimal(it) }
        "TINYINT UNSIGNED", "SMALLINT UNSIGNED", "INT UNSIGNED", "BIGINT UNSIGNED" -> BigDecimal(value.trim())
        "FLOAT", "DOUBLE" -> BigDecimal(value.trim())
        else -> value
    }

    private fun List<TimeSeriesWriteCell>.associateByUniqueName(rowIndex: Int): Map<String, TimeSeriesWriteCell> {
        val result = linkedMapOf<String, TimeSeriesWriteCell>()
        forEach { require(result.put(it.name, it) == null) { "第 ${rowIndex + 1} 行字段重复：${it.name}" } }
        return result
    }

    private fun List<TimeSeriesTagValueDraft>.associateByUniqueName(label: String): Map<String, TimeSeriesTagValueDraft> {
        val result = linkedMapOf<String, TimeSeriesTagValueDraft>()
        forEach { require(result.put(it.name, it) == null) { "$label 重复：${it.name}" } }
        return result
    }

    private fun renderType(field: TimeSeriesLifecycleField) = if (field.length == null) field.type else "${field.type}(${field.length})"
    private fun parseType(raw: String): Pair<String, Int?> {
        val match = TYPE_PATTERN.matchEntire(raw.trim().uppercase().replace(WHITESPACE, " "))
            ?: return raw.trim().uppercase() to null
        return match.groupValues[1] to match.groupValues[2].takeIf(String::isNotEmpty)?.toInt()
    }
    private fun qualified(database: String, table: String) = "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"
    private fun validateIdentifier(value: String, label: String, max: Int) {
        require(value.isNotBlank() && value == value.trim()) { "${label}不能为空或包含首尾空白" }
        require('.' !in value && value.none { it.code < 32 || it.code == 127 }) { "${label}包含非法字符" }
        require(value.toByteArray().size <= max) { "${label}不能超过 $max 字节" }
    }
    private fun fingerprint(database: String, kind: TimeSeriesWriteTargetKind, table: String, stable: String?, columns: List<TimeSeriesLifecycleField>, tags: List<TimeSeriesLifecycleField>): String {
        val text = buildString {
            append(database).append('|').append(kind).append('|').append(table).append('|').append(stable)
            (columns + tags).forEach { append('|').append(it.name).append(':').append(it.type).append(':').append(it.length).append(':').append(it.primaryTimestamp) }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val TYPE_PATTERN = Regex("^([A-Z]+(?: UNSIGNED)?)(?:\\((\\d+)\\))?$")
    }
}
