package com.easydb.launcher

import com.easydb.common.DatabaseSession
import com.easydb.common.InvalidReadOnlyClauseException
import com.easydb.common.InvalidTimeSeriesRangeException
import com.easydb.common.TimeSeriesQueryPage
import com.easydb.common.TimeSeriesQueryRequest
import java.sql.SQLException

internal class TimeSeriesQueryService(
    private val adapterRegistry: DatabaseAdapterRegistry
) {
    fun previewRows(
        session: DatabaseSession,
        database: String,
        table: String,
        request: TimeSeriesQueryRequest
    ): TimeSeriesQueryPage {
        val databaseAdapter = adapterRegistry.get(session.config.dbType)
        if (!databaseAdapter.capabilities().supportsTimeSeriesQuery) {
            throw TimeSeriesQueryException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）不支持时序范围查询"
            )
        }
        val queryAdapter = databaseAdapter.timeSeriesQueryAdapter()
            ?: throw TimeSeriesQueryException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）未提供时序查询适配器"
            )
        return try {
            queryAdapter.previewRows(session, database, table, request)
        } catch (error: InvalidTimeSeriesRangeException) {
            throw TimeSeriesQueryException("INVALID_TIME_RANGE", error.message ?: "时间范围无效", error)
        } catch (error: InvalidReadOnlyClauseException) {
            throw TimeSeriesQueryException(
                "INVALID_READ_ONLY_CLAUSE",
                error.message ?: "筛选或排序条件无效",
                error
            )
        } catch (error: SQLException) {
            throw TimeSeriesQueryException(
                "TIME_SERIES_PREVIEW_FAILED",
                error.message ?: "TDengine 时序预览失败",
                error
            )
        } catch (error: IllegalArgumentException) {
            throw TimeSeriesQueryException(
                "INVALID_TIME_SERIES_QUERY",
                error.message ?: "时序查询请求无效",
                error
            )
        }
    }
}

internal class TimeSeriesQueryException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
