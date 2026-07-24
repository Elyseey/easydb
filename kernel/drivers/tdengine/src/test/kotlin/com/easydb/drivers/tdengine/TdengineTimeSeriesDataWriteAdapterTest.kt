package com.easydb.drivers.tdengine

import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableInfo
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesChildTable
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagValueDraft
import com.easydb.common.TimeSeriesWriteCell
import com.easydb.common.TimeSeriesWriteRequest
import com.easydb.common.TimeSeriesWriteRow
import com.easydb.common.TimeSeriesWriteTargetKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TdengineTimeSeriesDataWriteAdapterTest {
    private val metadata = mockk<MetadataAdapter>()
    private val timeSeries = mockk<TimeSeriesMetadataAdapter>()
    private val session = mockk<DatabaseSession>()
    private val adapter = TdengineTimeSeriesDataWriteAdapter(metadata, timeSeries)

    @Test
    fun `basic table batch uses parameters and preserves null versus empty string`() {
        basicMetadata()
        val request = TimeSeriesWriteRequest(
            TimeSeriesWriteTargetKind.BASIC_TABLE,
            table = "events",
            columns = listOf("ts", "message", "enabled"),
            rows = listOf(
                row(cell("ts", "2026-07-22 10:00:00.123"), cell("message", ""), cell("enabled", "true")),
                row(cell("ts", "1721613600000"), cell("message", isNull = true), cell("enabled", "false"))
            )
        )
        val snapshot = adapter.inspectWriteTarget(session, "power", request)
        val plan = adapter.buildWritePlan("power", snapshot, request)

        assertEquals("INSERT INTO `power`.`events` (`ts`, `message`, `enabled`) VALUES (?, ?, ?), (?, ?, ?)", plan.sql)
        assertTrue(plan.previewSql.contains("'2026-07-22 10:00:00.123', '', TRUE"))
        assertTrue(plan.previewSql.contains("1721613600000, NULL, FALSE"))
        assertEquals(6, plan.parameters.size)
        assertEquals(null, plan.parameters[4].value)
    }

    @Test
    fun `text timestamp binds as jdbc timestamp while epoch binds as long`() {
        basicMetadata()
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>(relaxed = true)
        every { session.getJdbcConnection() } returns connection
        every { connection.prepareStatement(any()) } returns statement
        every { statement.executeUpdate() } returns 2
        val request = TimeSeriesWriteRequest(
            TimeSeriesWriteTargetKind.BASIC_TABLE,
            table = "events",
            columns = listOf("ts", "message", "enabled"),
            rows = listOf(
                row(cell("ts", "2026-07-22 11:18:16.699"), cell("message", "first"), cell("enabled", "true")),
                row(cell("ts", "1721613600000"), cell("message", "second"), cell("enabled", "false"))
            )
        )
        val snapshot = adapter.inspectWriteTarget(session, "power", request)

        adapter.executeWritePlan(session, adapter.buildWritePlan("power", snapshot, request))

        verify { statement.setTimestamp(1, Timestamp.valueOf("2026-07-22 11:18:16.699")) }
        verify { statement.setLong(4, 1_721_613_600_000L) }
        verify(exactly = 0) { statement.setObject(1, any()) }
    }

    @Test
    fun `new child write builds using tags and requires explicit timestamp`() {
        every { metadata.getTableInfo(session, "power", "meters") } returns TableInfo("meters", tableKind = TableKind.SUPER_TABLE)
        every { metadata.getTableInfo(session, "power", "d1") } throws IllegalArgumentException("not found")
        every { metadata.getColumns(session, "power", "meters") } returns listOf(
            ColumnInfo("ts", "TIMESTAMP", isPrimaryKey = true), ColumnInfo("value", "DOUBLE")
        )
        every { timeSeries.listTagDefinitions(session, "power", "meters") } returns listOf(TimeSeriesTagDefinition("location", "NCHAR(16)"))
        val request = TimeSeriesWriteRequest(
            TimeSeriesWriteTargetKind.NEW_CHILD_TABLE, "d1", "meters", listOf("ts", "value"),
            listOf(row(cell("ts", "2026-07-22 10:00:00"), cell("value", "1.25"))),
            listOf(TimeSeriesTagValueDraft("location", "上海"))
        )
        val snapshot = adapter.inspectWriteTarget(session, "power", request)
        val plan = adapter.buildWritePlan("power", snapshot, request)
        assertTrue(plan.sql.startsWith("INSERT INTO `power`.`d1` USING `power`.`meters` TAGS ('上海')"))
        assertTrue(plan.createsChildTable)

        val missingTimestamp = request.copy(columns = listOf("value"), rows = listOf(row(cell("value", "1"))))
        assertFailsWith<IllegalArgumentException> { adapter.buildWritePlan("power", snapshot, missingTimestamp) }
        val blankTimestamp = request.copy(rows = listOf(row(cell("ts", ""), cell("value", "1"))))
        assertFailsWith<IllegalArgumentException> { adapter.buildWritePlan("power", snapshot, blankTimestamp) }
    }

    @Test
    fun `existing child is inspected remotely and writes directly to that child`() {
        every { metadata.getTableInfo(session, "power", "d1") } returns TableInfo("d1", tableKind = TableKind.CHILD_TABLE)
        every { timeSeries.inspectChildTable(session, "power", "d1") } returns TimeSeriesChildTable(
            name = "d1",
            database = "power",
            stableName = "meters",
            tagValues = emptyList()
        )
        every { metadata.getColumns(session, "power", "d1") } returns listOf(
            ColumnInfo("ts", "TIMESTAMP", isPrimaryKey = true), ColumnInfo("value", "DOUBLE")
        )
        val request = TimeSeriesWriteRequest(
            TimeSeriesWriteTargetKind.EXISTING_CHILD_TABLE,
            "d1",
            columns = listOf("ts", "value"),
            rows = listOf(row(cell("ts", "2026-07-22 10:00:00"), cell("value", "7")))
        )

        val snapshot = adapter.inspectWriteTarget(session, "power", request)
        val plan = adapter.buildWritePlan("power", snapshot, request)

        assertEquals("meters", snapshot.stableName)
        assertEquals("INSERT INTO `power`.`d1` (`ts`, `value`) VALUES (?, ?)", plan.sql)
        assertTrue(!plan.createsChildTable)
    }

    @Test
    fun `whole batch is rejected before execution when any row is invalid or over limit`() {
        basicMetadata()
        val valid = row(cell("ts", "2026-07-22 10:00:00"), cell("message", "ok"), cell("enabled", "true"))
        val request = TimeSeriesWriteRequest(TimeSeriesWriteTargetKind.BASIC_TABLE, "events", columns = listOf("ts", "message", "enabled"), rows = listOf(valid))
        val snapshot = adapter.inspectWriteTarget(session, "power", request)
        assertEquals(100, adapter.buildWritePlan("power", snapshot, request.copy(rows = List(100) { valid })).rowCount)
        assertFailsWith<IllegalArgumentException> { adapter.buildWritePlan("power", snapshot, request.copy(rows = List(101) { valid })) }
        val invalid = request.copy(rows = listOf(valid, row(cell("ts", "2026-07-22 10:00:01"), cell("message", "missing enabled"))))
        assertFailsWith<IllegalArgumentException> { adapter.buildWritePlan("power", snapshot, invalid) }
    }

    @Test
    fun `csv adapter keeps visual limit separate and supports adaptive maximum`() {
        basicMetadata()
        val csv = TdengineTimeSeriesCsvImportAdapter(adapter)
        val config = com.easydb.common.TimeSeriesCsvImportConfig(
            "/tmp/events.csv", TimeSeriesWriteTargetKind.BASIC_TABLE, "events"
        )
        val snapshot = csv.inspectImportTarget(session, "power", config)
        val valid = row(cell("ts", "2026-07-22 10:00:00"), cell("message", "ok"), cell("enabled", "true"))
        val plan = csv.buildImportPlan("power", snapshot, config, listOf("ts", "message", "enabled"), List(5_000) { valid })
        assertEquals(5_000, plan.rowCount)
        assertFailsWith<IllegalArgumentException> {
            csv.buildImportPlan("power", snapshot, config, listOf("ts", "message", "enabled"), List(5_001) { valid })
        }

        assertFailsWith<IllegalArgumentException> {
            adapter.buildWritePlan("power", snapshot, TimeSeriesWriteRequest(
                TimeSeriesWriteTargetKind.BASIC_TABLE, "events",
                columns = listOf("ts", "message", "enabled"), rows = List(101) { valid }
            ))
        }

        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>(relaxed = true)
        every { session.getJdbcConnection() } returns connection
        every { connection.prepareStatement(any()) } returns statement
        every { statement.executeUpdate() } returns 101
        val textTimestamp = valid
        val epochTimestamp = row(cell("ts", "1721613600000"), cell("message", "epoch"), cell("enabled", "false"))
        val rows = listOf(textTimestamp, epochTimestamp) + List(99) { valid }
        csv.executeImportPlan(session, csv.buildImportPlan("power", snapshot, config, listOf("ts", "message", "enabled"), rows))
        verify { statement.setTimestamp(1, Timestamp.valueOf("2026-07-22 10:00:00")) }
        verify { statement.setLong(4, 1_721_613_600_000L) }
    }

    @Test
    fun `new child identifiers reject control characters`() {
        every { metadata.getTableInfo(session, "power", "meters") } returns TableInfo("meters", tableKind = TableKind.SUPER_TABLE)
        val request = TimeSeriesWriteRequest(
            TimeSeriesWriteTargetKind.NEW_CHILD_TABLE,
            "bad\nchild",
            stableName = "meters",
            columns = listOf("ts"),
            rows = listOf(row(cell("ts", "2026-07-22 10:00:00")))
        )
        assertFailsWith<IllegalArgumentException> { adapter.inspectWriteTarget(session, "power", request) }
    }

    private fun basicMetadata() {
        every { metadata.getTableInfo(session, "power", "events") } returns TableInfo("events", tableKind = TableKind.BASIC_TABLE)
        every { metadata.getColumns(session, "power", "events") } returns listOf(
            ColumnInfo("ts", "TIMESTAMP", isPrimaryKey = true), ColumnInfo("message", "VARCHAR(32)"), ColumnInfo("enabled", "BOOL")
        )
    }
    private fun cell(name: String, value: String? = null, isNull: Boolean = false) = TimeSeriesWriteCell(name, value, isNull)
    private fun row(vararg cells: TimeSeriesWriteCell) = TimeSeriesWriteRow(cells.toList())
}
