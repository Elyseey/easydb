package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionConfig
import com.easydb.common.DatabaseSession
import java.sql.Connection

class TdengineDatabaseSession(
    override val connectionId: String,
    override val config: ConnectionConfig,
    private val connection: Connection
) : DatabaseSession {

    override fun isValid(): Boolean = try {
        validateTdengineConnection(connection)
    } catch (_: Exception) {
        false
    }

    override fun close() {
        try {
            if (!connection.isClosed) connection.close()
        } catch (_: Exception) {
        }
    }

    override fun getJdbcConnection(): Connection = connection
}
