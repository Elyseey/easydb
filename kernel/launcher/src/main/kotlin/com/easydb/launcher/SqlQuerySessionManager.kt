package com.easydb.launcher

import com.easydb.common.ConnectionAdapter
import com.easydb.common.DatabaseSession
import com.easydb.common.DialectAdapter
import com.easydb.common.SqlResult
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * SQL 查询会话管理器 —— DBeaver 风格 LIMIT/OFFSET 分页。
 *
 * 核心思路：
 *   1. 用户 SQL 包装为分页子查询（由 DialectAdapter 生成分页 SQL）
 *   2. 每次 "加载更多" 只是 OFFSET 递增，重新执行一次轻量查询
 *   3. 连接可以自由复用（不像流式游标会锁连接）
 *   4. 总行数异步 COUNT(*)
 *
 * 对比旧方案（服务端游标 useCursorFetch=true）:
 *   旧：MySQL 先处理全部 200 万行 → 40 秒后才返回第一行
 *   新：MySQL 只扫描前 200 行 → 5ms 返回
 */
class SqlQuerySessionManager {

    companion object {
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 1000
        private const val MIN_CELL_CHARS = 128
        private const val MAX_CELL_CHARS = 16 * 1024
        private const val IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val QUERY_TIMEOUT_SECONDS = 120
        private const val TRUNCATED_SQL_CELL_SUFFIX = " …[truncated]"
        private const val MAX_RESULT_SQL_DISPLAY_LENGTH = 2000
    }

    private fun truncateSql(sql: String): String {
        return if (sql.length > MAX_RESULT_SQL_DISPLAY_LENGTH) {
            sql.take(MAX_RESULT_SQL_DISPLAY_LENGTH) + " …[SQL 已截断，原始长度 ${sql.length} 字符]"
        } else {
            sql
        }
    }

    private val sessions = ConcurrentHashMap<String, QuerySession>()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val countExecutor = Executors.newCachedThreadPool()

    /**
     * 连接池：按 connectionId + database 缓存 JDBC 连接。
     * LIMIT/OFFSET 模式不锁连接，可以安全复用。
     */
    private data class PoolKey(val connectionId: String, val database: String)
    private data class PooledConnection(val connection: Connection, val createdAt: Long = System.currentTimeMillis())
    private val connectionPool = ConcurrentHashMap<PoolKey, PooledConnection>()

