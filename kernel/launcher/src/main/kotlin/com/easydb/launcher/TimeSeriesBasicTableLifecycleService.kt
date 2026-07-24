package com.easydb.launcher

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesBasicTableApplyRequest
import com.easydb.common.TimeSeriesBasicTableCommand
import com.easydb.common.TimeSeriesBasicTableLifecycleAdapter
import com.easydb.common.TimeSeriesBasicTableOperation
import com.easydb.common.TimeSeriesBasicTablePreview
import com.easydb.common.TimeSeriesBasicTableResult
import com.easydb.common.TimeSeriesBasicTableSnapshot
import java.security.MessageDigest
import java.sql.SQLException

internal class TimeSeriesBasicTableLifecycleService(private val registry: DatabaseAdapterRegistry) {
    fun inspect(session: DatabaseSession, database: String, table: String): TimeSeriesBasicTableSnapshot =
        wrapInvalid { resolve(session).inspectBasicTable(session, database, table) }

    fun preview(session: DatabaseSession, database: String, table: String, command: TimeSeriesBasicTableCommand): TimeSeriesBasicTablePreview {
        val adapter = resolve(session)
        val snapshot = wrapInvalid { adapter.inspectBasicTable(session, database, table) }
        val ddl = build(adapter, database, table, snapshot, command)
        val destructive = command.operation == TimeSeriesBasicTableOperation.DROP_COLUMN
        return TimeSeriesBasicTablePreview(
            command, snapshot, ddl, token(snapshot.fingerprint, command, ddl), destructive,
            if (destructive) listOf("删除字段会永久移除该列的历史数据，且不可撤销") else emptyList()
        )
    }

    fun apply(session: DatabaseSession, database: String, table: String, request: TimeSeriesBasicTableApplyRequest): TimeSeriesBasicTableResult {
        val adapter = resolve(session)
        if (request.expectedFingerprint.isBlank() || request.previewToken.isBlank()) invalid("缺少结构指纹或预览令牌")
        if (request.command.operation == TimeSeriesBasicTableOperation.DROP_COLUMN && request.confirmationName != request.command.name) {
            throw TimeSeriesBasicTableLifecycleException("DESTRUCTIVE_CONFIRMATION_MISMATCH", "确认名称必须与字段名完全一致")
        }
        val snapshot = try {
            adapter.inspectBasicTable(session, database, table)
        } catch (error: IllegalArgumentException) {
            throw TimeSeriesBasicTableLifecycleException("OBJECT_CHANGED", "普通表已不存在或结构已变化，请重新预览", error)
        }
        if (snapshot.fingerprint != request.expectedFingerprint) {
            throw TimeSeriesBasicTableLifecycleException("OBJECT_CHANGED", "普通表结构已变化，请重新预览")
        }
        val ddl = build(adapter, database, table, snapshot, request.command)
        if (token(snapshot.fingerprint, request.command, ddl) != request.previewToken) invalid("命令与已预览内容不一致")
        try {
            session.getJdbcConnection().createStatement().use { it.execute(ddl) }
        } catch (error: SQLException) {
            throw TimeSeriesBasicTableLifecycleException("TIME_SERIES_MUTATION_FAILED", error.message ?: "修改普通表结构失败", error)
        }
        return TimeSeriesBasicTableResult(true, request.command, ddl, snapshot.fingerprint)
    }

    private fun resolve(session: DatabaseSession): TimeSeriesBasicTableLifecycleAdapter {
        val database = registry.get(session.config.dbType)
        if (!database.capabilities().supportsTimeSeriesBasicTableLifecycle) unsupported(session)
        return database.timeSeriesBasicTableLifecycleAdapter() ?: unsupported(session)
    }

    private fun build(adapter: TimeSeriesBasicTableLifecycleAdapter, database: String, table: String, snapshot: TimeSeriesBasicTableSnapshot, command: TimeSeriesBasicTableCommand) =
        wrapInvalid { adapter.buildBasicTableMutationSql(database, table, snapshot, command) }

    private fun <T> wrapInvalid(block: () -> T): T = try { block() } catch (error: IllegalArgumentException) {
        throw TimeSeriesBasicTableLifecycleException("INVALID_LIFECYCLE_COMMAND", error.message ?: "普通表结构命令无效", error)
    } catch (error: SQLException) {
        throw TimeSeriesBasicTableLifecycleException("TIME_SERIES_MUTATION_FAILED", error.message ?: "读取普通表结构失败", error)
    }

    private fun token(fingerprint: String, command: TimeSeriesBasicTableCommand, ddl: String): String = digest("$fingerprint|$command|$ddl")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun invalid(message: String): Nothing = throw TimeSeriesBasicTableLifecycleException("INVALID_LIFECYCLE_COMMAND", message)
    private fun unsupported(session: DatabaseSession): Nothing = throw TimeSeriesBasicTableLifecycleException("UNSUPPORTED_DB_FEATURE", "当前数据库类型（${session.config.dbType}）不支持时序普通表结构管理")
}

internal class TimeSeriesBasicTableLifecycleException(val code: String, override val message: String, cause: Throwable? = null) : RuntimeException(message, cause)
