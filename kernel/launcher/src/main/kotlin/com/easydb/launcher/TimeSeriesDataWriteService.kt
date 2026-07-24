package com.easydb.launcher

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesDataWriteAdapter
import com.easydb.common.TimeSeriesWriteApplyRequest
import com.easydb.common.TimeSeriesWritePreview
import com.easydb.common.TimeSeriesWriteRequest
import com.easydb.common.TimeSeriesWriteResult
import java.security.MessageDigest
import java.sql.SQLException

internal class TimeSeriesDataWriteService(private val registry: DatabaseAdapterRegistry) {
    fun preview(session: DatabaseSession, database: String, request: TimeSeriesWriteRequest): TimeSeriesWritePreview {
        val adapter = resolve(session)
        val snapshot = inspect(adapter, session, database, request)
        val plan = build(adapter, database, snapshot, request)
        return TimeSeriesWritePreview(
            request, snapshot, plan.previewSql, token(snapshot.fingerprint, plan.previewSql),
            plan.rowCount, plan.createsChildTable
        )
    }

    fun apply(session: DatabaseSession, database: String, request: TimeSeriesWriteApplyRequest): TimeSeriesWriteResult {
        val adapter = resolve(session)
        if (request.expectedFingerprint.isBlank() || request.previewToken.isBlank()) invalid("缺少写入指纹或预览令牌")
        val snapshot = try {
            adapter.inspectWriteTarget(session, database, request.request)
        } catch (error: IllegalArgumentException) {
            throw TimeSeriesDataWriteException("OBJECT_CHANGED", "写入目标或结构已变化，请重新预览", error)
        } catch (error: SQLException) {
            throw TimeSeriesDataWriteException("TIME_SERIES_WRITE_FAILED", error.message ?: "重新读取写入目标失败", error)
        }
        if (snapshot.fingerprint != request.expectedFingerprint) {
            throw TimeSeriesDataWriteException("OBJECT_CHANGED", "写入目标或结构已变化，请重新预览")
        }
        val plan = build(adapter, database, snapshot, request.request)
        if (token(snapshot.fingerprint, plan.previewSql) != request.previewToken) invalid("写入内容与已预览内容不一致")
        try {
            adapter.executeWritePlan(session, plan)
        } catch (error: SQLException) {
            throw TimeSeriesDataWriteException("TIME_SERIES_WRITE_FAILED", error.message ?: "写入时序数据失败", error)
        }
        return TimeSeriesWriteResult(true, request.request.table, snapshot.stableName, plan.rowCount, plan.createsChildTable)
    }

    private fun resolve(session: DatabaseSession): TimeSeriesDataWriteAdapter {
        val database = registry.get(session.config.dbType)
        if (!database.capabilities().supportsTimeSeriesDataWrite) unsupported(session)
        return database.timeSeriesDataWriteAdapter() ?: unsupported(session)
    }

    private fun inspect(adapter: TimeSeriesDataWriteAdapter, session: DatabaseSession, database: String, request: TimeSeriesWriteRequest) = try {
        adapter.inspectWriteTarget(session, database, request)
    } catch (error: IllegalArgumentException) {
        throw TimeSeriesDataWriteException("INVALID_TIME_SERIES_WRITE", error.message ?: "写入请求无效", error)
    } catch (error: SQLException) {
        throw TimeSeriesDataWriteException("TIME_SERIES_WRITE_FAILED", error.message ?: "读取写入目标失败", error)
    }

    private fun build(adapter: TimeSeriesDataWriteAdapter, database: String, snapshot: com.easydb.common.TimeSeriesWriteSnapshot, request: TimeSeriesWriteRequest) = try {
        adapter.buildWritePlan(database, snapshot, request)
    } catch (error: IllegalArgumentException) {
        throw TimeSeriesDataWriteException("INVALID_TIME_SERIES_WRITE", error.message ?: "写入请求无效", error)
    }

    private fun token(fingerprint: String, sql: String) = MessageDigest.getInstance("SHA-256")
        .digest("$fingerprint|$sql".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun invalid(message: String): Nothing = throw TimeSeriesDataWriteException("INVALID_TIME_SERIES_WRITE", message)
    private fun unsupported(session: DatabaseSession): Nothing = throw TimeSeriesDataWriteException("UNSUPPORTED_DB_FEATURE", "当前数据库类型（${session.config.dbType}）不支持时序数据写入")
}

internal class TimeSeriesDataWriteException(val code: String, override val message: String, cause: Throwable? = null) : RuntimeException(message, cause)
