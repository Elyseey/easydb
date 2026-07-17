/*
 * Copyright (c) 2024-2026 EasyDB Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.easydb.launcher

import com.easydb.api.fail
import com.easydb.api.ok
import com.easydb.common.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

/**
 * 慢查询分析路由
 *
 * 路由挂载点：/api/slow-query
 *
 * 通过 adapterRegistry 按 dbType 获取 SlowQueryAnalyzer。
 * 如果数据库不支持慢查询分析（analyzer 为 null），返回 UNSUPPORTED_DB_FEATURE 错误。
 */
fun Route.slowQueryRoutes() {
    val connMgr = ServiceRegistry.connectionManager
    val adapterRegistry = ServiceRegistry.adapterRegistry

    /**
     * 公共辅助：根据 connectionId query 参数获取会话，失败时写 fail 响应并返回 null
     */
    suspend fun getSession(call: ApplicationCall): DatabaseSession? {
        val connectionId = call.request.queryParameters["connectionId"]
            ?: run { call.fail("INVALID_REQUEST", "缺少 connectionId 参数"); return null }
        return connMgr.getSession(connectionId)
            ?: run { call.fail("NOT_CONNECTED", "连接未打开，请先打开连接（connectionId=$connectionId）"); null }
    }

    /**
     * 获取慢查询分析器，不支持时返回 null 并写入 fail 响应
     */
    suspend fun getAnalyzer(session: DatabaseSession, call: ApplicationCall): SlowQueryAnalyzer? {
        val analyzer = try {
            adapterRegistry.get(session.config.dbType).slowQueryAnalyzer()
        } catch (_: UnsupportedOperationException) {
            null
        }
        if (analyzer == null) {
            call.fail("UNSUPPORTED_DB_FEATURE", "当前数据库类型（${session.config.dbType}）不支持慢查询分析")
            return null
        }
        return analyzer
    }

    // ── GET /status?connectionId= ────────────────────────────
    get("/status") {
        val session = getSession(call) ?: return@get
        val analyzer = getAnalyzer(session, call) ?: return@get

        val capability = runCatching { analyzer.checkCapability(session) }
            .getOrElse { e ->
                call.fail("CAPABILITY_CHECK_FAILED", "能力探测失败: ${e.message}")
                return@get
            }
        call.ok(capability)
    }

    // ── POST /digests/query ───────────────────────────────────
    post("/digests/query") {
        val req = runCatching { call.receive<SlowQueryQueryRequest>() }
            .getOrElse { e ->
                call.fail("INVALID_REQUEST", "请求体解析失败: ${e.message}")
                return@post
            }

        val session = connMgr.getSession(req.connectionId)
            ?: return@post call.fail("NOT_CONNECTED", "连接未打开（connectionId=${req.connectionId}）")

        val analyzer = getAnalyzer(session, call) ?: return@post

        val result = runCatching { analyzer.queryDigests(session, req) }
            .getOrElse { e ->
                call.fail("QUERY_FAILED", "Digest 列表查询失败: ${e.message}")
                return@post
            }
        call.ok(result)
    }

    // ── GET /digests/{digest}/samples?connectionId=&limit= ───
    get("/digests/{digest}/samples") {
        val digest = call.parameters["digest"]
            ?: return@get call.fail("INVALID_REQUEST", "缺少 digest 路径参数")
        val session = getSession(call) ?: return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val analyzer = getAnalyzer(session, call) ?: return@get

        val samples = runCatching { analyzer.getSamples(session, digest, limit) }
            .getOrElse { e ->
                call.fail("SAMPLE_QUERY_FAILED", "样本 SQL 查询失败: ${e.message}")
                return@get
            }
        call.ok(samples)
    }

    // ── POST /explain ─────────────────────────────────────────
    post("/explain") {
        val req = runCatching { call.receive<ExplainRequest>() }
            .getOrElse { e ->
                call.fail("INVALID_REQUEST", "请求体解析失败: ${e.message}")
                return@post
            }

        val session = connMgr.getSession(req.connectionId)
            ?: return@post call.fail("NOT_CONNECTED", "连接未打开（connectionId=${req.connectionId}）")

        val analyzer = getAnalyzer(session, call) ?: return@post

        val result = runCatching { analyzer.explain(session, req.database, req.sql, req.format) }
            .getOrElse { e ->
                call.fail("EXPLAIN_FAILED", "EXPLAIN 执行失败: ${e.message}")
                return@post
            }

        // EXPLAIN 执行失败（内部 success=false）仍返回 HTTP 200 + ok，由前端处理降级
        call.ok(result)
    }

    // ── POST /advise ──────────────────────────────────────────
    post("/advise") {
        val req = runCatching { call.receive<AdviseRequest>() }
            .getOrElse { e ->
                call.fail("INVALID_REQUEST", "请求体解析失败: ${e.message}")
                return@post
            }

        val session = connMgr.getSession(req.connectionId)
            ?: return@post call.fail("NOT_CONNECTED", "连接未打开（connectionId=${req.connectionId}）")

        val analyzer = getAnalyzer(session, call) ?: return@post

        val advices = runCatching { analyzer.advise(session, req.sql, req.explainResult) }
            .getOrElse { e ->
                call.fail("ADVISE_FAILED", "规则诊断失败: ${e.message}")
                return@post
            }
        call.ok(advices)
    }
}
