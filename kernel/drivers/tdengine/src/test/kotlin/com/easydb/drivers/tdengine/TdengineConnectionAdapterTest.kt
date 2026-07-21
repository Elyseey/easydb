package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionConfig
import com.easydb.common.SslConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TdengineConnectionAdapterTest {

    @Test
    fun `builds websocket url without embedding credentials`() {
        val config = connectionConfig(password = "not-in-url", database = "power")

        val url = TdengineConnectionAdapter.buildJdbcUrl(config)

        assertEquals("jdbc:TAOS-WS://127.0.0.1:6041/power", url)
        assertFalse(url.contains(config.password))
        assertFalse(url.contains("user="))
    }

    @Test
    fun `supports ipv6 host and empty database`() {
        assertEquals(
            "jdbc:TAOS-WS://[::1]:6041/",
            TdengineConnectionAdapter.buildJdbcUrl(
                connectionConfig(host = "::1", database = null)
            )
        )
    }

    @Test
    fun `builds rest url with the same connection target`() {
        assertEquals(
            "jdbc:TAOS-RS://127.0.0.1:6041/power",
            TdengineConnectionAdapter.buildJdbcUrl(
                connectionConfig(),
                TdengineJdbcProtocol.REST
            )
        )
    }

    @Test
    fun `builds rest health url for ipv4 ipv6 and tls`() {
        assertEquals(
            "http://127.0.0.1:6041/-/ping",
            TdengineConnectionAdapter.buildRestHealthUrl(connectionConfig())
        )
        assertEquals(
            "https://[::1]:6041/-/ping",
            TdengineConnectionAdapter.buildRestHealthUrl(
                connectionConfig(host = "::1").copy(ssl = SslConfig(enabled = true))
            )
        )
    }

    @Test
    fun `rejects multi endpoint and url delimiter in database`() {
        assertFailsWith<IllegalArgumentException> {
            TdengineConnectionAdapter.buildJdbcUrl(
                connectionConfig(host = "taos-1,taos-2")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TdengineConnectionAdapter.buildJdbcUrl(
                connectionConfig(database = "power?user=other")
            )
        }
    }

    @Test
    fun `redacts password from unknown error message`() {
        val message = translateTdengineException(
            SQLException("connection failed with password unit-test-secret"),
            "unit-test-secret"
        )

        assertFalse(message.contains("unit-test-secret"))
    }

    @Test
    fun `open falls back to rest when websocket endpoint is unavailable`() {
        val restConnection = mockk<Connection>(relaxed = true)
        val attempts = mutableListOf<TdengineJdbcProtocol>()
        val adapter = TdengineConnectionAdapter { _, protocol ->
            attempts += protocol
            when (protocol) {
                TdengineJdbcProtocol.WEBSOCKET ->
                    throw SQLException("WebSocket handshake failed with HTTP status 404")

                TdengineJdbcProtocol.REST -> restConnection
            }
        }

        val session = adapter.open(connectionConfig())

        assertContentEquals(
            listOf(TdengineJdbcProtocol.WEBSOCKET, TdengineJdbcProtocol.REST),
            attempts
        )
        assertSame(restConnection, session.getJdbcConnection())
        adapter.close(session)
        verify(exactly = 1) { restConnection.close() }
    }

    @Test
    fun `websocket success does not open rest`() {
        val webSocketConnection = mockk<Connection>(relaxed = true)
        val attempts = mutableListOf<TdengineJdbcProtocol>()
        val adapter = TdengineConnectionAdapter { _, protocol ->
            attempts += protocol
            webSocketConnection
        }

        val session = adapter.open(connectionConfig())

        assertContentEquals(listOf(TdengineJdbcProtocol.WEBSOCKET), attempts)
        assertSame(webSocketConnection, session.getJdbcConnection())
        adapter.close(session)
    }

    @Test
    fun `test connection shares websocket to rest fallback`() {
        val restConnection = validConnection()
        val attempts = mutableListOf<TdengineJdbcProtocol>()
        val adapter = TdengineConnectionAdapter { _, protocol ->
            attempts += protocol
            if (protocol == TdengineJdbcProtocol.WEBSOCKET) {
                throw SQLException("server returned HTTP response code: 405 during WebSocket upgrade")
            }
            restConnection
        }

        val result = adapter.testConnection(connectionConfig())

        assertTrue(result.success)
        assertContentEquals(
            listOf(TdengineJdbcProtocol.WEBSOCKET, TdengineJdbcProtocol.REST),
            attempts
        )
        verify(exactly = 1) { restConnection.close() }
    }

    @Test
    fun `aggregate websocket timeout falls back only when rest endpoint is available`() {
        val restConnection = validConnection()
        val attempts = mutableListOf<TdengineJdbcProtocol>()
        var probeCalls = 0
        val adapter = TdengineConnectionAdapter(
            connectionOpener = { _, protocol ->
                attempts += protocol
                if (protocol == TdengineJdbcProtocol.WEBSOCKET) {
                    throw SQLException(
                        "ERROR (0x231d): can't create connection with any server within: 5000 milliseconds"
                    )
                }
                restConnection
            },
            restEndpointProbe = {
                probeCalls += 1
                true
            }
        )

        val result = adapter.testConnection(connectionConfig())

        assertTrue(result.success)
        assertEquals(1, probeCalls)
        assertContentEquals(
            listOf(TdengineJdbcProtocol.WEBSOCKET, TdengineJdbcProtocol.REST),
            attempts
        )
    }

    @Test
    fun `aggregate websocket timeout keeps original failure when rest endpoint is unavailable`() {
        val attempts = mutableListOf<TdengineJdbcProtocol>()
        var probeCalls = 0
        val adapter = TdengineConnectionAdapter(
            connectionOpener = { _, protocol ->
                attempts += protocol
                throw SQLException(
                    "ERROR (0x231d): can't create connection with any server within: 5000 milliseconds"
                )
            },
            restEndpointProbe = {
                probeCalls += 1
                false
            }
        )

        val result = adapter.testConnection(connectionConfig())

        assertFalse(result.success)
        assertEquals("TDengine 连接超时，请检查主机、6041 端口和 taosAdapter 状态", result.message)
        assertEquals(1, probeCalls)
        assertContentEquals(listOf(TdengineJdbcProtocol.WEBSOCKET), attempts)
    }

    @Test
    fun `validates connection without relying on driver generated column label`() {
        val connection = validConnection()

        assertTrue(validateTdengineConnection(connection))
    }

    @Test
    fun `does not fall back for ordinary connection failures`() {
        val errors = listOf(
            "WebSocket authentication failed with HTTP status 401",
            "database not exist",
            "SSL certificate rejected",
            "connection timed out",
            "connection refused"
        )

        errors.forEach { errorMessage ->
            val attempts = mutableListOf<TdengineJdbcProtocol>()
            val adapter = TdengineConnectionAdapter { _, protocol ->
                attempts += protocol
                throw SQLException(errorMessage)
            }

            val result = adapter.testConnection(connectionConfig())

            assertFalse(result.success)
            assertContentEquals(listOf(TdengineJdbcProtocol.WEBSOCKET), attempts)
        }
    }

    @Test
    fun `reports a sanitized error when websocket and rest both fail`() {
        val password = "unit-test-secret"
        val adapter = TdengineConnectionAdapter { _, protocol ->
            when (protocol) {
                TdengineJdbcProtocol.WEBSOCKET ->
                    throw SQLException("WebSocket handshake failed with HTTP status 501")

                TdengineJdbcProtocol.REST ->
                    throw SQLException("REST authentication failed for password $password")
            }
        }

        val result = adapter.testConnection(connectionConfig(password = password))

        assertFalse(result.success)
        assertContains(result.message, "TDengine WebSocket 接口不可用，REST 回退也失败")
        assertContains(result.message, "TDengine 认证失败")
        assertFalse(result.message.contains(password))
    }

    @Test
    fun `recognizes only explicit unsupported websocket endpoint statuses`() {
        listOf(404, 405, 501).forEach { status ->
            assertTrue(
                shouldFallbackToRest(
                    SQLException("WebSocket handshake failed with HTTP status $status")
                )
            )
        }
        assertTrue(
            shouldFallbackToRest(
                SQLException(
                    "connection failed",
                    IllegalStateException(
                        "Handshake failed: Invalid handshake response getStatus: 404 Not Found"
                    )
                )
            )
        )

        listOf(400, 401, 403, 500, 503).forEach { status ->
            assertFalse(
                shouldFallbackToRest(
                    SQLException("WebSocket handshake failed with HTTP status $status")
                )
            )
        }
        assertFalse(
            shouldFallbackToRest(
                SQLException("WebSocket login failed with database error code 404")
            )
        )
        assertFalse(shouldFallbackToRest(SQLException("database not found")))
    }

    @Test
    fun `translates common websocket connection failures`() {
        assertEquals(
            "TDengine 连接超时，请检查主机、6041 端口和 taosAdapter 状态",
            translateTdengineException(SQLException("connection timed out"))
        )
        assertEquals(
            "TDengine 认证失败，请检查用户名或密码",
            translateTdengineException(SQLException("invalid user or password"))
        )
        assertEquals(
            "TDengine 默认数据库不存在或当前用户无权访问",
            translateTdengineException(SQLException("database not exist"))
        )
        assertEquals(
            "TDengine TLS 握手失败，请检查证书与服务端 TLS 配置",
            translateTdengineException(SQLException("SSL certificate rejected"))
        )
        assertEquals(
            "无法连接 TDengine，请检查服务、主机、6041 端口和网络",
            translateTdengineException(SQLException("connection refused"))
        )
    }

    private fun connectionConfig(
        host: String = "127.0.0.1",
        password: String = "secret",
        database: String? = "power"
    ) = ConnectionConfig(
        id = "td-test",
        name = "TDengine Test",
        dbType = "tdengine",
        host = host,
        port = 6041,
        username = "easydb_test",
        password = password,
        database = database
    )

    private fun validConnection(): Connection {
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<Statement>(relaxed = true)
        val result = mockk<ResultSet>(relaxed = true)
        every { connection.isClosed } returns false
        every { connection.createStatement() } returns statement
        every { statement.executeQuery("SELECT 1") } returns result
        every { result.next() } returns true
        return connection
    }
}
