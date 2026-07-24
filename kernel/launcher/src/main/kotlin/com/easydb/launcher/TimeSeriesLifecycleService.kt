package com.easydb.launcher

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesChildPropertyApplyRequest
import com.easydb.common.TimeSeriesChildPropertyCommand
import com.easydb.common.TimeSeriesChildPropertyPreview
import com.easydb.common.TimeSeriesChildPropertyResult
import com.easydb.common.TimeSeriesChildPropertySnapshot
import com.easydb.common.TimeSeriesDeleteApplyRequest
import com.easydb.common.TimeSeriesDeleteObjectKind
import com.easydb.common.TimeSeriesDeletePreview
import com.easydb.common.TimeSeriesDeleteResult
import com.easydb.common.TimeSeriesDeleteSnapshot
import com.easydb.common.TimeSeriesLifecycleAdapter
import com.easydb.common.TimeSeriesLifecycleApplyRequest
import com.easydb.common.TimeSeriesLifecycleCommand
import com.easydb.common.TimeSeriesLifecycleOperation
import com.easydb.common.TimeSeriesLifecyclePreview
import com.easydb.common.TimeSeriesLifecycleResult
import com.easydb.common.TimeSeriesLifecycleSnapshot
import java.security.MessageDigest
import java.sql.SQLException

