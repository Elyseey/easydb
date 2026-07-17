package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionConfig
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
}
