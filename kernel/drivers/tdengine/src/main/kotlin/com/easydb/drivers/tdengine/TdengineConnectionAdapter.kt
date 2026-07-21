package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionAdapter
import com.easydb.common.ConnectionConfig
import com.easydb.common.ConnectionTestResult
import com.easydb.common.DatabaseSession
import java.net.HttpURLConnection
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

internal enum class TdengineJdbcProtocol(
    val driverClass: String,
    val jdbcScheme: String
) {
    WEBSOCKET(
        driverClass = "com.taosdata.jdbc.ws.WebSocketDriver",
        jdbcScheme = "jdbc:TAOS-WS"
    ),
    REST(
        driverClass = "com.taosdata.jdbc.rs.RestfulDriver",
        jdbcScheme = "jdbc:TAOS-RS"
    )
}

internal fun interface TdengineJdbcConnectionOpener {
    fun open(config: ConnectionConfig, protocol: TdengineJdbcProtocol): Connection
}

internal fun interface TdengineRestEndpointProbe {
    fun isAvailable(config: ConnectionConfig): Boolean
}

class TdengineConnectionAdapter private constructor(
    private val connectionOpener: TdengineJdbcConnectionOpener,
    private val restEndpointProbe: TdengineRestEndpointProbe
) : ConnectionAdapter {

    constructor() : this(
        TdengineJdbcConnectionOpener { config, protocol ->
            createJdbcConnection(config, protocol)
        },
        TdengineRestEndpointProbe(::isRestEndpointAvailable)
    )

    internal constructor(
        connectionOpener: (ConnectionConfig, TdengineJdbcProtocol) -> Connection
    ) : this(connectionOpener, ::isRestEndpointAvailable)

    internal constructor(
        connectionOpener: (ConnectionConfig, TdengineJdbcProtocol) -> Connection,
        restEndpointProbe: (ConnectionConfig) -> Boolean
    ) : this(
        TdengineJdbcConnectionOpener(connectionOpener),
        TdengineRestEndpointProbe(restEndpointProbe)
    )

    override fun testConnection(config: ConnectionConfig): ConnectionTestResult {
        val startedAt = System.currentTimeMillis()
        return try {
            openCompatibleConnection(config).use { connection ->
                val valid = validateTdengineConnection(connection)
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
            connection = openCompatibleConnection(config)
        )
    } catch (error: Exception) {
        throw RuntimeException(translateTdengineException(error, config.password), error)
    }

    override fun close(session: DatabaseSession) {
        session.close()
    }

    private fun openCompatibleConnection(config: ConnectionConfig): Connection = try {
        connectionOpener.open(config, TdengineJdbcProtocol.WEBSOCKET)
    } catch (webSocketError: Exception) {
        val shouldFallback = shouldFallbackToRest(webSocketError) ||
            isTdengineWebSocketAggregateTimeout(webSocketError) &&
            runCatching { restEndpointProbe.isAvailable(config) }.getOrDefault(false)
        if (!shouldFallback) throw webSocketError

        try {
            connectionOpener.open(config, TdengineJdbcProtocol.REST)
        } catch (restError: Exception) {
            throw TdengineProtocolFallbackException(webSocketError, restError)
        }
    }

    companion object {
        private const val DEFAULT_PORT = 6041

        internal fun buildJdbcUrl(
            config: ConnectionConfig,
            protocol: TdengineJdbcProtocol = TdengineJdbcProtocol.WEBSOCKET
        ): String {
            val endpoint = resolveEndpoint(config)
            val database = config.database?.trim()?.takeIf { it.isNotEmpty() }
            if (database != null) {
                require(database.none { it in "/?#&\r\n" }) {
                    "TDengine 默认数据库名包含连接 URL 不支持的字符"
                }
            }

            return buildString {
                append(protocol.jdbcScheme)
                append("://")
                append(endpoint.formattedHost)
                append(':')
                append(endpoint.port)
                append('/')
                if (database != null) append(database)
            }
        }

        internal fun buildRestHealthUrl(config: ConnectionConfig): String {
            val endpoint = resolveEndpoint(config)
            val scheme = if (config.ssl?.enabled == true) "https" else "http"
            return "$scheme://${endpoint.formattedHost}:${endpoint.port}/-/ping"
        }

        private fun resolveEndpoint(config: ConnectionConfig): TdengineEndpoint {
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
            return TdengineEndpoint(formattedHost, port)
        }

        internal fun createJdbcConnection(
            config: ConnectionConfig,
            protocol: TdengineJdbcProtocol = TdengineJdbcProtocol.WEBSOCKET
        ): Connection {
            Class.forName(protocol.driverClass)
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
            return DriverManager.getConnection(buildJdbcUrl(config, protocol), properties)
        }
    }
}

private data class TdengineEndpoint(
    val formattedHost: String,
    val port: Int
)

internal class TdengineProtocolFallbackException(
    webSocketError: Exception,
    val restError: Exception
) : SQLException("TDengine WebSocket endpoint unavailable and REST fallback failed") {
    init {
        addSuppressed(webSocketError)
        addSuppressed(restError)
    }
}

internal fun shouldFallbackToRest(error: Exception): Boolean {
    val message = collectExceptionMessages(error)
    val hasWebSocketContext = message.contains("websocket", ignoreCase = true) ||
        message.contains("web socket", ignoreCase = true) ||
        message.contains("handshake", ignoreCase = true) ||
        message.contains("upgrade", ignoreCase = true) ||
        message.contains("/ws", ignoreCase = true) ||
        exceptionChain(error).any { throwable ->
            throwable.javaClass.simpleName.contains("websocket", ignoreCase = true) ||
                throwable.javaClass.simpleName.contains("handshake", ignoreCase = true)
        }
    val hasHttpStatusContext = message.contains("http", ignoreCase = true) ||
        message.contains("status", ignoreCase = true)
    val hasUnsupportedHttpStatus = UNSUPPORTED_WEBSOCKET_HTTP_STATUS.containsMatchIn(message)
    return hasWebSocketContext && hasHttpStatusContext && hasUnsupportedHttpStatus
}

internal fun isTdengineWebSocketAggregateTimeout(error: Exception): Boolean {
    val message = collectExceptionMessages(error)
    return message.contains("can't create connection with any server within", ignoreCase = true) ||
        message.contains("cannot create connection with any server within", ignoreCase = true)
}

internal fun isRestEndpointAvailable(config: ConnectionConfig): Boolean {
    var connection: HttpURLConnection? = null
    return try {
        connection = URI.create(TdengineConnectionAdapter.buildRestHealthUrl(config))
            .toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = REST_HEALTH_TIMEOUT_MS
        connection.readTimeout = REST_HEALTH_TIMEOUT_MS
        connection.useCaches = false
        connection.responseCode in 200..399
    } catch (_: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

internal fun validateTdengineConnection(connection: Connection): Boolean {
    if (connection.isClosed) return false
    return connection.createStatement().use { statement ->
        statement.executeQuery("SELECT 1").use { result -> result.next() }
    }
}

internal fun translateTdengineException(error: Exception, password: String = ""): String {
    if (error is TdengineProtocolFallbackException) {
        return "TDengine WebSocket 接口不可用，REST 回退也失败：" +
            translateTdengineException(error.restError, password)
    }

    val collectedMessage = collectExceptionMessages(error)
    val rawMessage = if (password.isEmpty()) {
        collectedMessage
    } else {
        collectedMessage.replace(password, "<redacted>")
    }
    val firstLine = rawMessage.lineSequence().firstOrNull()?.trim().orEmpty()

    return when {
        error is ClassNotFoundException || rawMessage.contains("ClassNotFoundException", ignoreCase = true) ->
            "TDengine JDBC 驱动未找到，请检查应用打包配置"

        isTdengineWebSocketAggregateTimeout(error) ||
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

private val UNSUPPORTED_WEBSOCKET_HTTP_STATUS = Regex("""\b(?:404|405|501)\b""")
private const val REST_HEALTH_TIMEOUT_MS = 2000

private fun collectExceptionMessages(error: Throwable): String {
    return exceptionChain(error)
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .joinToString(" ")
}

private fun exceptionChain(error: Throwable): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && visited.add(current)) {
        chain += current
        current = current.cause
    }
    return chain
}
