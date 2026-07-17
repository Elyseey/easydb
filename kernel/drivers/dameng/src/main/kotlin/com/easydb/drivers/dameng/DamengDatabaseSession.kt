package com.easydb.drivers.dameng

import com.easydb.common.ConnectionConfig
import com.easydb.common.DatabaseSession
import java.sql.Connection

class DamengDatabaseSession(
    override val connectionId: String,
    override val config: ConnectionConfig,
    val connection: Connection
) : DatabaseSession {

    override fun isValid(): Boolean {
        return try {
            !connection.isClosed && connection.isValid(3)
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        try {
            if (!connection.isClosed) {
                connection.close()
            }
        } catch (_: Exception) {
        }
    }

    override fun getJdbcConnection(): java.sql.Connection = connection
}
