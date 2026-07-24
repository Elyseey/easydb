package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesChildPropertyCommand
import com.easydb.common.TimeSeriesChildPropertyOperation
import com.easydb.common.TimeSeriesChildPropertySnapshot
import com.easydb.common.TimeSeriesDeleteObjectKind
import com.easydb.common.TimeSeriesDeleteSnapshot
import com.easydb.common.TimeSeriesLifecycleAdapter
import com.easydb.common.TimeSeriesLifecycleCommand
import com.easydb.common.TimeSeriesLifecycleField
import com.easydb.common.TimeSeriesLifecycleOperation
import com.easydb.common.TimeSeriesLifecycleSnapshot
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagValueDraft
import java.security.MessageDigest

class TdengineTimeSeriesLifecycleAdapter(
    private val metadata: MetadataAdapter,
    private val timeSeriesMetadata: TimeSeriesMetadataAdapter,
    private val dialect: TdengineDialectAdapter = TdengineDialectAdapter(),
    private val tagValueRenderer: TdengineTimeSeriesObjectAdapter =
        TdengineTimeSeriesObjectAdapter(timeSeriesMetadata, dialect)
) : TimeSeriesLifecycleAdapter {

    override fun inspectDelete(
        session: DatabaseSession,
        database: String,
        name: String
    ): TimeSeriesDeleteSnapshot {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(name, "对象名", MAX_TABLE_NAME_BYTES)
        val table = metadata.getTableInfo(session, database, name)
        val stableName = if (table.tableKind == TableKind.CHILD_TABLE) {
            timeSeriesMetadata.inspectChildTable(session, database, name).stableName
        } else {
            null
        }
        val kind = when (table.tableKind) {
            TableKind.BASIC_TABLE -> TimeSeriesDeleteObjectKind.BASIC_TABLE
            TableKind.CHILD_TABLE -> TimeSeriesDeleteObjectKind.CHILD_TABLE
            TableKind.SUPER_TABLE -> TimeSeriesDeleteObjectKind.SUPER_TABLE
            else -> throw IllegalArgumentException("不支持删除该 TDengine 对象类型：${table.tableKind}")
        }
        val childCount = if (kind == TimeSeriesDeleteObjectKind.SUPER_TABLE) {
            timeSeriesMetadata.countChildTables(session, database, name)
        } else {
            0
        }
        return TimeSeriesDeleteSnapshot(
            database = database,
            name = name,
            kind = kind,
            stableName = stableName,
            createdAt = table.updateTime,
            affectedChildTables = childCount,
            fingerprint = deleteFingerprint(database, name, kind, stableName, table.updateTime, childCount)
        )
    }

    override fun buildDeleteSql(
        database: String,
        name: String,
        snapshot: TimeSeriesDeleteSnapshot
    ): String {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(name, "对象名", MAX_TABLE_NAME_BYTES)
        require(snapshot.database == database && snapshot.name == name) { "删除快照与目标对象不匹配" }
        val keyword = if (snapshot.kind == TimeSeriesDeleteObjectKind.SUPER_TABLE) "STABLE" else "TABLE"
        return "DROP $keyword ${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(name)}"
    }

    override fun inspect(
        session: DatabaseSession,
        database: String,
        stable: String
    ): TimeSeriesLifecycleSnapshot {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(stable, "超级表名", MAX_TABLE_NAME_BYTES)
        val table = metadata.getTableInfo(session, database, stable)
        require(table.tableKind == TableKind.SUPER_TABLE) { "对象不是超级表：$database.$stable" }

        val columns = metadata.getColumns(session, database, stable).map { column ->
            val parsed = parseCatalogType(column.type)
            TimeSeriesLifecycleField(
                name = column.name,
                type = parsed.type,
                length = parsed.length,
                primaryTimestamp = column.isPrimaryKey
            )
        }
        require(columns.isNotEmpty()) { "超级表没有可见字段：$database.$stable" }
        val tags = timeSeriesMetadata.listTagDefinitions(session, database, stable).map { tag ->
            val parsed = parseCatalogType(tag.type)
            TimeSeriesLifecycleField(name = tag.name, type = parsed.type, length = parsed.length)
        }
        require(tags.isNotEmpty()) { "超级表没有可见 Tag：$database.$stable" }

        val affectedChildTables = timeSeriesMetadata.countChildTables(session, database, stable)
        val fingerprint = structureFingerprint(database, stable, columns, tags, affectedChildTables)
        return TimeSeriesLifecycleSnapshot(
            database = database,
            stable = stable,
            columns = columns,
            tags = tags,
            affectedChildTables = affectedChildTables,
            fingerprint = fingerprint
        )
    }

    override fun buildMutationSql(
        database: String,
        stable: String,
        snapshot: TimeSeriesLifecycleSnapshot,
        command: TimeSeriesLifecycleCommand
    ): String {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(stable, "超级表名", MAX_TABLE_NAME_BYTES)
        require(snapshot.database == database && snapshot.stable == stable) { "结构快照与目标超级表不匹配" }
        validateIdentifier(command.name, command.operation.targetLabel(), MAX_FIELD_NAME_BYTES)
        validateCommandShape(command)

        val allNames = (snapshot.columns + snapshot.tags).map { it.name }.toSet()
        val qualified = "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(stable)}"
        val clause = when (command.operation) {
            TimeSeriesLifecycleOperation.ADD_COLUMN -> {
                require(command.name !in allNames) { "字段或 Tag 已存在：${command.name}" }
                require(snapshot.columns.size < MAX_COLUMN_COUNT) { "超级表最多支持 $MAX_COLUMN_COUNT 个普通字段" }
                val type = requireNotNull(command.type)
                validateFieldType(type, command.length, FieldRole.COLUMN)
                validateAggregateStorage(snapshot.columns, command, MAX_ROW_BYTES, "全部普通字段的估算行长度不能超过 64 KB")
                "ADD COLUMN ${dialect.quoteIdentifier(command.name)} ${renderType(type, command.length)}"
            }

            TimeSeriesLifecycleOperation.DROP_COLUMN -> {
                val current = snapshot.columns.requireNamed(command.name, "字段")
                require(!current.primaryTimestamp) { "不能删除超级表主时间戳列：${command.name}" }
                require(snapshot.columns.size > MIN_COLUMN_COUNT) { "超级表至少需要保留主时间戳列和一个普通字段" }
                "DROP COLUMN ${dialect.quoteIdentifier(command.name)}"
            }

            TimeSeriesLifecycleOperation.MODIFY_COLUMN -> {
                val current = snapshot.columns.requireNamed(command.name, "字段")
                require(!current.primaryTimestamp) { "不能修改超级表主时间戳列：${command.name}" }
                validateExpansion(current, requireNotNull(command.type), requireNotNull(command.length), FieldRole.COLUMN)
                validateExpansionStorage(
                    snapshot.columns,
                    current,
                    requireNotNull(command.type),
                    requireNotNull(command.length),
                    MAX_ROW_BYTES,
                    "扩展后全部普通字段的估算行长度不能超过 64 KB"
                )
                "MODIFY COLUMN ${dialect.quoteIdentifier(command.name)} ${renderType(requireNotNull(command.type), command.length)}"
            }

            TimeSeriesLifecycleOperation.ADD_TAG -> {
                require(command.name !in allNames) { "字段或 Tag 已存在：${command.name}" }
                require(snapshot.tags.size < MAX_TAG_COUNT) { "超级表最多支持 $MAX_TAG_COUNT 个 Tags" }
                val type = requireNotNull(command.type)
                validateFieldType(type, command.length, FieldRole.TAG)
                validateAggregateStorage(snapshot.tags, command, MAX_TAG_BYTES, "全部 Tag 定义的总长度不能超过 16 KB")
                "ADD TAG ${dialect.quoteIdentifier(command.name)} ${renderType(type, command.length)}"
            }

            TimeSeriesLifecycleOperation.DROP_TAG -> {
                snapshot.tags.requireNamed(command.name, "Tag")
                require(snapshot.tags.size > 1) { "超级表至少需要保留一个 Tag" }
                "DROP TAG ${dialect.quoteIdentifier(command.name)}"
            }

            TimeSeriesLifecycleOperation.MODIFY_TAG -> {
                val current = snapshot.tags.requireNamed(command.name, "Tag")
                validateExpansion(current, requireNotNull(command.type), requireNotNull(command.length), FieldRole.TAG)
                validateExpansionStorage(
                    snapshot.tags,
                    current,
                    requireNotNull(command.type),
                    requireNotNull(command.length),
                    MAX_TAG_BYTES,
                    "扩展后全部 Tag 定义的总长度不能超过 16 KB"
                )
                "MODIFY TAG ${dialect.quoteIdentifier(command.name)} ${renderType(requireNotNull(command.type), command.length)}"
            }

            TimeSeriesLifecycleOperation.RENAME_TAG -> {
                snapshot.tags.requireNamed(command.name, "Tag")
                val newName = requireNotNull(command.newName)
                validateIdentifier(newName, "新 Tag 名", MAX_FIELD_NAME_BYTES)
                require(newName != command.name) { "新 Tag 名必须与原名称不同" }
                require(newName !in allNames) { "字段或 Tag 已存在：$newName" }
                "RENAME TAG ${dialect.quoteIdentifier(command.name)} ${dialect.quoteIdentifier(newName)}"
            }
        }
        return "ALTER STABLE $qualified $clause"
    }

    override fun inspectChildProperties(
        session: DatabaseSession,
        database: String,
        table: String
    ): TimeSeriesChildPropertySnapshot {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(table, "子表名", MAX_TABLE_NAME_BYTES)
        val child = timeSeriesMetadata.inspectChildTable(session, database, table)
        require(child.stableName.isNotBlank()) { "对象不是子表：$database.$table" }
        val fingerprint = childPropertyFingerprint(
            database = database,
            table = table,
            stableName = child.stableName,
            tagValues = child.tagValues,
            ttl = child.ttl,
            comment = child.comment
        )
        return TimeSeriesChildPropertySnapshot(
            database = database,
            table = table,
            stableName = child.stableName,
            tagValues = child.tagValues,
            ttl = child.ttl,
            comment = child.comment,
            fingerprint = fingerprint
        )
    }

    override fun buildChildPropertyMutationSql(
        database: String,
        table: String,
        snapshot: TimeSeriesChildPropertySnapshot,
        command: TimeSeriesChildPropertyCommand
    ): String {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(table, "子表名", MAX_TABLE_NAME_BYTES)
        require(snapshot.database == database && snapshot.table == table) { "属性快照与目标子表不匹配" }
        validateChildPropertyCommand(command)
        val qualified = "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"
        val clause = when (command.operation) {
            TimeSeriesChildPropertyOperation.SET_TAG -> {
                val tagName = requireNotNull(command.tagName)
                validateIdentifier(tagName, "Tag 名", MAX_FIELD_NAME_BYTES)
                val current = snapshot.tagValues.firstOrNull { it.name == tagName }
                    ?: throw IllegalArgumentException("Tag 不存在：$tagName")
                val literal = tagValueRenderer.renderTagLiteral(
                    TimeSeriesTagDefinition(current.name, current.type),
                    TimeSeriesTagValueDraft(current.name, command.value, command.isNull)
                )
                "SET TAG ${dialect.quoteIdentifier(tagName)} = $literal"
            }

            TimeSeriesChildPropertyOperation.SET_TTL -> {
                val ttl = requireNotNull(command.ttl)
                require(ttl in MIN_TTL..MAX_TTL) { "TTL 必须在 $MIN_TTL 到 $MAX_TTL 之间" }
                "TTL $ttl"
            }

            TimeSeriesChildPropertyOperation.SET_COMMENT -> {
                val comment = requireNotNull(command.comment)
                require(comment.none { it.code == 0 }) { "COMMENT 不能包含空字符" }
                require(comment.toByteArray(Charsets.UTF_8).size <= MAX_COMMENT_BYTES) {
                    "COMMENT 不能超过 $MAX_COMMENT_BYTES 字节"
                }
                "COMMENT ${dialect.escapeValue(comment)}"
            }
        }
        return "ALTER TABLE $qualified $clause"
    }

    private fun validateChildPropertyCommand(command: TimeSeriesChildPropertyCommand) {
        when (command.operation) {
            TimeSeriesChildPropertyOperation.SET_TAG -> {
                require(command.tagName != null && command.ttl == null && command.comment == null) {
                    "SET_TAG 只能提供 tagName、value 和 isNull"
                }
                if (command.isNull) {
                    require(command.value == null) { "写入 NULL 时不能同时提供 value" }
                } else {
                    require(command.value != null) { "写入 Tag 时必须提供 value，空字符串可作为有效值" }
                }
            }

            TimeSeriesChildPropertyOperation.SET_TTL -> require(
                command.tagName == null && command.value == null && !command.isNull &&
                    command.ttl != null && command.comment == null
            ) { "SET_TTL 只能提供 ttl" }

            TimeSeriesChildPropertyOperation.SET_COMMENT -> require(
                command.tagName == null && command.value == null && !command.isNull &&
                    command.ttl == null && command.comment != null
            ) { "SET_COMMENT 只能提供 comment" }
        }
    }

    private fun validateCommandShape(command: TimeSeriesLifecycleCommand) {
        when (command.operation) {
            TimeSeriesLifecycleOperation.ADD_COLUMN,
            TimeSeriesLifecycleOperation.ADD_TAG -> {
                require(command.type != null) { "${command.operation} 必须提供 type" }
                require(command.newName == null) { "${command.operation} 不能提供 newName" }
            }

            TimeSeriesLifecycleOperation.DROP_COLUMN,
            TimeSeriesLifecycleOperation.DROP_TAG -> require(
                command.type == null && command.length == null && command.newName == null
            ) { "${command.operation} 只能提供 name" }

            TimeSeriesLifecycleOperation.MODIFY_COLUMN,
            TimeSeriesLifecycleOperation.MODIFY_TAG -> require(
                command.type != null && command.length != null && command.newName == null
            ) { "${command.operation} 必须提供 type 和 length，且不能提供 newName" }

            TimeSeriesLifecycleOperation.RENAME_TAG -> require(
                command.type == null && command.length == null && command.newName != null
            ) { "RENAME_TAG 只能提供 name 和 newName" }
        }
    }

    private fun validateExpansion(
        current: TimeSeriesLifecycleField,
        requestedType: TimeSeriesDataType,
        requestedLength: Int,
        role: FieldRole
    ) {
        require(requestedType in VARIABLE_LENGTH_TYPES) { "仅支持扩展 BINARY、VARCHAR 或 NCHAR 长度" }
        require(sameTypeFamily(current.type, requestedType.sql)) {
            "不能修改${role.label}类型：${current.type} -> ${requestedType.sql}"
        }
        val currentLength = current.length
            ?: throw IllegalArgumentException("${role.label} ${current.name} 缺少可扩展长度元数据")
        validateFieldType(requestedType, requestedLength, role)
        require(requestedLength > currentLength) {
            "${role.label} ${current.name} 的新长度必须大于当前长度 $currentLength"
        }
    }

    private fun validateFieldType(type: TimeSeriesDataType, length: Int?, role: FieldRole) {
        if (type.requiresLength) {
            val actualLength = length ?: throw IllegalArgumentException("${type.sql} 必须设置类型长度")
            val max = maxLength(type, role)
            require(actualLength in 1..max) { "${type.sql} 长度必须在 1 到 $max 之间" }
        } else {
            require(length == null) { "${type.sql} 类型不能设置长度" }
        }
    }

    private fun validateAggregateStorage(
        existing: List<TimeSeriesLifecycleField>,
        command: TimeSeriesLifecycleCommand,
        maxBytes: Int,
        message: String
    ) {
        val existingBytes = existing.map { storageBytes(it.type, it.length) }
        if (existingBytes.any { it == null }) return
        val newBytes = requireNotNull(storageBytes(requireNotNull(command.type).sql, command.length))
        require(existingBytes.filterNotNull().sum() + newBytes <= maxBytes) { message }
    }

    private fun validateExpansionStorage(
        existing: List<TimeSeriesLifecycleField>,
        current: TimeSeriesLifecycleField,
        requestedType: TimeSeriesDataType,
        requestedLength: Int,
        maxBytes: Int,
        message: String
    ) {
        val existingBytes = existing.map { storageBytes(it.type, it.length) }
        if (existingBytes.any { it == null }) return
        val currentBytes = requireNotNull(storageBytes(current.type, current.length))
        val requestedBytes = requireNotNull(storageBytes(requestedType.sql, requestedLength))
        require(existingBytes.filterNotNull().sum() - currentBytes + requestedBytes <= maxBytes) { message }
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

    private fun maxLength(type: TimeSeriesDataType, role: FieldRole): Int = when (role) {
        FieldRole.COLUMN -> if (type == TimeSeriesDataType.NCHAR) MAX_COLUMN_NCHAR_LENGTH else MAX_COLUMN_STRING_LENGTH
        FieldRole.TAG -> if (type == TimeSeriesDataType.NCHAR) MAX_TAG_NCHAR_LENGTH else MAX_TAG_STRING_LENGTH
    }

    private fun parseCatalogType(raw: String): ParsedType {
        val normalized = raw.trim().uppercase().replace(WHITESPACE, " ")
        val match = TYPE_PATTERN.matchEntire(normalized)
        return if (match == null) {
            ParsedType(normalized, null)
        } else {
            ParsedType(match.groupValues[1], match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull())
        }
    }

    private fun structureFingerprint(
        database: String,
        stable: String,
        columns: List<TimeSeriesLifecycleField>,
        tags: List<TimeSeriesLifecycleField>,
        affectedChildTables: Long
    ): String {
        val canonical = buildString {
            appendPart(database)
            appendPart(stable)
            columns.sortedBy { it.name }.forEach { appendField("column", it) }
            tags.sortedBy { it.name }.forEach { appendField("tag", it) }
            appendPart(affectedChildTables.toString())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun childPropertyFingerprint(
        database: String,
        table: String,
        stableName: String,
        tagValues: List<com.easydb.common.TimeSeriesTagValue>,
        ttl: Int,
        comment: String?
    ): String {
        val canonical = buildString {
            appendPart(database)
            appendPart(table)
            appendPart(stableName)
            tagValues.sortedBy { it.name }.forEach { tag ->
                appendPart(tag.name)
                appendPart(tag.type)
                appendPart(if (tag.value == null) "null" else "value:${tag.value}")
            }
            appendPart(ttl.toString())
            appendPart(if (comment == null) "null" else "value:$comment")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun deleteFingerprint(
        database: String,
        name: String,
        kind: TimeSeriesDeleteObjectKind,
        stableName: String?,
        createdAt: String?,
        affectedChildTables: Long
    ): String {
        val canonical = buildString {
            appendPart(database)
            appendPart(name)
            appendPart(kind.name)
            appendPart(stableName.orEmpty())
            appendPart(createdAt.orEmpty())
            appendPart(affectedChildTables.toString())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun StringBuilder.appendField(role: String, field: TimeSeriesLifecycleField) {
        appendPart(role)
        appendPart(field.name)
        appendPart(field.type)
        appendPart(field.length?.toString().orEmpty())
        appendPart(field.primaryTimestamp.toString())
    }

    private fun StringBuilder.appendPart(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    private fun renderType(type: TimeSeriesDataType, length: Int?): String = buildString {
        append(type.sql)
        if (length != null) append("($length)")
    }

    private fun validateIdentifier(value: String, label: String, maxBytes: Int) {
        require(value.isNotBlank()) { "${label}不能为空" }
        require(value == value.trim()) { "${label}不能包含首尾空白" }
        require('.' !in value) { "${label}不能包含句点" }
        require(value.none { it.code < 32 || it.code == 127 }) { "${label}不能包含控制字符" }
        require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "${label}不能超过 $maxBytes 字节" }
    }

    private fun List<TimeSeriesLifecycleField>.requireNamed(name: String, label: String): TimeSeriesLifecycleField =
        firstOrNull { it.name == name } ?: throw IllegalArgumentException("$label 不存在：$name")

    private fun sameTypeFamily(left: String, right: String): Boolean =
        canonicalType(left) == canonicalType(right)

    private fun canonicalType(type: String): String = when (type.uppercase()) {
        "BINARY", "VARCHAR" -> "BINARY"
        else -> type.uppercase()
    }

    private fun TimeSeriesLifecycleOperation.targetLabel(): String = when (this) {
        TimeSeriesLifecycleOperation.ADD_TAG,
        TimeSeriesLifecycleOperation.DROP_TAG,
        TimeSeriesLifecycleOperation.MODIFY_TAG,
        TimeSeriesLifecycleOperation.RENAME_TAG -> "Tag 名"
        else -> "字段名"
    }

    private enum class FieldRole(val label: String) {
        COLUMN("字段"),
        TAG("Tag")
    }

    private data class ParsedType(val type: String, val length: Int?)

    companion object {
        private const val MAX_DATABASE_NAME_BYTES = 64
        private const val MAX_TABLE_NAME_BYTES = 192
        private const val MAX_FIELD_NAME_BYTES = 64
        private const val MIN_COLUMN_COUNT = 2
        private const val MAX_COLUMN_COUNT = 4096
        private const val MAX_TAG_COUNT = 128
        private const val MAX_ROW_BYTES = 64 * 1024
        private const val MAX_TAG_BYTES = 16 * 1024
        private const val MAX_COLUMN_STRING_LENGTH = 65_517
        private const val MAX_COLUMN_NCHAR_LENGTH = 16_379
        private const val MAX_TAG_STRING_LENGTH = 16_382
        private const val MAX_TAG_NCHAR_LENGTH = 4_095
        private const val MIN_TTL = 0
        private const val MAX_TTL = Int.MAX_VALUE
        private const val MAX_COMMENT_BYTES = 1024
        private val WHITESPACE = Regex("\\s+")
        private val TYPE_PATTERN = Regex("^([A-Z]+(?: UNSIGNED)?)(?:\\((\\d+)\\))?$")
        private val VARIABLE_LENGTH_TYPES = setOf(
            TimeSeriesDataType.BINARY,
            TimeSeriesDataType.VARCHAR,
            TimeSeriesDataType.NCHAR
        )
    }
}
