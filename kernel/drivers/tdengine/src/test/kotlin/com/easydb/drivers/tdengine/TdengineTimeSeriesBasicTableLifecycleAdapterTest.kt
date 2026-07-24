package com.easydb.drivers.tdengine

import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableInfo
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesBasicTableCommand
import com.easydb.common.TimeSeriesBasicTableOperation
import com.easydb.common.TimeSeriesDataType
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TdengineTimeSeriesBasicTableLifecycleAdapterTest {
    private val metadata = mockk<MetadataAdapter>()
    private val session = mockk<DatabaseSession>()
    private val adapter = TdengineTimeSeriesBasicTableLifecycleAdapter(metadata)

    @Test
    fun `inspect only accepts basic tables and all operations build one quoted ddl`() {
        every { metadata.getTableInfo(session, "power", "events") } returns TableInfo("events", tableKind = TableKind.BASIC_TABLE)
        every { metadata.getColumns(session, "power", "events") } returns listOf(
            ColumnInfo("ts", "TIMESTAMP", isPrimaryKey = true),
            ColumnInfo("value", "DOUBLE"),
            ColumnInfo("payload", "VARCHAR(16)")
        )
        val snapshot = adapter.inspectBasicTable(session, "power", "events")
        val cases = listOf(
            TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.ADD_COLUMN, "enabled", TimeSeriesDataType.BOOL) to
                "ALTER TABLE `power`.`events` ADD COLUMN `enabled` BOOL",
            TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.DROP_COLUMN, "value") to
                "ALTER TABLE `power`.`events` DROP COLUMN `value`",
            TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.MODIFY_COLUMN, "payload", TimeSeriesDataType.VARCHAR, 32) to
                "ALTER TABLE `power`.`events` MODIFY COLUMN `payload` VARCHAR(32)",
            TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.RENAME_COLUMN, "value", newName = "reading") to
                "ALTER TABLE `power`.`events` RENAME COLUMN `value` `reading`"
        )
        cases.forEach { (command, ddl) -> assertEquals(ddl, adapter.buildBasicTableMutationSql("power", "events", snapshot, command)) }
    }

    @Test
    fun `primary timestamp shrinking and 3_0_4 row widths are rejected`() {
        every { metadata.getTableInfo(session, "power", "events") } returns TableInfo("events", tableKind = TableKind.BASIC_TABLE)
        every { metadata.getColumns(session, "power", "events") } returns listOf(
            ColumnInfo("ts", "TIMESTAMP", isPrimaryKey = true),
            ColumnInfo("payload", "BINARY(16)"),
            ColumnInfo("value", "DOUBLE")
        )
        val snapshot = adapter.inspectBasicTable(session, "power", "events")
        assertFailsWith<IllegalArgumentException> {
            adapter.buildBasicTableMutationSql("power", "events", snapshot, TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.DROP_COLUMN, "ts"))
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildBasicTableMutationSql("power", "events", snapshot, TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.ADD_COLUMN, "other_ts", TimeSeriesDataType.TIMESTAMP))
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildBasicTableMutationSql("power", "events", snapshot, TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.MODIFY_COLUMN, "payload", TimeSeriesDataType.BINARY, 8))
        }
        val wide = assertFailsWith<IllegalArgumentException> {
            adapter.buildBasicTableMutationSql("power", "events", snapshot, TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.ADD_COLUMN, "huge", TimeSeriesDataType.BINARY, 49_140))
        }
        assertTrue(wide.message.orEmpty().contains("48 KB"))
    }
}