internal class TimeSeriesLifecycleService(
    private val adapterRegistry: DatabaseAdapterRegistry
) {
    fun previewDelete(
        session: DatabaseSession,
        database: String,
        name: String
    ): TimeSeriesDeletePreview {
        val adapter = resolveDeleteAdapter(session)
        val snapshot = inspectDelete(adapter, session, database, name)
        val ddl = buildDeleteSql(adapter, database, name, snapshot)
        return TimeSeriesDeletePreview(
            snapshot = snapshot,
            ddl = ddl,
            previewToken = deletePreviewToken(snapshot.fingerprint, ddl),
            warnings = snapshot.deleteWarnings()
        )
    }

    fun applyDelete(
        session: DatabaseSession,
        database: String,
        name: String,
        request: TimeSeriesDeleteApplyRequest
    ): TimeSeriesDeleteResult {
        if (request.expectedFingerprint.isBlank() || request.previewToken.isBlank()) {
            throw TimeSeriesLifecycleException("INVALID_LIFECYCLE_COMMAND", "缺少有效的删除指纹或预览令牌")
        }
        if (request.confirmationName != name) {
            throw TimeSeriesLifecycleException(
                "DESTRUCTIVE_CONFIRMATION_MISMATCH",
                "确认名称必须与对象名完全一致"
            )
        }
        val adapter = resolveDeleteAdapter(session)
        val snapshot = try {
            adapter.inspectDelete(session, database, name)
        } catch (error: IllegalArgumentException) {
            throw TimeSeriesLifecycleException(
                "OBJECT_CHANGED",
                "对象已不存在或身份已变化，请重新预览",
                error
            )
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                "TIME_SERIES_MUTATION_FAILED",
                error.message ?: "重新读取对象身份失败",
                error
            )
        }
        if (snapshot.fingerprint != request.expectedFingerprint) {
            throw TimeSeriesLifecycleException("OBJECT_CHANGED", "对象身份或关联子表数量已变化，请重新预览")
        }
        val ddl = buildDeleteSql(adapter, database, name, snapshot)
        if (deletePreviewToken(snapshot.fingerprint, ddl) != request.previewToken) {
            throw TimeSeriesLifecycleException(
                "INVALID_LIFECYCLE_COMMAND",
                "删除命令与已预览内容不一致，请重新预览"
            )
        }
        try {
            session.getJdbcConnection().createStatement().use { statement -> statement.execute(ddl) }
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                "TIME_SERIES_MUTATION_FAILED",
                error.message ?: "删除时序对象失败",
                error
            )
        }
        return TimeSeriesDeleteResult(success = true, snapshot = snapshot, ddl = ddl)
    }

    fun inspect(
        session: DatabaseSession,
        database: String,
        stable: String
    ): TimeSeriesLifecycleSnapshot {
        val adapter = resolveAdapter(session)
        return try {
            adapter.inspect(session, database, stable)
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                code = "TIME_SERIES_MUTATION_FAILED",
                message = error.message ?: "读取超级表结构失败",
                cause = error
            )
        } catch (error: IllegalArgumentException) {
            throw invalidCommand(error)
        }
    }

    fun preview(
        session: DatabaseSession,
        database: String,
        stable: String,
        command: TimeSeriesLifecycleCommand
    ): TimeSeriesLifecyclePreview {
        val adapter = resolveAdapter(session)
        val snapshot = inspect(session, database, stable)
        val ddl = buildSql(adapter, database, stable, snapshot, command)
        return TimeSeriesLifecyclePreview(
            command = command,
            snapshot = snapshot,
            ddl = ddl,
            previewToken = previewToken(snapshot.fingerprint, command, ddl),
            destructive = command.operation.isDestructive(),
            warnings = command.operation.warnings()
        )
    }

    fun apply(
        session: DatabaseSession,
        database: String,
        stable: String,
        request: TimeSeriesLifecycleApplyRequest
    ): TimeSeriesLifecycleResult {
        requireApplyProof(request)
        val adapter = resolveAdapter(session)
        val snapshot = try {
            adapter.inspect(session, database, stable)
        } catch (error: IllegalArgumentException) {
            throw TimeSeriesLifecycleException(
                code = "OBJECT_CHANGED",
                message = "超级表已不存在或结构已变化，请重新预览",
                cause = error
            )
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                code = "TIME_SERIES_MUTATION_FAILED",
                message = error.message ?: "重新读取超级表结构失败",
                cause = error
            )
        }
        if (snapshot.fingerprint != request.expectedFingerprint) {
            throw TimeSeriesLifecycleException(
                code = "OBJECT_CHANGED",
                message = "超级表结构已变化，请刷新后重新预览"
            )
        }
        val ddl = buildSql(adapter, database, stable, snapshot, request.command)
        val expectedToken = previewToken(snapshot.fingerprint, request.command, ddl)
        if (expectedToken != request.previewToken) {
            throw TimeSeriesLifecycleException(
                code = "INVALID_LIFECYCLE_COMMAND",
                message = "执行命令与已预览内容不一致，请重新预览"
            )
        }

        try {
            session.getJdbcConnection().createStatement().use { statement ->
                statement.execute(ddl)
            }
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                code = "TIME_SERIES_MUTATION_FAILED",
                message = error.message ?: "修改超级表结构失败",
                cause = error
            )
        }
        return TimeSeriesLifecycleResult(
            success = true,
            command = request.command,
            ddl = ddl,
            previousFingerprint = snapshot.fingerprint
        )
    }

    fun inspectChild(
        session: DatabaseSession,
        database: String,
        table: String
    ): TimeSeriesChildPropertySnapshot {
        val adapter = resolveAdapter(session)
        return try {
            adapter.inspectChildProperties(session, database, table)
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                code = "TIME_SERIES_MUTATION_FAILED",
                message = error.message ?: "读取子表属性失败",
                cause = error
            )
        } catch (error: IllegalArgumentException) {
            throw invalidCommand(error)
        }
    }

    fun previewChild(
        session: DatabaseSession,
        database: String,
        table: String,
        command: TimeSeriesChildPropertyCommand
    ): TimeSeriesChildPropertyPreview {
        val adapter = resolveAdapter(session)
        val snapshot = inspectChild(session, database, table)
        val ddl = buildChildSql(adapter, database, table, snapshot, command)
        return TimeSeriesChildPropertyPreview(
            command = command,
            snapshot = snapshot,
            ddl = ddl,
            previewToken = childPreviewToken(snapshot.fingerprint, command, ddl)
        )
    }

    fun applyChild(
        session: DatabaseSession,
        database: String,
        table: String,
        request: TimeSeriesChildPropertyApplyRequest
    ): TimeSeriesChildPropertyResult {
        if (request.expectedFingerprint.isBlank() || request.previewToken.isBlank()) {
            throw TimeSeriesLifecycleException(
                code = "INVALID_LIFECYCLE_COMMAND",
                message = "缺少有效的属性指纹或预览令牌"
            )
        }
        val adapter = resolveAdapter(session)
        val snapshot = try {
            adapter.inspectChildProperties(session, database, table)
        } catch (error: IllegalArgumentException) {
            throw TimeSeriesLifecycleException(
                code = "OBJECT_CHANGED",
                message = "子表已不存在或属性已变化，请重新预览",
                cause = error
            )
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                code = "TIME_SERIES_MUTATION_FAILED",
                message = error.message ?: "重新读取子表属性失败",
                cause = error
            )
        }
        if (snapshot.fingerprint != request.expectedFingerprint) {
            throw TimeSeriesLifecycleException(
                code = "OBJECT_CHANGED",
                message = "子表属性已变化，请刷新后重新预览"
            )
        }
        val ddl = buildChildSql(adapter, database, table, snapshot, request.command)
        if (childPreviewToken(snapshot.fingerprint, request.command, ddl) != request.previewToken) {
            throw TimeSeriesLifecycleException(
                code = "INVALID_LIFECYCLE_COMMAND",
                message = "执行命令与已预览内容不一致，请重新预览"
            )
        }
        try {
            session.getJdbcConnection().createStatement().use { statement -> statement.execute(ddl) }
        } catch (error: SQLException) {
            throw TimeSeriesLifecycleException(
                code = "TIME_SERIES_MUTATION_FAILED",
                message = error.message ?: "修改子表属性失败",
                cause = error
            )
        }
        return TimeSeriesChildPropertyResult(
            success = true,
            command = request.command,
            ddl = ddl,
            previousFingerprint = snapshot.fingerprint
        )
    }

    private fun resolveAdapter(session: DatabaseSession): TimeSeriesLifecycleAdapter {
        val databaseAdapter = adapterRegistry.get(session.config.dbType)
        if (!databaseAdapter.capabilities().supportsTimeSeriesLifecycle) {
            throw TimeSeriesLifecycleException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）不支持时序结构生命周期管理"
            )
        }
        return databaseAdapter.timeSeriesLifecycleAdapter()
            ?: throw TimeSeriesLifecycleException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）未提供时序结构生命周期适配器"
            )
    }

    private fun resolveDeleteAdapter(session: DatabaseSession): TimeSeriesLifecycleAdapter {
        val databaseAdapter = adapterRegistry.get(session.config.dbType)
        if (!databaseAdapter.capabilities().supportsTimeSeriesObjectDelete) {
            throw TimeSeriesLifecycleException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）不支持删除时序对象"
            )
        }
        return databaseAdapter.timeSeriesLifecycleAdapter()
            ?: throw TimeSeriesLifecycleException(
                code = "UNSUPPORTED_DB_FEATURE",
                message = "当前数据库类型（${session.config.dbType}）未提供时序对象删除适配器"
            )
    }

    private fun inspectDelete(
        adapter: TimeSeriesLifecycleAdapter,
        session: DatabaseSession,
        database: String,
        name: String
    ): TimeSeriesDeleteSnapshot = try {
        adapter.inspectDelete(session, database, name)
    } catch (error: SQLException) {
        throw TimeSeriesLifecycleException(
            "TIME_SERIES_MUTATION_FAILED",
            error.message ?: "读取时序对象身份失败",
            error
        )
    } catch (error: IllegalArgumentException) {
        throw invalidCommand(error)
    }

    private fun buildDeleteSql(
        adapter: TimeSeriesLifecycleAdapter,
        database: String,
        name: String,
        snapshot: TimeSeriesDeleteSnapshot
    ): String = try {
        adapter.buildDeleteSql(database, name, snapshot)
    } catch (error: SQLException) {
        throw TimeSeriesLifecycleException(
            "TIME_SERIES_MUTATION_FAILED",
            error.message ?: "生成时序对象删除 DDL 失败",
            error
        )
    } catch (error: IllegalArgumentException) {
        throw invalidCommand(error)
    }

    private fun buildSql(
        adapter: TimeSeriesLifecycleAdapter,
        database: String,
        stable: String,
        snapshot: TimeSeriesLifecycleSnapshot,
        command: TimeSeriesLifecycleCommand
    ): String = try {
        adapter.buildMutationSql(database, stable, snapshot, command)
    } catch (error: SQLException) {
        throw TimeSeriesLifecycleException(
            code = "TIME_SERIES_MUTATION_FAILED",
            message = error.message ?: "生成超级表结构变更 DDL 失败",
            cause = error
        )
    } catch (error: IllegalArgumentException) {
        throw invalidCommand(error)
    }

    private fun buildChildSql(
        adapter: TimeSeriesLifecycleAdapter,
        database: String,
        table: String,
        snapshot: TimeSeriesChildPropertySnapshot,
        command: TimeSeriesChildPropertyCommand
    ): String = try {
        adapter.buildChildPropertyMutationSql(database, table, snapshot, command)
    } catch (error: SQLException) {
        throw TimeSeriesLifecycleException(
            code = "TIME_SERIES_MUTATION_FAILED",
            message = error.message ?: "生成子表属性变更 DDL 失败",
            cause = error
        )
    } catch (error: IllegalArgumentException) {
        throw invalidCommand(error)
    }

    private fun requireApplyProof(request: TimeSeriesLifecycleApplyRequest) {
        if (request.expectedFingerprint.isBlank() || request.previewToken.isBlank()) {
            throw TimeSeriesLifecycleException(
                code = "INVALID_LIFECYCLE_COMMAND",
                message = "缺少有效的结构指纹或预览令牌"
            )
        }
        if (request.command.operation.isDestructive()) {
            if (request.confirmationName != request.command.name) {
                throw TimeSeriesLifecycleException(
                    code = "DESTRUCTIVE_CONFIRMATION_MISMATCH",
                    message = "确认名称必须与待删除字段或 Tag 名完全一致"
                )
            }
        } else if (request.confirmationName != null) {
            throw TimeSeriesLifecycleException(
                code = "INVALID_LIFECYCLE_COMMAND",
                message = "非破坏性结构命令不能提供删除确认名称"
            )
        }
    }

    private fun invalidCommand(error: IllegalArgumentException) = TimeSeriesLifecycleException(
        code = "INVALID_LIFECYCLE_COMMAND",
        message = error.message ?: "时序生命周期命令无效",
        cause = error
    )

    private fun previewToken(
        fingerprint: String,
        command: TimeSeriesLifecycleCommand,
        ddl: String
    ): String {
        val canonical = buildString {
            appendPart(fingerprint)
            appendPart(command.operation.name)
            appendPart(command.name)
            appendPart(command.type?.name.orEmpty())
            appendPart(command.length?.toString().orEmpty())
            appendPart(command.newName.orEmpty())
            appendPart(ddl)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun childPreviewToken(
        fingerprint: String,
        command: TimeSeriesChildPropertyCommand,
        ddl: String
    ): String {
        val canonical = buildString {
            appendPart(fingerprint)
            appendPart(command.operation.name)
            appendPart(command.tagName.orEmpty())
            appendPart(if (command.value == null) "null" else "value:${command.value}")
            appendPart(command.isNull.toString())
            appendPart(command.ttl?.toString().orEmpty())
            appendPart(if (command.comment == null) "null" else "value:${command.comment}")
            appendPart(ddl)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun deletePreviewToken(fingerprint: String, ddl: String): String {
        val canonical = buildString {
            appendPart(fingerprint)
            appendPart(ddl)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun StringBuilder.appendPart(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    private fun TimeSeriesLifecycleOperation.isDestructive(): Boolean = when (this) {
        TimeSeriesLifecycleOperation.DROP_COLUMN,
        TimeSeriesLifecycleOperation.DROP_TAG -> true
        else -> false
    }

    private fun TimeSeriesLifecycleOperation.warnings(): List<String> = when (this) {
        TimeSeriesLifecycleOperation.DROP_COLUMN -> listOf("删除字段会永久删除该超级表全部子表中的对应列及数据")
        TimeSeriesLifecycleOperation.DROP_TAG -> listOf("删除 Tag 会永久删除该超级表全部子表中的对应 Tag 定义和值")
        else -> emptyList()
    }

    private fun TimeSeriesDeleteSnapshot.deleteWarnings(): List<String> = when (kind) {
        TimeSeriesDeleteObjectKind.SUPER_TABLE -> listOf(
            "删除超级表会永久删除其结构、全部 $affectedChildTables 个子表及所有数据"
        )
        TimeSeriesDeleteObjectKind.CHILD_TABLE -> listOf("删除子表会永久删除该子表及其全部数据")
        TimeSeriesDeleteObjectKind.BASIC_TABLE -> listOf("删除普通表会永久删除该表及其全部数据")
    }
}

internal class TimeSeriesLifecycleException(
    val code: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
