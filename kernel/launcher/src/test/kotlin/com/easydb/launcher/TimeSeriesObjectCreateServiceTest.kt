package com.easydb.launcher

import com.easydb.common.ConnectionConfig
import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesCreateDefinition
import com.easydb.common.TimeSeriesCreateKind
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesFieldDraft
import com.easydb.drivers.mysql.MysqlDatabaseAdapter
import com.easydb.drivers.tdengine.TdengineDatabaseAdapter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeSeriesObjectCreateServiceTest {
    private val service = TimeSeriesObjectCreateService(
        DatabaseAdapterRegistry(listOf(MysqlDatabaseAdapter(), TdengineDatabaseAdapter()))
    )

    @Test
    fun `preview and create rebuild from the current definition and execute one statement`() {
        val executed = mutableListOf<String>()
        val session = fakeSession("tdengine", recordingConnection(executed))
        val preview = service.preview(session, "power", basicTable("preview_only"))

        val result = service.create(session, "power", basicTable("created_now"))

        assertTrue(preview.ddl.contains("`preview_only`"))
        assertTrue(result.ddl.contains("`created_now`"))
        assertEquals(listOf(result.ddl), executed)
        assertEquals(TimeSeriesCreateKind.BASIC_TABLE, result.kind)
        assertEquals("created_now", result.name)
    }

    @Test
    fun `unsupported databases and invalid definitions return stable domain errors`() {
        val unsupported = assertFailsWith<TimeSeriesObjectCreateException> {
            service.preview(fakeSession("mysql", recordingConnection()), "power", basicTable("events"))
        }
        assertEquals("UNSUPPORTED_DB_FEATURE", unsupported.code)

        val invalid = assertFailsWith<TimeSeriesObjectCreateException> {
            service.preview(
                fakeSession("tdengine", recordingConnection()),
                "power",
                basicTable("events").copy(
                    columns = listOf(
                        TimeSeriesFieldDraft("value", TimeSeriesDataType.DOUBLE),
                        TimeSeriesFieldDraft("message", TimeSeriesDataType.VARCHAR, 32)
                    )
                )
            )
        }
        assertEquals("INVALID_TIME_SERIES_DEFINITION", invalid.code)
    }

    @Test
    fun `database failures are classified into stable conflict permission and parent codes`() {
        val cases = listOf(
            Triple("Table already exists", TimeSeriesCreateKind.BASIC_TABLE, "TIME_SERIES_OBJECT_ALREADY_EXISTS"),
            Triple("Permission denied for database", TimeSeriesCreateKind.BASIC_TABLE, "TIME_SERIES_PERMISSION_DENIED"),
            Triple(
                "Stable does not exist",
                TimeSeriesCreateKind.CHILD_TABLE,
                "TIME_SERIES_PARENT_NOT_FOUND"
            )
        )

        cases.forEach { (message, kind, expectedCode) ->
            val error = classifyTimeSeriesCreateFailure(SQLException(message), kind)
            assertEquals(expectedCode, error.code)
        }
    }

    private fun basicTable(name: String) = TimeSeriesCreateDefinition(
        kind = TimeSeriesCreateKind.BASIC_TABLE,
        name = name,
        columns = listOf(
            TimeSeriesFieldDraft("ts", TimeSeriesDataType.TIMESTAMP),
            TimeSeriesFieldDraft("value", TimeSeriesDataType.DOUBLE)
        )
    )

    private fun fakeSession(dbType: String, connection: Connection) = object : DatabaseSession {
        override val connectionId: String = "test"
        override val config: ConnectionConfig = ConnectionConfig(name = "test", dbType = dbType)
        override fun isValid(): Boolean = true
        override fun close() = Unit
        override fun getJdbcConnection(): Connection = connection
    }

    private fun recordingConnection(
        executed: MutableList<String> = mutableListOf(),
        failure: SQLException? = null
    ): Connection {
        val statement = Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execute" -> {
                    failure?.let { throw it }
                    executed += requireNotNull(args).single() as String
                    true
                }
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
