package com.easydb.launcher

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesCreateDefinition
import com.easydb.common.TimeSeriesCreateKind
import com.easydb.common.TimeSeriesCreatePreview
import com.easydb.common.TimeSeriesCreateResult
import com.easydb.common.TimeSeriesParentNotFoundException
import java.sql.SQLException

internal class TimeSeriesObjectCreateService(
    private val adapterRegistry: DatabaseAdapterRegistry
) {
    fun preview(
        session: DatabaseSession,
        database: String,
        definition: TimeSeriesCreateDefinition
    ): TimeSeriesCreatePreview = TimeSeriesCreatePreview(
        ddl = buildCreateSql(session, database, definition)
    )

    fun create(
        session: DatabaseSession,
        database: String,
        definition: TimeSeriesCreateDefinition
    ): TimeSeriesCreateResult {
        val ddl = buildCreateSql(session, database, definition)
        try {
            session.getJdbcConnection().createStatement().use { statement ->
                statement.execute(ddl)
            }
        } catch (error: SQLException) {
            throw classifyTimeSeriesCreateFailure(error, definition.kind)
        }
        return TimeSeriesCreateResult(
            success = true,
            ddl = ddl,
            kind = definition.kind,
            name = definition.name,
            stableName = definition.stableName
        )
    }

    private fun buildCreateSql(
        session: DatabaseSession,
        database: String,
        definition: TimeSeriesCreateDefinition
    ): String {
        val databaseAdapter = adapterRegistry.get(session.config.dbType)
        if (!databaseAdapter.capabilities().supportsTimeSeriesObjectCreate) {
            throw TimeSeriesObjectCreateException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）不支持时序对象创建"
            )
        }
        val objectAdapter = databaseAdapter.timeSeriesObjectAdapter()
            ?: throw TimeSeriesObjectCreateException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）未提供时序对象创建适配器"
            )
        return try {
            objectAdapter.buildCreateSql(session, database, definition)
        } catch (error: TimeSeriesParentNotFoundException) {
            throw TimeSeriesObjectCreateException(
                code = "TIME_SERIES_PARENT_NOT_FOUND",
                message = error.message ?: "父超级表不存在或不可访问",
                cause = error
            )
        } catch (error: SQLException) {
            throw classifyTimeSeriesCreateFailure(error, definition.kind)
        } catch (error: IllegalArgumentException) {
            throw TimeSeriesObjectCreateException(
                code = "INVALID_TIME_SERIES_DEFINITION",
                message = error.message ?: "时序对象定义无效",
                cause = error
            )
        }
    }

}

internal fun classifyTimeSeriesCreateFailure(
    error: SQLException,
    kind: TimeSeriesCreateKind
): TimeSeriesObjectCreateException {
    val message = error.message.orEmpty()
    val normalized = message.lowercase()
    val code = when {
        normalized.containsAny("already exists", "already exist", "object exists") ->
            "TIME_SERIES_OBJECT_ALREADY_EXISTS"

        normalized.containsAny("permission", "privilege", "access denied", "not authorized") ->
            "TIME_SERIES_PERMISSION_DENIED"

        kind == TimeSeriesCreateKind.CHILD_TABLE &&
            normalized.containsAny("not exist", "doesn't exist", "does not exist", "invalid table name") ->
            "TIME_SERIES_PARENT_NOT_FOUND"

        else -> "CREATE_TIME_SERIES_OBJECT_FAILED"
    }
    val fallback = when (code) {
        "TIME_SERIES_OBJECT_ALREADY_EXISTS" -> "同名时序对象已存在"
        "TIME_SERIES_PERMISSION_DENIED" -> "当前账号没有创建时序对象的权限"
        "TIME_SERIES_PARENT_NOT_FOUND" -> "父超级表不存在或不可访问"
        else -> "创建时序对象失败"
    }
    return TimeSeriesObjectCreateException(code, message.ifBlank { fallback }, error)
}

private fun String.containsAny(vararg fragments: String): Boolean =
    fragments.any(::contains)

internal class TimeSeriesObjectCreateException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
