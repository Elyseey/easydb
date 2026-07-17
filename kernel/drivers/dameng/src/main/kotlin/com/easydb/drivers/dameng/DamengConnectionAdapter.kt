package com.easydb.drivers.dameng

import com.easydb.common.*
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

class DamengConnectionAdapter : ConnectionAdapter {

    override fun testConnection(config: ConnectionConfig): ConnectionTestResult {
        val start = System.currentTimeMillis()
        return try {
            createJdbcConnection(config).use { conn ->
                val valid = conn.isValid(5)
                val latency = System.currentTimeMillis() - start
                if (valid) {
                    ConnectionTestResult(success = true, message = "连接成功", latencyMs = latency)
                } else {
                    ConnectionTestResult(success = false, message = "连接验证失败")
                }
            }
        } catch (e: Exception) {
            ConnectionTestResult(success = false, message = translateDamengException(e))
        }
    }

    override fun open(config: ConnectionConfig): DatabaseSession {
        return try {
            val conn = createJdbcConnection(config)
            DamengDatabaseSession(
                connectionId = config.id,
                config = config,
                connection = conn
            )
        } catch (e: Exception) {
            throw RuntimeException(translateDamengException(e), e)
        }
    }

    override fun close(session: DatabaseSession) {
        session.close()
    }

    companion object {
        fun createJdbcConnection(config: ConnectionConfig): Connection {
            Class.forName("dm.jdbc.driver.DmDriver")

            val schema = config.database?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""
            val url = "jdbc:dm://${config.host}:${config.port}$schema"

            val props = Properties().apply {
                setProperty("user", config.username)
                setProperty("password", config.password)
                setProperty("connectTimeout", "5000")
                setProperty("socketTimeout", "300000")
            }

            val sslConfig = config.ssl
            if (sslConfig != null && sslConfig.enabled) {
                props.setProperty("ssl", "true")
                sslConfig.caPath?.takeIf { it.isNotBlank() }?.let { ca ->
                    props.setProperty("sslFilesPath", ca)
                }
            }

            return DriverManager.getConnection(url, props)
        }
    }
}

private fun translateDamengException(e: Exception): String {
    val msg = e.message ?: e.javaClass.simpleName
    val cause = e.cause?.message ?: ""

    return when {
        msg.contains("Connection refused", ignoreCase = true) ||
        cause.contains("Connection refused", ignoreCase = true) ->
            "无法连接到达梦数据库，请检查服务、主机、端口和网络"

        msg.contains("Connection timed out", ignoreCase = true) ||
        msg.contains("connect timed out", ignoreCase = true) ->
            "连接超时（5s），请检查网络可达性和达梦端口是否开放"

        msg.contains("Access denied", ignoreCase = true) ||
        msg.contains("认证失败", ignoreCase = true) ->
            "达梦认证失败，请检查用户名或密码"

        msg.contains("SSL", ignoreCase = true) || msg.contains("TLS", ignoreCase = true) ||
        cause.contains("SSL", ignoreCase = true) ->
            "SSL/TLS 握手失败，请检查达梦 SSL 配置"

        msg.contains("ClassNotFoundException", ignoreCase = true) ||
        e is ClassNotFoundException ->
            "达梦 JDBC 驱动未找到，请检查应用打包配置"

        else -> {
            val firstLine = msg.lines().firstOrNull()?.trim() ?: msg
            firstLine.ifBlank { "达梦数据库连接失败，请检查连接配置" }
        }
    }
}
