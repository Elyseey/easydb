package com.easydb.launcher

import com.easydb.common.ConnectionConfig
import com.easydb.common.DatabaseAdapter
import com.easydb.common.DatabaseCapabilities
import com.easydb.common.DatabaseSession
import com.easydb.common.DbType
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesChildPropertyApplyRequest
import com.easydb.common.TimeSeriesChildPropertyCommand
import com.easydb.common.TimeSeriesChildPropertyOperation
import com.easydb.common.TimeSeriesChildPropertySnapshot
import com.easydb.common.TimeSeriesDeleteApplyRequest
import com.easydb.common.TimeSeriesDeleteObjectKind
import com.easydb.common.TimeSeriesDeleteSnapshot
import com.easydb.common.TimeSeriesLifecycleAdapter
import com.easydb.common.TimeSeriesLifecycleApplyRequest
import com.easydb.common.TimeSeriesLifecycleCommand
import com.easydb.common.TimeSeriesLifecycleField
import com.easydb.common.TimeSeriesLifecycleOperation
import com.easydb.common.TimeSeriesLifecycleSnapshot
import com.easydb.common.TimeSeriesTagValue
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeSeriesLifecycleServiceTest {

    @Test
    fun `delete requires exact confirmation rechecks fingerprint and executes one statement`() {
        val executed = mutableListOf<String>()
        var current = TimeSeriesDeleteSnapshot(
            database = "power",
            name = "meters",
            kind = TimeSeriesDeleteObjectKind.SUPER_TABLE,
            affectedChildTables = 12,
            fingerprint = "delete-v1"
        )
        val ddl = "DROP STABLE `power`.`meters`"
        val adapter = deleteLifecycle({ current }, ddl)
        val service = service(adapter)
        val session = fakeSession("tdengine", recordingConnection(executed))
        val preview = service.previewDelete(session, "power", "meters")

        val mismatch = assertFailsWith<TimeSeriesLifecycleException> {
            service.applyDelete(
                session,
                "power",
                "meters",
                TimeSeriesDeleteApplyRequest(preview.snapshot.fingerprint, preview.previewToken, "Meters")
            )
        }
        assertEquals("DESTRUCTIVE_CONFIRMATION_MISMATCH", mismatch.code)
        assertTrue(executed.isEmpty())

        current = current.copy(affectedChildTables = 13, fingerprint = "delete-v2")
        val changed = assertFailsWith<TimeSeriesLifecycleException> {
            service.applyDelete(
                session,
                "power",
                "meters",
                TimeSeriesDeleteApplyRequest(preview.snapshot.fingerprint, preview.previewToken, "meters")
            )
        }
        assertEquals("OBJECT_CHANGED", changed.code)
        assertTrue(executed.isEmpty())

        current = preview.snapshot
        val result = service.applyDelete(
            session,
            "power",
            "meters",
            TimeSeriesDeleteApplyRequest(preview.snapshot.fingerprint, preview.previewToken, "meters")
        )
        assertEquals(listOf(ddl), executed)
        assertEquals(12, result.snapshot.affectedChildTables)
    }

    @Test
    fun `preview and apply rebuild the same command and execute exactly one statement`() {
        val executed = mutableListOf<String>()
        val session = fakeSession("tdengine", recordingConnection(executed))
        val snapshot = snapshot("v1")
        val command = TimeSeriesLifecycleCommand(TimeSeriesLifecycleOperation.DROP_TAG, "group_id")
        val ddl = "ALTER STABLE `power`.`meters` DROP TAG `group_id`"
        val service = service(lifecycle(snapshot) { actual ->
            assertEquals(command, actual)
            ddl
        })

        val preview = service.preview(session, "power", "meters", command)
        val result = service.apply(
            session,
            "power",
            "meters",
            TimeSeriesLifecycleApplyRequest(command, preview.snapshot.fingerprint, preview.previewToken, "group_id")
        )

        assertTrue(preview.destructive)
        assertTrue(preview.warnings.single().contains("全部子表"))
        assertEquals(listOf(ddl), executed)
        assertEquals(ddl, result.ddl)
        assertEquals("v1", result.previousFingerprint)
    }

    @Test
    fun `apply rejects changed metadata before building or executing ddl`() {
        val executed = mutableListOf<String>()
        val command = TimeSeriesLifecycleCommand(TimeSeriesLifecycleOperation.DROP_COLUMN, "value")
        val service = service(lifecycle(snapshot("v2")) { "unused" })

        val error = assertFailsWith<TimeSeriesLifecycleException> {
            service.apply(
                fakeSession("tdengine", recordingConnection(executed)),
                "power",
                "meters",
                TimeSeriesLifecycleApplyRequest(command, "v1", "old-preview", "value")
            )
        }

        assertEquals("OBJECT_CHANGED", error.code)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `apply rejects a command changed after preview`() {
        val executed = mutableListOf<String>()
        val snapshot = snapshot("v1")
        val previewCommand = TimeSeriesLifecycleCommand(TimeSeriesLifecycleOperation.DROP_TAG, "group_id")
        val changedCommand = TimeSeriesLifecycleCommand(
            TimeSeriesLifecycleOperation.ADD_TAG,
            "site",
            TimeSeriesDataType.VARCHAR,
            32
        )
        val service = service(lifecycle(snapshot) { command ->
            if (command == previewCommand) "DROP PREVIEW" else "ADD CHANGED"
        })
        val session = fakeSession("tdengine", recordingConnection(executed))
        val preview = service.preview(session, "power", "meters", previewCommand)

        val error = assertFailsWith<TimeSeriesLifecycleException> {
            service.apply(
                session,
                "power",
                "meters",
                TimeSeriesLifecycleApplyRequest(changedCommand, snapshot.fingerprint, preview.previewToken)
            )
        }

        assertEquals("INVALID_LIFECYCLE_COMMAND", error.code)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `unsupported invalid and database failures use stable error codes`() {
        val unsupportedService = TimeSeriesLifecycleService(
            DatabaseAdapterRegistry(listOf(databaseAdapter(DbType.MYSQL, false, null)))
        )
        val unsupported = assertFailsWith<TimeSeriesLifecycleException> {
            unsupportedService.inspect(fakeSession("mysql", recordingConnection()), "power", "meters")
        }
        assertEquals("UNSUPPORTED_DB_FEATURE", unsupported.code)

        val command = TimeSeriesLifecycleCommand(TimeSeriesLifecycleOperation.DROP_TAG, "missing")
        val invalidService = service(lifecycle(snapshot("v1")) { throw IllegalArgumentException("Tag 不存在") })
        val invalid = assertFailsWith<TimeSeriesLifecycleException> {
            invalidService.preview(fakeSession("tdengine", recordingConnection()), "power", "meters", command)
        }
        assertEquals("INVALID_LIFECYCLE_COMMAND", invalid.code)

        val failedService = service(lifecycle(snapshot("v1")) { "DROP TAG" })
        val preview = failedService.preview(
            fakeSession("tdengine", recordingConnection()),
            "power",
            "meters",
            command
        )
        val failed = assertFailsWith<TimeSeriesLifecycleException> {
            failedService.apply(
                fakeSession("tdengine", recordingConnection(failure = SQLException("permission denied"))),
                "power",
                "meters",
                TimeSeriesLifecycleApplyRequest(command, "v1", preview.previewToken, "missing")
            )
        }
        assertEquals("TIME_SERIES_MUTATION_FAILED", failed.code)
    }

    @Test
    fun `destructive lifecycle apply requires exact confirmation name`() {
        val executed = mutableListOf<String>()
        val command = TimeSeriesLifecycleCommand(TimeSeriesLifecycleOperation.DROP_TAG, "group_id")
        val service = service(lifecycle(snapshot("v1")) { "ALTER STABLE DROP TAG" })
        val session = fakeSession("tdengine", recordingConnection(executed))
        val preview = service.preview(session, "power", "meters", command)

        listOf(null, "GROUP_ID", " group_id ").forEach { confirmation ->
            val error = assertFailsWith<TimeSeriesLifecycleException> {
                service.apply(
                    session,
                    "power",
                    "meters",
                    TimeSeriesLifecycleApplyRequest(command, "v1", preview.previewToken, confirmation)
                )
            }
            assertEquals("DESTRUCTIVE_CONFIRMATION_MISMATCH", error.code)
        }
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `child preview and apply bind fingerprint token and execute exactly one statement`() {
        val executed = mutableListOf<String>()
        val childSnapshot = TimeSeriesChildPropertySnapshot(
            database = "power",
            table = "d1",
            stableName = "meters",
            tagValues = listOf(TimeSeriesTagValue("location", "VARCHAR(16)", "old")),
            ttl = 0,
            fingerprint = "child-v1"
        )
        val command = TimeSeriesChildPropertyCommand(
            operation = TimeSeriesChildPropertyOperation.SET_TAG,
            tagName = "location",
            value = "",
            isNull = false
        )
        val ddl = "ALTER TABLE `power`.`d1` SET TAG `location` = ''"
        val adapter = object : TimeSeriesLifecycleAdapter {
            override fun inspect(
                session: DatabaseSession,
                database: String,
                stable: String
            ): TimeSeriesLifecycleSnapshot = snapshot("unused")

            override fun buildMutationSql(
                database: String,
                stable: String,
                snapshot: TimeSeriesLifecycleSnapshot,
                command: TimeSeriesLifecycleCommand
            ): String = "unused"

            override fun inspectChildProperties(
                session: DatabaseSession,
                database: String,
                table: String
            ): TimeSeriesChildPropertySnapshot = childSnapshot

            override fun buildChildPropertyMutationSql(
                database: String,
                table: String,
                snapshot: TimeSeriesChildPropertySnapshot,
                command: TimeSeriesChildPropertyCommand
            ): String = ddl
        }
        val service = service(adapter)
        val session = fakeSession("tdengine", recordingConnection(executed))

        val preview = service.previewChild(session, "power", "d1", command)
        val result = service.applyChild(
            session,
            "power",
            "d1",
            TimeSeriesChildPropertyApplyRequest(command, childSnapshot.fingerprint, preview.previewToken)
        )

        assertEquals(listOf(ddl), executed)
        assertEquals(ddl, result.ddl)
        assertEquals("", preview.command.value)
    }

    private fun lifecycle(
        snapshot: TimeSeriesLifecycleSnapshot,
        sqlBuilder: (TimeSeriesLifecycleCommand) -> String
    ) = object : TimeSeriesLifecycleAdapter {
        override fun inspect(
            session: DatabaseSession,
            database: String,
            stable: String
        ): TimeSeriesLifecycleSnapshot = snapshot

        override fun buildMutationSql(
            database: String,
            stable: String,
            snapshot: TimeSeriesLifecycleSnapshot,
            command: TimeSeriesLifecycleCommand
        ): String = sqlBuilder(command)
    }

    private fun deleteLifecycle(
        snapshotProvider: () -> TimeSeriesDeleteSnapshot,
        ddl: String
    ) = object : TimeSeriesLifecycleAdapter {
        override fun inspect(
            session: DatabaseSession,
            database: String,
            stable: String
        ): TimeSeriesLifecycleSnapshot = snapshot("unused")

        override fun buildMutationSql(
            database: String,
            stable: String,
            snapshot: TimeSeriesLifecycleSnapshot,
            command: TimeSeriesLifecycleCommand
        ): String = "unused"

        override fun inspectDelete(
            session: DatabaseSession,
            database: String,
            name: String
        ): TimeSeriesDeleteSnapshot = snapshotProvider()

        override fun buildDeleteSql(
            database: String,
            name: String,
            snapshot: TimeSeriesDeleteSnapshot
        ): String = ddl
    }

    private fun service(lifecycle: TimeSeriesLifecycleAdapter) = TimeSeriesLifecycleService(
        DatabaseAdapterRegistry(listOf(databaseAdapter(DbType.TDENGINE, true, lifecycle)))
    )

    private fun databaseAdapter(
        dbType: DbType,
        lifecycleSupported: Boolean,
        lifecycle: TimeSeriesLifecycleAdapter?
    ): DatabaseAdapter = Proxy.newProxyInstance(
        DatabaseAdapter::class.java.classLoader,
        arrayOf(DatabaseAdapter::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "dbType" -> dbType
            "capabilities" -> DatabaseCapabilities(
                supportsTimeSeriesLifecycle = lifecycleSupported,
                supportsTimeSeriesObjectDelete = lifecycleSupported
            )
            "timeSeriesLifecycleAdapter" -> lifecycle
            else -> defaultValue(method.returnType)
        }
    } as DatabaseAdapter

    private fun snapshot(fingerprint: String) = TimeSeriesLifecycleSnapshot(
        database = "power",
        stable = "meters",
        columns = listOf(
            TimeSeriesLifecycleField("ts", "TIMESTAMP", primaryTimestamp = true),
            TimeSeriesLifecycleField("value", "DOUBLE")
        ),
        tags = listOf(
            TimeSeriesLifecycleField("location", "NCHAR", 32),
            TimeSeriesLifecycleField("group_id", "INT")
        ),
        fingerprint = fingerprint
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
