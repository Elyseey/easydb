package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionAdapter
import com.easydb.common.ConnectionConfig
import com.easydb.common.ConnectionTestResult
import com.easydb.common.DatabaseSession
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class TdengineConnectionAdapter : ConnectionAdapter {

    override fun testConnection(config: ConnectionConfig): ConnectionTestResult {
        val startedAt = System.currentTimeMillis()
        return try {
            createJdbcConnection(config).use { connection ->
                val valid = connection.isValid(5)
                if (valid) {
                    ConnectionTestResult(
                        success = true,
                        message = "连接成功",
                        latencyMs = System.currentTimeMillis() - startedAt
                    )
                } else {
                    ConnectionTestResult(success = false, message = "TDengine 连接验证失败")
                }
            }
        } catch (error: Exception) {
            ConnectionTestResult(success = false, message = translateTdengineException(error, config.password))
        }
    }

    override fun open(config: ConnectionConfig): DatabaseSession = try {
        TdengineDatabaseSession(
            connectionId = config.id,
            config = config,
            connection = createJdbcConnection(config)
        )
    } catch (error: Exception) {
        throw RuntimeException(translateTdengineException(error, config.password), error)
    }

    override fun close(session: DatabaseSession) {
        session.close()
    }

    companion object {
        private const val DRIVER_CLASS = "com.taosdata.jdbc.ws.WebSocketDriver"
        private const val DEFAULT_PORT = 6041

        internal fun buildJdbcUrl(config: ConnectionConfig): String {
            val host = config.host.trim()
            require(host.isNotEmpty()) { "TDengine 主机不能为空" }
            require(',' !in host) { "TDengine MVP 暂不支持多 endpoint，请填写单个主机" }

            val port = if (config.port == 0) DEFAULT_PORT else config.port
            require(port in 1..65535) { "TDengine 端口必须在 1 到 65535 之间" }

            val formattedHost = when {
                host.startsWith("[") && host.endsWith("]") -> host
                ':' in host -> "[$host]"
                else -> host
            }
            val database = config.database?.trim()?.takeIf { it.isNotEmpty() }
            if (database != null) {
                require(database.none { it in "/?#&\r\n" }) {
                    "TDengine 默认数据库名包含连接 URL 不支持的字符"
                }
            }

            return buildString {
                append("jdbc:TAOS-WS://")
                append(formattedHost)
                append(':')
                append(port)
                append('/')
                if (database != null) append(database)
            }
        }

        internal fun createJdbcConnection(config: ConnectionConfig): Connection {
            Class.forName(DRIVER_CLASS)
            val properties = Properties().apply {
                setProperty("user", config.username)
                setProperty("password", config.password)
                setProperty("httpConnectTimeout", "5000")
                setProperty("messageWaitTimeout", "300000")
                setProperty("varcharAsString", "true")

                config.ssl?.takeIf { it.enabled }?.let { ssl ->
                    setProperty("useSSL", "true")
                    setProperty("disableSSLCertValidation", (!ssl.rejectUnauthorized).toString())
                }
            }
            return DriverManager.getConnection(buildJdbcUrl(config), properties)
        }
    }
}

internal fun translateTdengineException(error: Exception, password: String = ""): String {
    val rawMessage = sequenceOf(error.message, error.cause?.message)
        .filterNotNull()
        .joinToString(" ")
        .replace(password.takeIf { it.isNotEmpty() } ?: "\u0000", "<redacted>")
    val firstLine = rawMessage.lineSequence().firstOrNull()?.trim().orEmpty()

    return when {
        error is ClassNotFoundException || rawMessage.contains("ClassNotFoundException", ignoreCase = true) ->
            "TDengine JDBC 驱动未找到，请检查应用打包配置"

        rawMessage.contains("timeout", ignoreCase = true) ||
            rawMessage.contains("timed out", ignoreCase = true) ->
            "TDengine 连接超时，请检查主机、6041 端口和 taosAdapter 状态"

        rawMessage.contains("authentication", ignoreCase = true) ||
            rawMessage.contains("user or password", ignoreCase = true) ||
            rawMessage.contains("invalid user", ignoreCase = true) ->
            "TDengine 认证失败，请检查用户名或密码"

        rawMessage.contains("database not", ignoreCase = true) ||
            rawMessage.contains("invalid database", ignoreCase = true) ->
            "TDengine 默认数据库不存在或当前用户无权访问"

        rawMessage.contains("ssl", ignoreCase = true) ||
            rawMessage.contains("certificate", ignoreCase = true) ->
            "TDengine TLS 握手失败，请检查证书与服务端 TLS 配置"

        rawMessage.contains("connection refused", ignoreCase = true) ||
            rawMessage.contains("connect failed", ignoreCase = true) ||
            rawMessage.contains("failed to connect", ignoreCase = true) ->
            "无法连接 TDengine，请检查服务、主机、6041 端口和网络"

        firstLine.isNotEmpty() -> firstLine
        else -> "TDengine 连接失败，请检查连接配置"
    }
}
