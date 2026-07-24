package com.easydb.launcher

import com.easydb.common.*
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeSeriesPhase7ServiceTest {
    @Test
    fun `apply routes reject unsupported adapters before validating payload proofs`() {
        val session = session(recordingConnection())
        val basic = assertFailsWith<TimeSeriesBasicTableLifecycleException> {
            TimeSeriesBasicTableLifecycleService(registry()).apply(
                session,
                "power",
                "events",
                TimeSeriesBasicTableApplyRequest(
                    TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.DROP_COLUMN, "value"),
                    "",
                    "",
                    "wrong"
                )
            )
        }
        assertEquals("UNSUPPORTED_DB_FEATURE", basic.code)

        val writeRequest = TimeSeriesWriteRequest(
            TimeSeriesWriteTargetKind.BASIC_TABLE,
            "events",
            columns = listOf("ts"),
            rows = listOf(TimeSeriesWriteRow(listOf(TimeSeriesWriteCell("ts", "2026-07-22 10:00:00"))))
        )
        val write = assertFailsWith<TimeSeriesDataWriteException> {
            TimeSeriesDataWriteService(registry()).apply(
                session,
                "power",
                TimeSeriesWriteApplyRequest(writeRequest, "", "")
            )
        }
        assertEquals("UNSUPPORTED_DB_FEATURE", write.code)
    }

    @Test
    fun `basic lifecycle binds preview proof confirmation and executes one ddl`() {
        val executed = mutableListOf<String>()
        val snapshot = TimeSeriesBasicTableSnapshot("power", "events", listOf(
            TimeSeriesLifecycleField("ts", "TIMESTAMP", primaryTimestamp = true),
            TimeSeriesLifecycleField("value", "DOUBLE"),
            TimeSeriesLifecycleField("note", "VARCHAR", 16)
        ), "basic-v1")
        val adapter = object : TimeSeriesBasicTableLifecycleAdapter {
            override fun inspectBasicTable(session: DatabaseSession, database: String, table: String) = snapshot
            override fun buildBasicTableMutationSql(database: String, table: String, snapshot: TimeSeriesBasicTableSnapshot, command: TimeSeriesBasicTableCommand) =
                "ALTER TABLE `power`.`events` DROP COLUMN `value`"
        }
        val service = TimeSeriesBasicTableLifecycleService(registry(basic = adapter))
        val session = session(recordingConnection(executed))
        val command = TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.DROP_COLUMN, "value")
        val preview = service.preview(session, "power", "events", command)
        val mismatch = assertFailsWith<TimeSeriesBasicTableLifecycleException> {
            service.apply(session, "power", "events", TimeSeriesBasicTableApplyRequest(command, snapshot.fingerprint, preview.previewToken, "VALUE"))
        }
        assertEquals("DESTRUCTIVE_CONFIRMATION_MISMATCH", mismatch.code)
        service.apply(session, "power", "events", TimeSeriesBasicTableApplyRequest(command, snapshot.fingerprint, preview.previewToken, "value"))
        assertEquals(listOf(preview.ddl), executed)
    }

    @Test
    fun `data write re-inspects target and rejects changed proof before driver execution`() {
        val request = TimeSeriesWriteRequest(
            TimeSeriesWriteTargetKind.BASIC_TABLE, "events", columns = listOf("ts"),
            rows = listOf(TimeSeriesWriteRow(listOf(TimeSeriesWriteCell("ts", "2026-07-22 10:00:00"))))
        )
        var snapshot = writeSnapshot("write-v1")
        var executions = 0
        val adapter = object : TimeSeriesDataWriteAdapter {
            override fun inspectWriteTarget(session: DatabaseSession, database: String, request: TimeSeriesWriteRequest) = snapshot
            override fun buildWritePlan(database: String, snapshot: TimeSeriesWriteSnapshot, request: TimeSeriesWriteRequest) =
                TimeSeriesWritePlan("INSERT INTO hidden VALUES (?)", "INSERT INTO `power`.`events` (`ts`) VALUES ('2026-07-22 10:00:00')", listOf(TimeSeriesWriteParameter("TIMESTAMP", "2026-07-22 10:00:00")), 1, false)
            override fun executeWritePlan(session: DatabaseSession, plan: TimeSeriesWritePlan) { executions += 1 }
        }
        val service = TimeSeriesDataWriteService(registry(write = adapter))
        val session = session(recordingConnection())
        val preview = service.preview(session, "power", request)
        snapshot = writeSnapshot("write-v2")
        val changed = assertFailsWith<TimeSeriesDataWriteException> {
            service.apply(session, "power", TimeSeriesWriteApplyRequest(request, preview.snapshot.fingerprint, preview.previewToken))
        }
        assertEquals("OBJECT_CHANGED", changed.code)
        assertEquals(0, executions)
        snapshot = preview.snapshot
        val result = service.apply(session, "power", TimeSeriesWriteApplyRequest(request, preview.snapshot.fingerprint, preview.previewToken))
        assertEquals(1, result.insertedRows)
        assertEquals(1, executions)
        assertTrue(preview.sql.contains("2026-07-22"))
    }

    private fun writeSnapshot(fingerprint: String) = TimeSeriesWriteSnapshot(
        "power", TimeSeriesWriteTargetKind.BASIC_TABLE, "events",
        columns = listOf(TimeSeriesLifecycleField("ts", "TIMESTAMP", primaryTimestamp = true)), fingerprint = fingerprint
    )

    private fun registry(basic: TimeSeriesBasicTableLifecycleAdapter? = null, write: TimeSeriesDataWriteAdapter? = null) =
        DatabaseAdapterRegistry(listOf(Proxy.newProxyInstance(DatabaseAdapter::class.java.classLoader, arrayOf(DatabaseAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "dbType" -> DbType.TDENGINE
                "capabilities" -> DatabaseCapabilities(supportsTimeSeriesBasicTableLifecycle = basic != null, supportsTimeSeriesDataWrite = write != null)
                "timeSeriesBasicTableLifecycleAdapter" -> basic
                "timeSeriesDataWriteAdapter" -> write
                else -> defaultValue(method.returnType)
            }
        } as DatabaseAdapter))

    private fun session(connection: Connection) = object : DatabaseSession {
        override val connectionId = "test"
        override val config = ConnectionConfig(name = "test", dbType = "tdengine")
        override fun isValid() = true
        override fun close() = Unit
        override fun getJdbcConnection() = connection
    }

    private fun recordingConnection(executed: MutableList<String> = mutableListOf()): Connection {
        val statement = Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, method, args ->
            when (method.name) { "execute" -> { executed += args!![0] as String; true }; "close" -> null; else -> defaultValue(method.returnType) }
        } as Statement
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) { "createStatement" -> statement; "close" -> null; else -> defaultValue(method.returnType) }
        } as Connection
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; java.lang.Double.TYPE -> 0.0; else -> null
    }
}
