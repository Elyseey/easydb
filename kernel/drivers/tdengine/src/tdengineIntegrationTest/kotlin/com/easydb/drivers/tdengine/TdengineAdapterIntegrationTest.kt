package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionManager
import com.easydb.common.SqlExecutionService
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesMetadataLimits
import com.easydb.common.TimeSeriesQueryRequest
import com.easydb.common.TimeSeriesCreateDefinition
import com.easydb.common.TimeSeriesCreateKind
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesFieldDraft
import com.easydb.common.TimeSeriesTagValueDraft
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
    fun `easydb adapter connects and executes basic queries with compatible jdbc protocol`() {
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
    fun `time series object builder creates all object kinds with typed tag values`() {
        assumeTrue(config.allowDdl, "Set EASYDB_TDENGINE_ALLOW_DDL=true for create builder coverage")

        val token = UUID.randomUUID().toString().replace("-", "").take(10)
        val stable = "easydb_it_${token}_builder_stable"
        val child = "easydb_it_${token}_builder_child"
        val basic = "easydb_it_${token}_builder_basic"
        val cleanup = mutableListOf<String>()
        var primaryFailure: Throwable? = null
        val session = adapter.connectionAdapter().open(config.toConnectionConfig())
        val objectAdapter = requireNotNull(adapter.timeSeriesObjectAdapter())

        try {
            val stableDdl = objectAdapter.buildCreateSql(
                session,
                config.database,
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.SUPER_TABLE,
                    name = stable,
                    columns = listOf(
                        TimeSeriesFieldDraft("ts", TimeSeriesDataType.TIMESTAMP),
                        TimeSeriesFieldDraft("reading", TimeSeriesDataType.DOUBLE)
                    ),
                    tags = listOf(
                        TimeSeriesFieldDraft("location", TimeSeriesDataType.VARCHAR, 64),
                        TimeSeriesFieldDraft("description", TimeSeriesDataType.NCHAR, 32),
                        TimeSeriesFieldDraft("group_id", TimeSeriesDataType.BIGINT_UNSIGNED),
                        TimeSeriesFieldDraft("enabled", TimeSeriesDataType.BOOL),
                        TimeSeriesFieldDraft("installed_at", TimeSeriesDataType.TIMESTAMP),
                        TimeSeriesFieldDraft("nullable_note", TimeSeriesDataType.VARCHAR, 8)
                    ),
                    comment = "builder\\rack's integration test"
                )
            )
            assertFalse(stableDdl.contains("IF NOT EXISTS"))
            execute(session, stableDdl)
            cleanup += "DROP STABLE IF EXISTS ${qualified(stable)}"

            val childDdl = objectAdapter.buildCreateSql(
                session,
                config.database,
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.CHILD_TABLE,
                    name = child,
                    stableName = stable,
                    tagValues = listOf(
                        TimeSeriesTagValueDraft("location", ""),
                        TimeSeriesTagValueDraft("description", "中文\\机房's"),
                        TimeSeriesTagValueDraft("group_id", "18446744073709551615"),
                        TimeSeriesTagValueDraft("enabled", "true"),
                        TimeSeriesTagValueDraft("installed_at", "2026-07-17 12:34:56.123"),
                        TimeSeriesTagValueDraft("nullable_note", isNull = true)
                    )
                )
            )
            execute(session, childDdl)
            cleanup += "DROP TABLE IF EXISTS ${qualified(child)}"

            val basicDdl = objectAdapter.buildCreateSql(
                session,
                config.database,
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.BASIC_TABLE,
                    name = basic,
                    columns = listOf(
                        TimeSeriesFieldDraft("event_time", TimeSeriesDataType.TIMESTAMP),
                        TimeSeriesFieldDraft("message", TimeSeriesDataType.NCHAR, 64)
                    )
                )
            )
            execute(session, basicDdl)
            cleanup += "DROP TABLE IF EXISTS ${qualified(basic)}"

            val topLevel = adapter.metadataAdapter().listTables(session, config.database)
            assertEquals(TableKind.SUPER_TABLE, topLevel.single { it.name == stable }.tableKind)
            assertEquals(TableKind.BASIC_TABLE, topLevel.single { it.name == basic }.tableKind)
            assertFalse(topLevel.any { it.name == child })

            val tagValues = requireNotNull(adapter.timeSeriesMetadataAdapter())
                .listTagValues(session, config.database, child)
                .associateBy { it.name }
            assertEquals("", tagValues.getValue("location").value)
            assertEquals("中文\\机房's", tagValues.getValue("description").value)
            assertEquals("18446744073709551615", tagValues.getValue("group_id").value)
            assertEquals("true", tagValues.getValue("enabled").value?.lowercase())
            assertTrue(!tagValues.getValue("installed_at").value.isNullOrBlank())
            assertEquals(null, tagValues.getValue("nullable_note").value)
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
                    "Failed to clean ${cleanupFailures.size} TDengine builder integration object(s)"
                )
                cleanupFailures.forEach(cleanupFailure::addSuppressed)
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    @Test
    fun `time series query applies half-open bounds latest-first paging to every table kind`() {
        assumeTrue(config.allowDdl, "Set EASYDB_TDENGINE_ALLOW_DDL=true for query lifecycle coverage")

        val token = UUID.randomUUID().toString().replace("-", "").take(10)
        val stable = "easydb_it_${token}_query_stable"
        val childOne = "easydb_it_${token}_query_child_1"
        val childTwo = "easydb_it_${token}_query_child_2"
        val basic = "easydb_it_${token}_query_basic"
        val cleanup = mutableListOf<String>()
        var primaryFailure: Throwable? = null
        val session = adapter.connectionAdapter().open(config.toConnectionConfig())
        val query = requireNotNull(adapter.timeSeriesQueryAdapter())

        try {
            execute(
                session,
                "CREATE STABLE ${qualified(stable)} (ts TIMESTAMP, reading INT) TAGS (site VARCHAR(16))"
            )
            cleanup += "DROP STABLE IF EXISTS ${qualified(stable)}"
            execute(session, "CREATE TABLE ${qualified(childOne)} USING ${qualified(stable)} TAGS ('north')")
            cleanup += "DROP TABLE IF EXISTS ${qualified(childOne)}"
            execute(session, "CREATE TABLE ${qualified(childTwo)} USING ${qualified(stable)} TAGS ('south')")
            cleanup += "DROP TABLE IF EXISTS ${qualified(childTwo)}"
            execute(session, "CREATE TABLE ${qualified(basic)} (ts TIMESTAMP, reading INT)")
            cleanup += "DROP TABLE IF EXISTS ${qualified(basic)}"

            execute(
                session,
                "INSERT INTO ${qualified(childOne)} VALUES " +
                    "('2026-07-17T12:00:00+08:00', 1) " +
                    "('2026-07-17T12:01:00+08:00', 2) " +
                    "('2026-07-17T12:02:00+08:00', 3)"
            )
            execute(
                session,
                "INSERT INTO ${qualified(childTwo)} VALUES ('2026-07-17T12:00:30+08:00', 10)"
            )
            execute(
                session,
                "INSERT INTO ${qualified(basic)} VALUES " +
                    "('2026-07-17T12:00:00+08:00', 101) " +
                    "('2026-07-17T12:01:00+08:00', 102) " +
                    "('2026-07-17T12:02:00+08:00', 103)"
            )

            val range = TimeSeriesQueryRequest(
                startInclusive = "2026-07-17T12:00:00+08:00",
                endExclusive = "2026-07-17T12:02:00+08:00",
                where = "reading > 0",
                limit = 1
            )
            val firstChildPage = query.previewRows(session, config.database, childOne, range)
            assertEquals(listOf("2"), firstChildPage.rows.map { it["reading"] })
            assertTrue(firstChildPage.hasMore)
            assertEquals("2026-07-17T12:00:00+08:00", firstChildPage.startInclusive)

            val secondChildPage = query.previewRows(
                session,
                config.database,
                childOne,
                range.copy(offset = 1)
            )
            assertEquals(listOf("1"), secondChildPage.rows.map { it["reading"] })
            assertFalse(secondChildPage.hasMore)

            val stablePage = query.previewRows(session, config.database, stable, range.copy(limit = 10))
            assertEquals(setOf("1", "2", "10"), stablePage.rows.map { it["reading"] }.toSet())
            assertFalse(stablePage.hasMore)

            val basicPage = query.previewRows(session, config.database, basic, range.copy(limit = 10))
            assertEquals(listOf("102", "101"), basicPage.rows.map { it["reading"] })
            assertFalse(basicPage.hasMore)
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
                    "Failed to clean TDengine query integration object(s)"
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

                val timeSeriesPage = requireNotNull(adapter.timeSeriesQueryAdapter()).previewRows(
                    session,
                    database,
                    table,
                    TimeSeriesQueryRequest(
                        startInclusive = "2026-07-16T00:00:00Z",
                        endExclusive = "2026-07-18T00:00:00Z",
                        limit = 10
                    )
                )
                assertTrue(timeSeriesPage.rows.single().getValue("ts")?.contains(".$fraction") == true)
                assertFalse(timeSeriesPage.hasMore)

                val exactBoundaryPage = requireNotNull(adapter.timeSeriesQueryAdapter()).previewRows(
                    session,
                    database,
                    table,
                    TimeSeriesQueryRequest(
                        startInclusive = "2026-07-17T12:34:56.${fraction}Z",
                        endExclusive = "2026-07-17T12:34:57Z",
                        limit = 10
                    )
                )
                assertEquals(1, exactBoundaryPage.rows.size)

                val exclusiveEndPage = requireNotNull(adapter.timeSeriesQueryAdapter()).previewRows(
                    session,
                    database,
                    table,
                    TimeSeriesQueryRequest(
                        startInclusive = "2026-07-17T12:34:55Z",
                        endExclusive = "2026-07-17T12:34:56.${fraction}Z",
                        limit = 10
                    )
                )
                assertTrue(exclusiveEndPage.rows.isEmpty())

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
