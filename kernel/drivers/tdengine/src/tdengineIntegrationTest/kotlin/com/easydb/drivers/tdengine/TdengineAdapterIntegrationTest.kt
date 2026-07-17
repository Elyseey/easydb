package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionManager
import com.easydb.common.SqlExecutionService
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesMetadataLimits
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.SQLFeatureNotSupportedException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TdengineAdapterIntegrationTest {
    private val config = TdengineIntegrationTestConfig.fromEnvironment()
    private val adapter = TdengineDatabaseAdapter()
    private val dialect = TdengineDialectAdapter()

    @Test
    fun `easydb adapter connects and executes basic queries over websocket`() {
        val connectionConfig = config.toConnectionConfig()

        val testResult = adapter.connectionAdapter().testConnection(connectionConfig)
        assertTrue(testResult.success, testResult.message)

        val session = adapter.connectionAdapter().open(connectionConfig)
        try {
            assertTrue(session.isValid())
            session.getJdbcConnection().createStatement().use { statement ->
                statement.executeQuery("SELECT SERVER_VERSION() AS version").use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getString("version").isNotBlank())
                }
                statement.execute(adapter.dialectAdapter().buildSwitchDatabaseSql(config.database))
                statement.executeQuery("SELECT DATABASE() AS database_name").use { result ->
                    assertTrue(result.next())
                    assertEquals(config.database, result.getString("database_name"))
                }
            }
            assertTrue(adapter.metadataAdapter().listDatabases(session).any { it.name == config.database })
        } finally {
            adapter.connectionAdapter().close(session)
        }
        assertFalse(session.isValid())
    }

    @Test
    fun `connection test reports an unreachable websocket endpoint without leaking credentials`() {
        val unreachable = config.toConnectionConfig().copy(port = 1)

        val result = adapter.connectionAdapter().testConnection(unreachable)

        assertFalse(result.success)
        assertFalse(result.message.contains(config.password))
    }

    @Test
    fun `closed sessions reconnect and query resources close while active cancel is unsupported`() {
        val manager = ConnectionManager()
        val connectionConfig = config.toConnectionConfig()
        try {
            val original = manager.openSession(adapter.connectionAdapter(), connectionConfig)
            original.close()

            val reconnected = assertNotNull(manager.getPrimarySession(connectionConfig.id))
            assertTrue(reconnected !== original)
            assertTrue(reconnected.isValid())
            reconnected.getJdbcConnection().createStatement().use { statement ->
                statement.executeQuery("SELECT 1 AS ready").use { result ->
                    assertTrue(result.next())
                    assertEquals("1", result.getString("ready"))
                    assertFailsWith<SQLFeatureNotSupportedException> { statement.cancel() }
                }
                assertFalse(statement.isClosed)
            }
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `catalog keeps stables basic tables children columns and tags distinct`() {
        assumeTrue(config.allowDdl, "Set EASYDB_TDENGINE_ALLOW_DDL=true for catalog lifecycle coverage")

        val token = UUID.randomUUID().toString().replace("-", "").take(10)
        val stable = "easydb_it_${token}_meters"
        val childOne = "easydb_it_${token}_d1001"
        val childTwo = "easydb_it_${token}_d1002"
        val basic = "easydb_it_${token}_basic"
        val cleanup = mutableListOf<String>()
        var primaryFailure: Throwable? = null
        val session = adapter.connectionAdapter().open(config.toConnectionConfig())

        try {
            execute(
                session,
                "CREATE STABLE ${qualified(stable)} (ts TIMESTAMP, reading DOUBLE) " +
                    "TAGS (location VARCHAR(64), group_id INT)"
            )
            cleanup += "DROP STABLE IF EXISTS ${qualified(stable)}"

            val emptyStableTags = requireNotNull(adapter.timeSeriesMetadataAdapter())
                .listTagDefinitions(session, config.database, stable)
            assertEquals(setOf("location", "group_id"), emptyStableTags.map { it.name }.toSet())

            execute(session, "CREATE TABLE ${qualified(childOne)} USING ${qualified(stable)} TAGS ('beijing', 1)")
            cleanup += "DROP TABLE IF EXISTS ${qualified(childOne)}"
            execute(session, "CREATE TABLE ${qualified(childTwo)} USING ${qualified(stable)} TAGS ('shanghai', 2)")
            cleanup += "DROP TABLE IF EXISTS ${qualified(childTwo)}"
            execute(session, "CREATE TABLE ${qualified(basic)} (ts TIMESTAMP, reading DOUBLE)")
            cleanup += "DROP TABLE IF EXISTS ${qualified(basic)}"
            execute(session, "INSERT INTO ${qualified(childOne)} VALUES (NOW, 10.5)")
            execute(session, "INSERT INTO ${qualified(basic)} VALUES (NOW, 42.0)")

            val metadata = adapter.metadataAdapter()
            val timeSeries = requireNotNull(adapter.timeSeriesMetadataAdapter())
            val topLevel = metadata.listTables(session, config.database)
            assertEquals(TableKind.SUPER_TABLE, topLevel.single { it.name == stable }.tableKind)
            assertEquals(TableKind.BASIC_TABLE, topLevel.single { it.name == basic }.tableKind)
            assertFalse(topLevel.any { it.name == childOne || it.name == childTwo })

            val columns = metadata.getColumns(session, config.database, stable)
            assertEquals(listOf("ts", "reading"), columns.map { it.name })
            assertFalse(columns.any { it.name == "location" || it.name == "group_id" })

            val definitions = timeSeries.listTagDefinitions(session, config.database, stable)
            assertEquals(setOf("location", "group_id"), definitions.map { it.name }.toSet())

            val firstPage = timeSeries.listChildTables(
                session,
                config.database,
                stable,
                offset = 0,
                limit = 1
            )
            assertEquals(1, firstPage.items.size)
            assertTrue(firstPage.hasMore)
            assertEquals(stable, firstPage.items.single().stableName)
            assertTrue(firstPage.items.single().tagValues.isNotEmpty())

            val searched = timeSeries.listChildTables(
                session,
                config.database,
                stable,
                search = childTwo
            )
            assertEquals(childTwo, searched.items.single().name)
            assertEquals("shanghai", searched.items.single().tagValues.single { it.name == "location" }.value)

            val capped = timeSeries.listChildTables(
                session,
                config.database,
                stable,
                limit = 10_000
            )
            assertEquals(TimeSeriesMetadataLimits.MAX_CHILD_TABLE_PAGE_SIZE, capped.limit)

            assertEquals(TableKind.CHILD_TABLE, metadata.getTableInfo(session, config.database, childOne).tableKind)
            assertTrue(metadata.getDdl(session, config.database, stable).startsWith("CREATE STABLE"))
            assertTrue(metadata.getDdl(session, config.database, childOne).startsWith("CREATE TABLE"))
            assertTrue(metadata.getDdl(session, config.database, basic).startsWith("CREATE TABLE"))
            assertEquals(1, metadata.previewRows(session, config.database, childOne, limit = 10).size)
            assertEquals(1, metadata.previewRows(session, config.database, basic, limit = 10).size)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = cleanup.asReversed().mapNotNull { sql ->
                runCatching { execute(session, sql) }.exceptionOrNull()
            }
            adapter.connectionAdapter().close(session)
            if (cleanupFailures.isNotEmpty()) {
                val cleanupFailure = AssertionError(
                    "Failed to clean ${cleanupFailures.size} EASYDB_IT_ TDengine object(s)"
                )
                cleanupFailures.forEach(cleanupFailure::addSuppressed)
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    @Test
    fun `timestamp precision special types and long cell preview survive the websocket path`() {
        assumeTrue(config.allowDdl, "Set EASYDB_TDENGINE_ALLOW_DDL=true for precision lifecycle coverage")

        val precisionFractions = mapOf(
            "ms" to "123",
            "us" to "123456",
            "ns" to "123456789"
        )
        val payload = "时".repeat(300)

        precisionFractions.forEach { (precision, fraction) ->
            val database = assertNotNull(config.precisionDatabases[precision])
            val token = UUID.randomUUID().toString().replace("-", "").take(10)
            val table = "easydb_it_${token}_${precision}"
            val session = adapter.connectionAdapter().open(config.toConnectionConfig(database))
            var created = false
            var primaryFailure: Throwable? = null
            try {
                execute(
                    session,
                    "CREATE TABLE ${qualified(database, table)} (" +
                        "ts TIMESTAMP, enabled BOOL, counter BIGINT UNSIGNED, payload NCHAR(1024))"
                )
                created = true
                execute(
                    session,
                    "INSERT INTO ${qualified(database, table)} VALUES (" +
                        "'2026-07-17 12:34:56.$fraction', true, 42, " +
                        "${stringLiteral(payload)})"
                )

                val columns = adapter.metadataAdapter().getColumns(session, database, table)
                assertTrue(columns.any { it.name == "enabled" && it.type == "BOOL" })
                assertTrue(columns.any { it.name == "counter" && it.type == "BIGINT UNSIGNED" })
                assertTrue(columns.any { it.name == "payload" && it.type.startsWith("NCHAR") })

                val rawRow = adapter.metadataAdapter().previewRows(session, database, table, limit = 1).single()
                assertTrue(rawRow.getValue("ts")?.contains(".$fraction") == true, rawRow["ts"])
                assertEquals("true", rawRow["enabled"]?.lowercase())
                assertEquals("42", rawRow["counter"])
                assertEquals(payload, rawRow["payload"])

                val preview = SqlExecutionService().previewQuery(
                    session = session,
                    database = database,
                    sql = "SELECT ts, payload FROM ${qualified(database, table)}",
                    offset = 0,
                    pageSize = 10,
                    maxCellChars = 128,
                    dialect = dialect
                )
                assertEquals("query", preview.type, preview.error)
                assertTrue(preview.rows.orEmpty().single().getValue("ts")?.contains(".$fraction") == true)
                assertTrue(preview.rows.orEmpty().single().getValue("payload")?.endsWith(" …[truncated]") == true)
                assertEquals(1, preview.truncatedCellCount)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                val cleanupFailure = if (created) {
                    runCatching { execute(session, "DROP TABLE IF EXISTS ${qualified(database, table)}") }
                        .exceptionOrNull()
                } else {
                    null
                }
                adapter.connectionAdapter().close(session)
                if (cleanupFailure != null) {
                    val failure = AssertionError("Failed to clean EASYDB_IT_ TDengine precision object", cleanupFailure)
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
                }
            }
        }
    }

    private fun execute(session: com.easydb.common.DatabaseSession, sql: String) {
        session.getJdbcConnection().createStatement().use { statement -> statement.execute(sql) }
    }

    private fun qualified(name: String): String =
        qualified(config.database, name)

    private fun qualified(database: String, name: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(name)}"

    private fun stringLiteral(value: String): String = "'${value.replace("'", "''")}'"
}
