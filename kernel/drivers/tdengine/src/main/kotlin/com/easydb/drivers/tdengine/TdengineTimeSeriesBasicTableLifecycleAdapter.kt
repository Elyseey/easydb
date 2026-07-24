package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesBasicTableCommand
import com.easydb.common.TimeSeriesBasicTableLifecycleAdapter
import com.easydb.common.TimeSeriesBasicTableOperation
import com.easydb.common.TimeSeriesBasicTableSnapshot
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesLifecycleField
import java.security.MessageDigest

class TdengineTimeSeriesBasicTableLifecycleAdapter(
    private val metadata: MetadataAdapter,
    private val dialect: TdengineDialectAdapter = TdengineDialectAdapter()
) : TimeSeriesBasicTableLifecycleAdapter {

    override fun inspectBasicTable(
        session: DatabaseSession,
        database: String,
        table: String
    ): TimeSeriesBasicTableSnapshot {
        validateIdentifier(database, "数据库名", 64)
        validateIdentifier(table, "普通表名", 192)
        require(metadata.getTableInfo(session, database, table).tableKind == TableKind.BASIC_TABLE) {
            "对象不是普通表：$database.$table"
        }
        val columns = metadata.getColumns(session, database, table).map { column ->
            val parsed = parseType(column.type)
            TimeSeriesLifecycleField(
                name = column.name,
                type = parsed.first,
                length = parsed.second,
                primaryTimestamp = column.isPrimaryKey
            )
        }
        require(columns.isNotEmpty() && columns.first().primaryTimestamp) { "普通表缺少主时间戳列" }
        return TimeSeriesBasicTableSnapshot(
            database = database,
            table = table,
            columns = columns,
            fingerprint = fingerprint(database, table, columns)
        )
    }

    override fun buildBasicTableMutationSql(
        database: String,
        table: String,
        snapshot: TimeSeriesBasicTableSnapshot,
        command: TimeSeriesBasicTableCommand
    ): String {
        require(snapshot.database == database && snapshot.table == table) { "结构快照与目标普通表不匹配" }
        validateIdentifier(command.name, "字段名", 64)
        val current = snapshot.columns.firstOrNull { it.name == command.name }
        val qualified = "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"
        val clause = when (command.operation) {
            TimeSeriesBasicTableOperation.ADD_COLUMN -> {
                require(command.type != null && command.newName == null) { "ADD_COLUMN 必须只提供类型和可选长度" }
                require(current == null) { "字段已存在：${command.name}" }
                val requestedType = requireNotNull(command.type)
                require(requestedType != TimeSeriesDataType.TIMESTAMP) { "不能新增第二个 TIMESTAMP 字段；主时间戳必须保持唯一" }
                validateType(requestedType, command.length)
                validateRowWidth(snapshot.columns + TimeSeriesLifecycleField(command.name, requestedType.sql, command.length))
                "ADD COLUMN ${dialect.quoteIdentifier(command.name)} ${renderType(requestedType, command.length)}"
            }
            TimeSeriesBasicTableOperation.DROP_COLUMN -> {
                require(command.type == null && command.length == null && command.newName == null) { "DROP_COLUMN 只能提供字段名" }
                val field = requireNotNull(current) { "字段不存在：${command.name}" }
                require(!field.primaryTimestamp) { "不能删除主时间戳列：${command.name}" }
                require(snapshot.columns.size > 2) { "普通表至少需要保留主时间戳列和一个普通字段" }
                "DROP COLUMN ${dialect.quoteIdentifier(command.name)}"
            }
            TimeSeriesBasicTableOperation.MODIFY_COLUMN -> {
                require(command.type != null && command.length != null && command.newName == null) { "MODIFY_COLUMN 必须只提供类型和长度" }
                val field = requireNotNull(current) { "字段不存在：${command.name}" }
                require(!field.primaryTimestamp) { "不能修改主时间戳列：${command.name}" }
                val requestedType = requireNotNull(command.type)
                val requestedLength = requireNotNull(command.length)
                require(requestedType in VARIABLE_TYPES) { "仅支持扩展 BINARY、VARCHAR 或 NCHAR 长度" }
                require(canonical(field.type) == canonical(requestedType.sql)) { "不能修改字段类型：${field.type} -> ${requestedType.sql}" }
                validateType(requestedType, requestedLength)
                require(requestedLength > requireNotNull(field.length)) { "新长度必须大于当前长度 ${field.length}" }
                validateRowWidth(snapshot.columns.map { if (it.name == field.name) it.copy(type = requestedType.sql, length = requestedLength) else it })
                "MODIFY COLUMN ${dialect.quoteIdentifier(command.name)} ${renderType(requestedType, requestedLength)}"
            }
            TimeSeriesBasicTableOperation.RENAME_COLUMN -> {
                require(command.type == null && command.length == null && command.newName != null) { "RENAME_COLUMN 只能提供原字段名和新字段名" }
                val field = requireNotNull(current) { "字段不存在：${command.name}" }
                require(!field.primaryTimestamp) { "不能重命名主时间戳列：${command.name}" }
                val newName = requireNotNull(command.newName)
                validateIdentifier(newName, "新字段名", 64)
                require(newName != command.name) { "新字段名必须与原名称不同" }
                require(snapshot.columns.none { it.name == newName }) { "字段已存在：$newName" }
                "RENAME COLUMN ${dialect.quoteIdentifier(command.name)} ${dialect.quoteIdentifier(newName)}"
            }
        }
        return "ALTER TABLE $qualified $clause"
    }

    private fun validateType(type: TimeSeriesDataType, length: Int?) {
        if (type.requiresLength) {
            val max = if (type == TimeSeriesDataType.NCHAR) 16_379 else 65_517
            require(length != null && length in 1..max) { "${type.sql} 长度必须在 1 到 $max 之间" }
        } else {
            require(length == null) { "${type.sql} 类型不能设置长度" }
        }
    }

    private fun renderType(type: TimeSeriesDataType, length: Int?) =
        if (length == null) type.sql else "${type.sql}($length)"

    /** TDengine 3.0.4 的行宽上限是 48 KB；Phase 7 采用跨支持版本的保守门禁。 */
    private fun validateRowWidth(columns: List<TimeSeriesLifecycleField>) {
        val widths = columns.map { storageBytes(it.type, it.length) }
        if (widths.any { it == null }) return
        require(widths.filterNotNull().sum() <= MAX_ROW_BYTES) { "普通表估算行宽不能超过 48 KB（兼容 TDengine 3.0.4）" }
    }

    private fun storageBytes(type: String, length: Int?): Int? = when (type.uppercase()) {
        "TIMESTAMP", "BIGINT", "BIGINT UNSIGNED", "DOUBLE" -> 8
        "INT", "INT UNSIGNED", "FLOAT" -> 4
        "SMALLINT", "SMALLINT UNSIGNED" -> 2
        "TINYINT", "TINYINT UNSIGNED", "BOOL" -> 1
        "BINARY", "VARCHAR" -> length?.plus(2)
        "NCHAR" -> length?.times(4)?.plus(2)
        else -> null
    }

    private fun parseType(raw: String): Pair<String, Int?> {
        val match = TYPE_PATTERN.matchEntire(raw.trim().uppercase().replace(WHITESPACE, " "))
            ?: return raw.trim().uppercase() to null
        return match.groupValues[1] to match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
    }

    private fun fingerprint(database: String, table: String, columns: List<TimeSeriesLifecycleField>): String {
        val canonical = buildString {
            append(database).append('\u0000').append(table)
            columns.forEach { append('\u0000').append(it.name).append(':').append(it.type).append(':').append(it.length).append(':').append(it.primaryTimestamp) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun validateIdentifier(value: String, label: String, maxBytes: Int) {
        require(value.isNotBlank() && value == value.trim()) { "${label}不能为空或包含首尾空白" }
        require('.' !in value && value.none { it.code < 32 || it.code == 127 }) { "${label}包含非法字符" }
        require(value.toByteArray().size <= maxBytes) { "${label}不能超过 $maxBytes 字节" }
    }

    private fun canonical(type: String) = if (type.uppercase() in setOf("BINARY", "VARCHAR")) "BINARY" else type.uppercase()

    companion object {
        private const val MAX_ROW_BYTES = 48 * 1024
        private val VARIABLE_TYPES = setOf(TimeSeriesDataType.BINARY, TimeSeriesDataType.VARCHAR, TimeSeriesDataType.NCHAR)
        private val WHITESPACE = Regex("\\s+")
        private val TYPE_PATTERN = Regex("^([A-Z]+(?: UNSIGNED)?)(?:\\((\\d+)\\))?$")
    }
}