    /**
     * 连接池保活线程：每 60 秒检查池中连接有效性
     * 失效连接自动移除，下次 acquireConnection 时重建
     */
    private val poolKeepAlive = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "query-pool-keepalive").apply { isDaemon = true }
    }.also { executor ->
        executor.scheduleAtFixedRate({
            for ((key, pooled) in connectionPool) {
                try {
                    if (pooled.connection.isClosed || !pooled.connection.isValid(2)) {
                        connectionPool.remove(key)
                        closeQuietly(pooled.connection)
                    }
                } catch (_: Exception) {
                    connectionPool.remove(key)
                    closeQuietly(pooled.connection)
                }
            }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun acquireConnection(
        session: DatabaseSession,
        database: String,
        connectionAdapter: ConnectionAdapter
    ): Connection {
        val key = PoolKey(session.connectionId, database)
        val pooled = connectionPool[key]

        if (pooled != null) {
            try {
                if (!pooled.connection.isClosed && pooled.connection.isValid(2)) {
                    return pooled.connection
                }
            } catch (_: Exception) { }
            connectionPool.remove(key)
            closeQuietly(pooled.connection)
        }

        val config = session.config.copy(database = database)
        val conn = connectionAdapter.open(config).getJdbcConnection()
        connectionPool[key] = PooledConnection(conn)
        return conn
    }

    private fun releasePooledConnections(connectionId: String) {
        connectionPool.entries
            .filter { it.key.connectionId == connectionId }
            .forEach { entry ->
                connectionPool.remove(entry.key)
                closeQuietly(entry.value.connection)
            }
    }

    // ─── 公开 API ──────────────────────────────────────────

    fun start(
        session: DatabaseSession,
        database: String,
        sql: String,
        pageSize: Int,
        maxCellChars: Int,
        connectionAdapter: ConnectionAdapter,
        dialect: DialectAdapter
    ): SqlResult {
        cleanupExpiredSessions()

        val safePageSize = pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val safeMaxCellChars = maxCellChars.coerceIn(MIN_CELL_CHARS, MAX_CELL_CHARS)
        val startAt = System.currentTimeMillis()
        val normalizedSql = sql.trim().trimEnd(';')

        return try {
            val conn = acquireConnection(session, database, connectionAdapter)

            dialect.buildSwitchDatabaseSql(database)?.let { switchSql ->
                conn.createStatement().use { stmt ->
                    stmt.execute(switchSql)
                }
            }

            val pagedSql = dialect.buildPaginationSql(normalizedSql, safePageSize + 1, 0)

            val page = executePagedQuery(conn, pagedSql, safePageSize, safeMaxCellChars)

            if (page.columns.isEmpty()) {
                return errorResult(sql, "当前语句没有可预览的结果集", System.currentTimeMillis() - startAt)
            }

            val sessionId = UUID.randomUUID().toString()
            val querySession = QuerySession(
                sessionId = sessionId,
                connectionId = session.connectionId,
                originalSql = normalizedSql,
                executedAt = now(),
                database = database,
                columns = page.columns,
                totalRows = null,
                offset = safePageSize.toLong(),
                loadedRows = page.rows.size.toLong(),
                hasMore = page.hasMore
            )

            if (page.hasMore) {
                sessions[sessionId] = querySession
                scheduleResultRowCount(querySession, session, database, normalizedSql, connectionAdapter, dialect)
            } else {
                querySession.totalRows = querySession.loadedRows
            }

            SqlResult(
                type = "query",
                columns = page.columns,
                rows = page.rows,
                preview = true,
                hasMore = page.hasMore,
                querySessionId = if (page.hasMore) sessionId else null,
                totalRows = querySession.totalRows,
                pageSize = safePageSize,
                loadedRows = page.rows.size,
                truncatedCellCount = page.truncatedCellCount,
                duration = System.currentTimeMillis() - startAt,
                sql = truncateSql(sql),
                executedAt = querySession.executedAt,
                warning = page.warning
            )
        } catch (e: Exception) {
            val key = PoolKey(session.connectionId, database)
            connectionPool.remove(key)?.let { closeQuietly(it.connection) }
            errorResult(truncateSql(sql), e.message ?: "SQL 预览异常", System.currentTimeMillis() - startAt)
        }
    }

    fun fetch(querySessionId: String, pageSize: Int, maxCellChars: Int, dialect: DialectAdapter): SqlResult {
        cleanupExpiredSessions()

        val session = sessions[querySessionId]
            ?: return errorResult("", "查询会话不存在或已过期", 0L)

        val safePageSize = pageSize.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val safeMaxCellChars = maxCellChars.coerceIn(MIN_CELL_CHARS, MAX_CELL_CHARS)
        val startAt = System.currentTimeMillis()

        return try {
            val poolKey = PoolKey(session.connectionId, session.database)
            val conn = connectionPool[poolKey]?.connection
                ?: return errorResult("", "数据库连接已断开", 0L)

            val pagedSql = dialect.buildPaginationSql(session.originalSql, safePageSize + 1, session.offset.toInt())
            val page = executePagedQuery(conn, pagedSql, safePageSize, safeMaxCellChars)

            session.offset += page.rows.size
            session.loadedRows += page.rows.size
            session.hasMore = page.hasMore
            session.lastAccessAt = System.currentTimeMillis()

            if (!page.hasMore) {
                if (session.totalRows == null) {
                    session.totalRows = session.loadedRows
                }
                sessions.remove(querySessionId)
            }

            SqlResult(
                type = "query",
                columns = session.columns,
                rows = page.rows,
                preview = true,
                hasMore = page.hasMore,
                querySessionId = querySessionId,
                totalRows = session.totalRows,
                pageSize = safePageSize,
                loadedRows = page.rows.size,
                truncatedCellCount = page.truncatedCellCount,
                duration = System.currentTimeMillis() - startAt,
                sql = session.originalSql,
                executedAt = session.executedAt,
                warning = page.warning
            )
        } catch (e: Exception) {
            sessions.remove(querySessionId)
            errorResult(session.originalSql, e.message ?: "加载更多失败", System.currentTimeMillis() - startAt)
        }
    }

    fun close(querySessionId: String) {
        cleanupExpiredSessions()
        sessions.remove(querySessionId)
    }

    fun getStatus(querySessionId: String): com.easydb.common.SqlQuerySessionStatus {
        cleanupExpiredSessions()
        val session = sessions[querySessionId]
            ?: return com.easydb.common.SqlQuerySessionStatus(
                querySessionId = querySessionId,
                exists = false
            )

        return com.easydb.common.SqlQuerySessionStatus(
            querySessionId = querySessionId,
            totalRows = session.totalRows,
            counting = session.counting,
            exists = true
        )
    }

    fun closeByConnectionId(connectionId: String) {
        cleanupExpiredSessions()
        sessions.entries
            .filter { it.value.connectionId == connectionId }
            .map { it.key }
            .forEach { sessions.remove(it) }
        releasePooledConnections(connectionId)
    }

    // ─── 内部实现 ──────────────────────────────────────────

    /**
     * 执行分页查询，返回当前页数据。
     * 双层防御：
     *  - 软上限：拉满 pageSize+1 行后立即停止迭代，正常 hasMore 流程；
     *    若发现继续 next() 仍能拿到第 pageSize+2 行（即驱动忽略了 LIMIT/FETCH），打 warning + 截断；
     *  - 硬上限：达到 pageSize*10 行仍未截断，立即抛错保护 JVM 内存。
     */
    private fun executePagedQuery(
        conn: Connection,
        pagedSql: String,
        pageSize: Int,
        maxCellChars: Int
    ): PageResult {
        conn.createStatement().use { stmt ->
            stmt.queryTimeout = QUERY_TIMEOUT_SECONDS
            val hasResult = stmt.execute(pagedSql)

            if (!hasResult) {
                return PageResult(columns = emptyList(), rows = emptyList(), hasMore = false, truncatedCellCount = 0)
            }

            stmt.resultSet.use { rs ->
                val meta = rs.metaData
                val columnCount = meta.columnCount
                val allColumns = (1..columnCount).map { meta.getColumnLabel(it) }
                val easydbPrefixes = setOf("_easydb_")
                val columns = allColumns.filter { col -> easydbPrefixes.none { col.startsWith(it) } }
                val easydbColumnIndices = allColumns.mapIndexedNotNull { idx, col ->
                    if (easydbPrefixes.any { col.startsWith(it) }) idx + 1 else null
                }.toSet()
                // Build mapping from filtered column name to original ResultSet index
                val columnToRsIndex = allColumns.mapIndexedNotNull { idx, col ->
                    if (idx + 1 !in easydbColumnIndices) col to (idx + 1) else null
                }
                val rows = mutableListOf<Map<String, String?>>()
                var truncatedCellCount = 0

                while (rs.next()) {
                    if (rows.size >= pageSize) {
                        // 软上限触发：尝试再 next 一次，若仍能拿数据说明驱动可能忽略了分页 SQL
                        val driverIgnoredPagination = try { rs.next() } catch (_: Exception) { false }
                        if (driverIgnoredPagination) {
                            // 继续探测到 pageSize*10 仍未结束 → 抛错
                            var probed = pageSize + 1
                            while (rs.next()) {
                                probed++
                                if (probed > pageSize * 10) {
                                    throw IllegalStateException(
                                        "Pagination strategy did not apply: driver returned >${pageSize * 10} rows; " +
                                            "check DialectAdapter.paginationStrategy is recognized by the database"
                                    )
                                }
                            }
                            return PageResult(
                                columns,
                                rows,
                                hasMore = true,
                                truncatedCellCount,
                                warning = "PAGINATION_STRATEGY_NOT_APPLIED"
                            )
                        }
                        return PageResult(columns, rows, hasMore = true, truncatedCellCount)
                    }

                    val row = linkedMapOf<String, String?>()
                    for ((column, rsIndex) in columnToRsIndex) {
                        val colType = meta.getColumnType(rsIndex)

                        if (colType in setOf(Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY)) {
                            val bytes = rs.getBytes(rsIndex)
                            if (bytes == null) {
                                row[column] = null
                            } else {
                                truncatedCellCount++
                                val sizeLabel = when {
                                    bytes.size < 1024 -> "${bytes.size} B"
                                    bytes.size < 1024 * 1024 -> "${bytes.size / 1024} KB"
                                    else -> "${bytes.size / (1024 * 1024)} MB"
                                }
                                row[column] = "[BLOB $sizeLabel]"
                            }
                            continue
                        }

                        val value = rs.getString(rsIndex)
                        row[column] = when {
                            value == null -> null
                            value.length > maxCellChars -> {
                                truncatedCellCount++
                                value.take(maxCellChars) + TRUNCATED_SQL_CELL_SUFFIX
                            }
                            else -> value
                        }
                    }
                    rows.add(row)
                }

                return PageResult(columns, rows, hasMore = false, truncatedCellCount)
            }
        }
    }

    private fun scheduleResultRowCount(
        session: QuerySession,
        dbSession: DatabaseSession,
        database: String,
        sql: String,
        connectionAdapter: ConnectionAdapter,
        dialect: DialectAdapter
    ) {
        if (session.counting || session.totalRows != null) return
        session.counting = true

        countExecutor.submit {
            try {
                val totalRows = countResultRows(dbSession, database, sql, connectionAdapter)
                if (totalRows != null) {
                    session.totalRows = totalRows
                }
            } finally {
                session.counting = false
            }
        }
    }

    private fun countResultRows(
        session: DatabaseSession,
        database: String,
        sql: String,
        connectionAdapter: ConnectionAdapter
    ): Long? {
        val normalizedSql = sql.trimStart()
        if (!normalizedSql.startsWith("select", ignoreCase = true) &&
            !normalizedSql.startsWith("with", ignoreCase = true)
        ) {
            return null
        }

        var dedicatedConn: Connection? = null
        return try {
            val config = session.config.copy(database = database)
            dedicatedConn = connectionAdapter.open(config).getJdbcConnection()
            dedicatedConn.createStatement().use { stmt ->
                stmt.queryTimeout = QUERY_TIMEOUT_SECONDS
                stmt.executeQuery(
                    "SELECT COUNT(*) FROM (${sql.trim().trimEnd(';')}) _easydb_count"
                ).use { rs ->
                    if (rs.next()) rs.getLong(1) else null
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            closeQuietly(dedicatedConn)
        }
    }

    private fun cleanupExpiredSessions() {
        val deadline = System.currentTimeMillis() - IDLE_TIMEOUT_MILLIS
        sessions.entries
            .filter { it.value.lastAccessAt < deadline }
            .map { it.key }
            .forEach { sessions.remove(it) }
    }

    private fun errorResult(sql: String, message: String, duration: Long): SqlResult = SqlResult(
        type = "error",
        duration = duration,
        sql = sql,
        executedAt = now(),
        error = message
    )

    private fun escapeIdentifier(value: String): String = value.replace("`", "``")

    private fun now(): String = LocalDateTime.now().format(timeFormatter)

    private fun closeQuietly(resource: AutoCloseable?) {
        try {
            resource?.close()
        } catch (_: Exception) {
        }
    }

    // ─── 数据类 ──────────────────────────────────────────

    private data class PageResult(
        val columns: List<String>,
        val rows: List<Map<String, String?>>,
        val hasMore: Boolean,
        val truncatedCellCount: Int,
        val warning: String? = null
    )

    /**
     * 查询会话 —— 轻量级，只保存 SQL 和分页偏移量。
     * 不再持有 Connection/Statement/ResultSet 引用，连接由池管理。
     */
    private data class QuerySession(
        val sessionId: String,
        val connectionId: String,
        val originalSql: String,
        val executedAt: String,
        val database: String,
        val columns: List<String>,
        @Volatile var totalRows: Long?,
        var offset: Long,
        var loadedRows: Long,
        var hasMore: Boolean,
        var lastAccessAt: Long = System.currentTimeMillis(),
        @Volatile var counting: Boolean = false
    )
}
