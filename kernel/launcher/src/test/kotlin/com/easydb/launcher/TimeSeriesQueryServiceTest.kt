package com.easydb.launcher

import com.easydb.common.ConnectionConfig
import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesQueryRequest
import com.easydb.drivers.mysql.MysqlDatabaseAdapter
import com.easydb.drivers.tdengine.TdengineDatabaseAdapter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TimeSeriesQueryServiceTest {
    private val service = TimeSeriesQueryService(
        DatabaseAdapterRegistry(listOf(MysqlDatabaseAdapter(), TdengineDatabaseAdapter()))
    )

    @Test
    fun `maps unsupported range and clause failures to stable codes`() {
        val unsupported = assertFailsWith<TimeSeriesQueryException> {
            service.previewRows(fakeSession("mysql", failingConnection()), "power", "events", TimeSeriesQueryRequest())
        }
        assertEquals("UNSUPPORTED_DB_FEATURE", unsupported.code)

        val invalidRange = assertFailsWith<TimeSeriesQueryException> {
            service.previewRows(
                fakeSession("tdengine", failingConnection()),
                "power",
                "events",
                TimeSeriesQueryRequest(
                    startInclusive = "2026-07-17T13:00:00Z",
                    endExclusive = "2026-07-17T12:00:00Z"
                )
            )
        }
        assertEquals("INVALID_TIME_RANGE", invalidRange.code)

        val invalidClause = assertFailsWith<TimeSeriesQueryException> {
            service.previewRows(
                fakeSession("tdengine", failingConnection()),
                "power",
                "events",
                TimeSeriesQueryRequest(where = "value > 1; DELETE FROM events")
            )
        }
        assertEquals("INVALID_READ_ONLY_CLAUSE", invalidClause.code)
    }

    @Test
    fun `maps jdbc failures without leaking adapter details into routes`() {
        val failure = assertFailsWith<TimeSeriesQueryException> {
            service.previewRows(
                fakeSession("tdengine", failingConnection(SQLException("query unavailable"))),
                "power",
                "events",
                TimeSeriesQueryRequest()
            )
        }
        assertEquals("TIME_SERIES_PREVIEW_FAILED", failure.code)
        assertEquals("query unavailable", failure.message)
    }

    private fun fakeSession(dbType: String, connection: Connection) = object : DatabaseSession {
        override val connectionId: String = "test"
        override val config: ConnectionConfig = ConnectionConfig(name = "test", dbType = dbType)
        override fun isValid(): Boolean = true
        override fun close() = Unit
        override fun getJdbcConnection(): Connection = connection
    }

    private fun failingConnection(failure: SQLException = SQLException("must not execute")): Connection {
        val statement = Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "executeQuery" -> throw failure
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        } as Statement
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "createStatement" -> statement
                "close" -> null
                else -> defaultValue(method.returnType)
            }
        } as Connection
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
