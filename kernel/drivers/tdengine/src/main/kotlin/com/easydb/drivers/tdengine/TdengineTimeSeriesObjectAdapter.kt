package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesCreateDefinition
import com.easydb.common.TimeSeriesCreateKind
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesFieldDraft
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesObjectAdapter
import com.easydb.common.TimeSeriesParentNotFoundException
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagValueDraft
import java.math.BigDecimal
import java.math.BigInteger

class TdengineTimeSeriesObjectAdapter(
    private val metadata: TimeSeriesMetadataAdapter,
    private val dialect: TdengineDialectAdapter = TdengineDialectAdapter()
) : TimeSeriesObjectAdapter {

    override fun buildCreateSql(
        session: DatabaseSession,
        database: String,
        definition: TimeSeriesCreateDefinition
    ): String {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(definition.name, "对象名", MAX_TABLE_NAME_BYTES)
        validateComment(definition.comment)

        return when (definition.kind) {
            TimeSeriesCreateKind.SUPER_TABLE -> buildSuperTable(database, definition)
            TimeSeriesCreateKind.BASIC_TABLE -> buildBasicTable(database, definition)
            TimeSeriesCreateKind.CHILD_TABLE -> buildChildTable(session, database, definition)
        }
    }

    private fun buildSuperTable(database: String, definition: TimeSeriesCreateDefinition): String {
        require(definition.stableName == null && definition.tagValues.isEmpty()) {
            "超级表创建请求不能包含父超级表或 Tag 值"
        }
        validateColumns(definition.columns)
        require(definition.tags.isNotEmpty()) { "超级表至少需要一个 Tag" }
        require(definition.tags.size <= MAX_TAG_COUNT) { "超级表最多支持 $MAX_TAG_COUNT 个 Tags" }
        validateFields(definition.tags, FieldRole.TAG)
        validateUniqueNames(definition.columns + definition.tags)
        require(definition.tags.sumOf { storageBytes(it) } <= MAX_TAG_BYTES) {
            "全部 Tag 定义的总长度不能超过 16 KB"
        }

        return buildString {
            append("CREATE STABLE ")
            append(qualified(database, definition.name))
            append(" (")
            append(definition.columns.joinToString(", ") { renderField(it) })
            append(") TAGS (")
            append(definition.tags.joinToString(", ") { renderField(it) })
            append(')')
            appendComment(definition.comment)
        }
    }

    private fun buildBasicTable(database: String, definition: TimeSeriesCreateDefinition): String {
        require(
            definition.tags.isEmpty() && definition.stableName == null && definition.tagValues.isEmpty()
        ) {
            "普通表创建请求不能包含 Tags、父超级表或 Tag 值"
        }
        validateColumns(definition.columns)
        validateUniqueNames(definition.columns)

        return buildString {
            append("CREATE TABLE ")
            append(qualified(database, definition.name))
            append(" (")
            append(definition.columns.joinToString(", ") { renderField(it) })
            append(')')
            appendComment(definition.comment)
        }
    }

    private fun buildChildTable(
        session: DatabaseSession,
        database: String,
        definition: TimeSeriesCreateDefinition
    ): String {
        require(definition.columns.isEmpty() && definition.tags.isEmpty()) {
            "子表创建请求不能自定义字段或 Tag 定义"
        }
        require(definition.comment == null) { "子表创建暂不支持 COMMENT" }
        val stableName = definition.stableName
            ?: throw IllegalArgumentException("子表必须选择父超级表")
        validateIdentifier(stableName, "父超级表名", MAX_TABLE_NAME_BYTES)

        val tagDefinitions = metadata.listTagDefinitions(session, database, stableName)
        if (tagDefinitions.isEmpty()) {
            throw TimeSeriesParentNotFoundException("父超级表不存在或没有可见的 Tag 定义：$stableName")
        }
        val valuesByName = definition.tagValues.associateByUniqueName()
        val expectedNames = tagDefinitions.map { it.name }.toSet()
        require(valuesByName.keys == expectedNames) {
            val missing = expectedNames - valuesByName.keys
            val unknown = valuesByName.keys - expectedNames
            buildString {
                append("子表 Tag 值必须与父超级表定义一致")
                if (missing.isNotEmpty()) append("，缺少：${missing.sorted().joinToString()}")
                if (unknown.isNotEmpty()) append("，未知：${unknown.sorted().joinToString()}")
            }
        }

        val resolvedTags = tagDefinitions.map { tag ->
            val resolvedType = resolveCatalogType(tag)
            ResolvedTag(
                definition = tag,
                type = resolvedType,
                value = requireNotNull(valuesByName[tag.name])
            )
        }

        return buildString {
            append("CREATE TABLE ")
            append(qualified(database, definition.name))
            append(" USING ")
            append(qualified(database, stableName))
            append(" (")
            append(resolvedTags.joinToString(", ") { dialect.quoteIdentifier(it.definition.name) })
            append(") TAGS (")
            append(resolvedTags.joinToString(", ") { renderTagValue(it) })
            append(')')
        }
    }

    private fun validateColumns(columns: List<TimeSeriesFieldDraft>) {
        require(columns.size in MIN_COLUMN_COUNT..MAX_COLUMN_COUNT) {
            "普通字段数量必须在 $MIN_COLUMN_COUNT 到 $MAX_COLUMN_COUNT 之间"
        }
        require(columns.first().type == TimeSeriesDataType.TIMESTAMP) {
            "第一个字段必须是 TIMESTAMP"
        }
        validateFields(columns, FieldRole.COLUMN)
        require(columns.sumOf { storageBytes(it) } <= MAX_ROW_BYTES) {
            "全部普通字段的估算行长度不能超过 64 KB"
        }
    }

    private fun validateFields(fields: List<TimeSeriesFieldDraft>, role: FieldRole) {
        fields.forEach { field ->
            validateIdentifier(field.name, if (role == FieldRole.TAG) "Tag 名" else "字段名", MAX_FIELD_NAME_BYTES)
            if (field.type.requiresLength) {
                val length = field.length ?: throw IllegalArgumentException("${field.name} 必须设置类型长度")
                val maxLength = maxLength(field.type, role)
                require(length in 1..maxLength) {
                    "${field.name} 的长度必须在 1 到 $maxLength 之间"
                }
            } else {
                require(field.length == null) { "${field.name} 的 ${field.type.sql} 类型不能设置长度" }
            }
        }
    }

    private fun validateUniqueNames(fields: List<TimeSeriesFieldDraft>) {
        val duplicates = fields.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "字段名与 Tag 名不能重复：${duplicates.sorted().joinToString()}" }
    }

    private fun renderField(field: TimeSeriesFieldDraft): String = buildString {
        append(dialect.quoteIdentifier(field.name))
        append(' ')
        append(field.type.sql)
        field.length?.let { append("($it)") }
    }

    private fun resolveCatalogType(tag: TimeSeriesTagDefinition): ResolvedType {
        val match = TYPE_PATTERN.matchEntire(tag.type.trim().uppercase().replace(WHITESPACE, " "))
            ?: throw unsupportedParentTag(tag)
        val sqlType = match.groupValues[1]
        val type = TimeSeriesDataType.entries.firstOrNull { it.sql == sqlType }
            ?: throw unsupportedParentTag(tag)
        val length = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: if (type.requiresLength) throw unsupportedParentTag(tag) else null
        if (!type.requiresLength && length != null) throw unsupportedParentTag(tag)
        return ResolvedType(type, length)
    }

    private fun unsupportedParentTag(tag: TimeSeriesTagDefinition) = IllegalArgumentException(
        "父超级表 Tag ${tag.name} 的类型 ${tag.type} 暂不支持可视化创建子表"
    )

    private fun renderTagValue(tag: ResolvedTag): String {
        if (tag.value.isNull) return "NULL"
        val raw = tag.value.value
            ?: throw IllegalArgumentException("Tag ${tag.definition.name} 未设置值，也未显式设为 NULL")
        val label = "Tag ${tag.definition.name}"
        require(raw.length <= MAX_TAG_INPUT_CHARS) { "$label 输入内容过长" }

        return when (tag.type.type) {
            TimeSeriesDataType.BOOL -> when (raw.trim().lowercase()) {
                "true" -> "TRUE"
                "false" -> "FALSE"
                else -> throw IllegalArgumentException("$label 必须是 true 或 false")
            }
            TimeSeriesDataType.TINYINT -> integerLiteral(raw, label, "-128", "127")
            TimeSeriesDataType.TINYINT_UNSIGNED -> integerLiteral(raw, label, "0", "255")
            TimeSeriesDataType.SMALLINT -> integerLiteral(raw, label, "-32768", "32767")
            TimeSeriesDataType.SMALLINT_UNSIGNED -> integerLiteral(raw, label, "0", "65535")
            TimeSeriesDataType.INT -> integerLiteral(raw, label, "-2147483648", "2147483647")
            TimeSeriesDataType.INT_UNSIGNED -> integerLiteral(raw, label, "0", "4294967295")
            TimeSeriesDataType.BIGINT -> integerLiteral(raw, label, Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())
            TimeSeriesDataType.BIGINT_UNSIGNED -> integerLiteral(raw, label, "0", "18446744073709551615")
            TimeSeriesDataType.FLOAT -> decimalLiteral(raw, label, FLOAT_MAX)
            TimeSeriesDataType.DOUBLE -> decimalLiteral(raw, label, DOUBLE_MAX)
            TimeSeriesDataType.TIMESTAMP -> timestampLiteral(raw, label)
            TimeSeriesDataType.BINARY,
            TimeSeriesDataType.VARCHAR -> {
                val maxBytes = requireNotNull(tag.type.length)
                require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes) { "$label 超过声明长度 $maxBytes 字节" }
                stringLiteral(raw)
            }
            TimeSeriesDataType.NCHAR -> {
                val maxCharacters = requireNotNull(tag.type.length)
                require(raw.codePointCount() <= maxCharacters) { "$label 超过声明长度 $maxCharacters 个字符" }
                stringLiteral(raw)
            }
        }
    }

    private fun integerLiteral(raw: String, label: String, min: String, max: String): String {
        val normalized = raw.trim()
        require(INTEGER_PATTERN.matches(normalized)) { "$label 必须是整数" }
        val value = runCatching { BigInteger(normalized) }
            .getOrElse { throw IllegalArgumentException("$label 必须是整数") }
        require(value >= BigInteger(min) && value <= BigInteger(max)) { "$label 超出类型范围" }
        return value.toString()
    }

    private fun decimalLiteral(raw: String, label: String, max: BigDecimal): String {
        val value = runCatching { BigDecimal(raw.trim()) }
            .getOrElse { throw IllegalArgumentException("$label 必须是有限数值") }
        require(value.abs() <= max) { "$label 超出类型范围" }
        return value.stripTrailingZeros().toPlainString()
    }

    private fun timestampLiteral(raw: String, label: String): String {
        val normalized = raw.trim()
        require(normalized.isNotEmpty()) { "$label 不能为空" }
        if (INTEGER_PATTERN.matches(normalized)) {
            return integerLiteral(normalized, label, Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())
        }
        require(TIMESTAMP_PATTERN.matches(normalized)) {
            "$label 必须是时间文本（YYYY-MM-DD HH:mm:ss[.fraction]）或 epoch 整数"
        }
        return stringLiteral(normalized)
    }

    private fun List<TimeSeriesTagValueDraft>.associateByUniqueName(): Map<String, TimeSeriesTagValueDraft> {
        val result = linkedMapOf<String, TimeSeriesTagValueDraft>()
        forEach { value ->
            validateIdentifier(value.name, "Tag 名", MAX_FIELD_NAME_BYTES)
            require(result.put(value.name, value) == null) { "Tag 值名称不能重复：${value.name}" }
        }
        return result
    }

    private fun validateIdentifier(value: String, label: String, maxBytes: Int) {
        require(value.isNotBlank()) { "${label}不能为空" }
        require(value == value.trim()) { "${label}不能包含首尾空白" }
        require('.' !in value) { "${label}不能包含句点" }
        require(value.none { it.code < 32 || it.code == 127 }) { "${label}不能包含控制字符" }
        require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "${label}不能超过 $maxBytes 字节" }
    }

    private fun validateComment(comment: String?) {
        if (comment == null) return
        require(comment.toByteArray(Charsets.UTF_8).size <= MAX_COMMENT_BYTES) {
            "COMMENT 不能超过 $MAX_COMMENT_BYTES 字节"
        }
        require(comment.none { it.code == 0 }) { "COMMENT 不能包含空字符" }
    }

    private fun maxLength(type: TimeSeriesDataType, role: FieldRole): Int = when (role) {
        FieldRole.COLUMN -> if (type == TimeSeriesDataType.NCHAR) MAX_COLUMN_NCHAR_LENGTH else MAX_COLUMN_STRING_LENGTH
        FieldRole.TAG -> if (type == TimeSeriesDataType.NCHAR) MAX_TAG_NCHAR_LENGTH else MAX_TAG_STRING_LENGTH
    }

    private fun storageBytes(field: TimeSeriesFieldDraft): Int = when (field.type) {
        TimeSeriesDataType.TIMESTAMP, TimeSeriesDataType.BIGINT, TimeSeriesDataType.BIGINT_UNSIGNED,
        TimeSeriesDataType.DOUBLE -> 8
        TimeSeriesDataType.INT, TimeSeriesDataType.INT_UNSIGNED, TimeSeriesDataType.FLOAT -> 4
        TimeSeriesDataType.SMALLINT, TimeSeriesDataType.SMALLINT_UNSIGNED -> 2
        TimeSeriesDataType.TINYINT, TimeSeriesDataType.TINYINT_UNSIGNED, TimeSeriesDataType.BOOL -> 1
        TimeSeriesDataType.BINARY, TimeSeriesDataType.VARCHAR -> requireNotNull(field.length) + 2
        TimeSeriesDataType.NCHAR -> requireNotNull(field.length) * 4 + 2
    }

    private fun qualified(database: String, name: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(name)}"

    private fun StringBuilder.appendComment(comment: String?) {
        if (comment != null) append(" COMMENT ${stringLiteral(comment)}")
    }

    private fun stringLiteral(value: String): String = dialect.escapeValue(value)

    private fun String.codePointCount(): Int = this.codePointCount(0, length)

    private enum class FieldRole { COLUMN, TAG }

    private data class ResolvedType(val type: TimeSeriesDataType, val length: Int?)

    private data class ResolvedTag(
        val definition: TimeSeriesTagDefinition,
        val type: ResolvedType,
        val value: TimeSeriesTagValueDraft
    )

    companion object {
        private const val MAX_DATABASE_NAME_BYTES = 64
        private const val MAX_TABLE_NAME_BYTES = 192
        private const val MAX_FIELD_NAME_BYTES = 64
        private const val MAX_COMMENT_BYTES = 1024
        private const val MAX_TAG_INPUT_CHARS = 65_536
        private const val MIN_COLUMN_COUNT = 2
        private const val MAX_COLUMN_COUNT = 4096
        private const val MAX_TAG_COUNT = 128
        private const val MAX_ROW_BYTES = 64 * 1024
        private const val MAX_TAG_BYTES = 16 * 1024
        private const val MAX_COLUMN_STRING_LENGTH = 65_517
        private const val MAX_COLUMN_NCHAR_LENGTH = 16_379
        private const val MAX_TAG_STRING_LENGTH = 16_382
        private const val MAX_TAG_NCHAR_LENGTH = 4_095
        private val WHITESPACE = Regex("\\s+")
        private val TYPE_PATTERN = Regex("^([A-Z]+(?: UNSIGNED)?)(?:\\((\\d+)\\))?$")
        private val INTEGER_PATTERN = Regex("^[+-]?\\d+$")
        private val TIMESTAMP_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?$")
        private val FLOAT_MAX = BigDecimal("3.4028235E38")
        private val DOUBLE_MAX = BigDecimal("1.7976931348623157E308")
    }
}
