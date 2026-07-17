package com.easydb.launcher

import com.easydb.api.fail
import com.easydb.api.ok
import com.easydb.common.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * 存储过程 / 函数执行路由。
 *
 * - POST /api/procedure/inspect  → 查询参数元数据（名称、类型、方向）
 * - POST /api/procedure/execute  → 执行过程/函数，返回 OUT 参数 + 多结果集
 *
 * 路由通过 adapterRegistry 按 dbType 获取适配器，
 * 如果适配器不支持存储过程，返回 UNSUPPORTED_DB_FEATURE 错误。
 */
fun Route.procedureRoutes() {
    val connMgr        = ServiceRegistry.connectionManager
    val adapterRegistry = ServiceRegistry.adapterRegistry
    val executeService = com.easydb.common.ProcedureExecuteService()

    /** 根据 session 的 dbType 获取对应的 ProcedureAdapter，不支持时返回 null */
    fun procedureAdapterFor(session: DatabaseSession): ProcedureAdapter? {
        return try {
            adapterRegistry.get(session.config.dbType).procedureAdapter()
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    // ─── POST /api/procedure/inspect ──────────────────────────────

    /**
     * 查询存储过程或函数的参数元数据。
     * 请求体：{ connectionId, database, name, type }
     * 响应：ProcedureInspectResult（参数列表 + ddl + definer + comment）
     */
    post("/inspect") {
        val body = call.receive<kotlinx.serialization.json.JsonObject>()

        fun kotlinx.serialization.json.JsonObject.str(key: String) =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

        val connectionId = body.str("connectionId")
        val database     = body.str("database")
        val name         = body.str("name")
        val type         = body.str("type").ifBlank { "PROCEDURE" }.uppercase()

        if (connectionId.isBlank() || database.isBlank() || name.isBlank()) {
            call.fail("INVALID_REQUEST", "缺少必要参数：connectionId / database / name")
            return@post
        }

        val session = connMgr.getPrimarySession(connectionId)
            ?: return@post call.fail("NOT_CONNECTED", "连接未激活，请先打开连接")

        val procedureAdapter = procedureAdapterFor(session)
            ?: return@post call.fail("UNSUPPORTED_DB_FEATURE", "当前数据库类型（${session.config.dbType}）不支持存储过程功能")

        try {
            val result = procedureAdapter.inspect(session, database, name, type)
            call.ok(result)
        } catch (e: Exception) {
            call.fail("INSPECT_FAILED", e.message ?: "获取参数元数据失败")
        }
    }

    // ─── POST /api/procedure/execute ──────────────────────────────

    /**
     * 执行存储过程或函数。
     * 请求体：ProcedureExecuteRequest
     * 响应：ProcedureExecuteResult（outParams + resultSets + duration）
     */
    post("/execute") {
        val request = call.receive<ProcedureExecuteRequest>()

        val session = connMgr.getPrimarySession(request.connectionId)
            ?: return@post call.ok(
                ProcedureExecuteResult(success = false, duration = 0, error = "连接未激活，请先打开连接")
            )

        val procedureAdapter = procedureAdapterFor(session)
            ?: return@post call.ok(
                ProcedureExecuteResult(success = false, duration = 0, error = "当前数据库类型（${session.config.dbType}）不支持存储过程功能")
            )

        // 执行引擎：纯 JDBC 标准逻辑，适配器负责数据库特定 SQL
        val result = executeService.execute(procedureAdapter, session, request)
        call.ok(result)
    }
}
