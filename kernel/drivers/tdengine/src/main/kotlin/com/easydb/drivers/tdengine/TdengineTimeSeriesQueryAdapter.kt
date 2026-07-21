package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.InvalidTimeSeriesRangeException
import com.easydb.common.TimeSeriesQueryAdapter
import com.easydb.common.TimeSeriesQueryLimits
import com.easydb.common.TimeSeriesQueryPage
import com.easydb.common.TimeSeriesQueryRequest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class TdengineTimeSeriesQueryAdapter(
    private val dialect: TdengineDialectAdapter = TdengineDialectAdapter()
) : TimeSeriesQueryAdapter {

    override fun previewRows(
        session: DatabaseSession,
        database: String,
        table: String,
        request: TimeSeriesQueryRequest
    ): TimeSeriesQueryPage {
        val plan = buildQueryPlan(database, table, request)
        val fetched = session.getJdbcConnection().createStatement().use { statement ->
            statement.executeQuery(plan.sql).use { result -> result.readTdengineStringRows() }
        }
        return TimeSeriesQueryPage(
            rows = fetched.take(plan.request.limit),
            offset = plan.request.offset,
            limit = plan.request.limit,
            hasMore = fetched.size > plan.request.limit,
            startInclusive = plan.request.startInclusive,
            endExclusive = plan.request.endExclusive
        )
    }

    internal fun buildQueryPlan(
        database: String,
        table: String,
        request: TimeSeriesQueryRequest
    ): TdengineTimeSeriesQueryPlan {
        validateIdentifier(database, "数据库名", MAX_DATABASE_NAME_BYTES)
        validateIdentifier(table, "对象名", MAX_TABLE_NAME_BYTES)
        val bounds = normalizeBounds(request.startInclusive, request.endExclusive)
        val safeWhere = validateTdengineReadOnlyClause(request.where, "where")
        val safeOrderBy = validateTdengineReadOnlyClause(request.orderBy, "orderBy")
        val safeLimit = request.limit.coerceIn(1, TimeSeriesQueryLimits.MAX_PAGE_SIZE)
        val safeOffset = request.offset.coerceAtLeast(0)
        val normalized = request.copy(
            startInclusive = bounds?.first,
            endExclusive = bounds?.second,
            where = safeWhere,
            orderBy = safeOrderBy,
            limit = safeLimit,
            offset = safeOffset
        )
        val conditions = buildList {
            if (bounds != null) {
                add(
                    "_rowts >= ${dialect.escapeValue(bounds.first)} " +
                        "AND _rowts < ${dialect.escapeValue(bounds.second)}"
                )
            }
            if (safeWhere != null) add(safeWhere)
        }
        val sql = buildString {
            append("SELECT * FROM ")
            append(qualified(database, table))
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND ") { "($it)" })
            }
            append(" ORDER BY ")
            append(safeOrderBy ?: DEFAULT_ORDER_BY)
            append(" LIMIT ${safeLimit + 1} OFFSET $safeOffset")
        }
        return TdengineTimeSeriesQueryPlan(sql, normalized)
    }

    private fun normalizeBounds(start: String?, end: String?): Pair<String, String>? {
        val normalizedStart = start?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedEnd = end?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedStart == null && normalizedEnd == null) return null
        if (normalizedStart == null || normalizedEnd == null) {
            throw InvalidTimeSeriesRangeException("时间范围必须同时包含开始和结束时间")
        }
        if (normalizedStart.length > MAX_TIME_TEXT_LENGTH || normalizedEnd.length > MAX_TIME_TEXT_LENGTH) {
            throw InvalidTimeSeriesRangeException("时间范围文本过长")
        }
        val parsedStart = parseOffsetDateTime(normalizedStart, "开始时间")
        val parsedEnd = parseOffsetDateTime(normalizedEnd, "结束时间")
        if (parsedStart.toInstant() >= parsedEnd.toInstant()) {
            throw InvalidTimeSeriesRangeException("开始时间必须早于结束时间")
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(parsedStart) to
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(parsedEnd)
    }

    private fun parseOffsetDateTime(value: String, label: String): OffsetDateTime = try {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (_: DateTimeParseException) {
        throw InvalidTimeSeriesRangeException("$label 必须是带时区偏移的 RFC3339/ISO-8601 时间")
    }

    private fun validateIdentifier(value: String, label: String, maxBytes: Int) {
        require(value.isNotBlank()) { "$label 不能为空" }
        require(value == value.trim()) { "$label 不能包含首尾空白" }
        require('.' !in value) { "$label 不能包含句点" }
        require(value.none { it.code < 32 || it.code == 127 }) { "$label 不能包含控制字符" }
        require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "$label 不能超过 $maxBytes 字节" }
    }

    private fun qualified(database: String, table: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"

    companion object {
        private const val MAX_DATABASE_NAME_BYTES = 64
        private const val MAX_TABLE_NAME_BYTES = 192
        private const val MAX_TIME_TEXT_LENGTH = 64
        private const val DEFAULT_ORDER_BY = "_rowts DESC"
    }
}

internal data class TdengineTimeSeriesQueryPlan(
    val sql: String,
    val request: TimeSeriesQueryRequest
)
