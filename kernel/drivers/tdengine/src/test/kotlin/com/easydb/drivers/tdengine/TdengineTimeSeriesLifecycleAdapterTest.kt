package com.easydb.drivers.tdengine

import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableInfo
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesChildPropertyCommand
import com.easydb.common.TimeSeriesChildPropertyOperation
import com.easydb.common.TimeSeriesChildTable
import com.easydb.common.TimeSeriesDeleteObjectKind
import com.easydb.common.TimeSeriesLifecycleCommand
import com.easydb.common.TimeSeriesLifecycleField
import com.easydb.common.TimeSeriesLifecycleOperation
import com.easydb.common.TimeSeriesLifecycleSnapshot
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagValue
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TdengineTimeSeriesLifecycleAdapterTest {
    private val metadata = mockk<MetadataAdapter>()
    private val timeSeriesMetadata = mockk<TimeSeriesMetadataAdapter>()
    private val session = mockk<DatabaseSession>()
    private val adapter = TdengineTimeSeriesLifecycleAdapter(metadata, timeSeriesMetadata)

    @Test
    fun `delete inspection distinguishes all object kinds and stable child count changes fingerprint`() {
        every { metadata.getTableInfo(session, "power", "basic") } returns
            TableInfo("basic", updateTime = "2026-01-01", tableKind = TableKind.BASIC_TABLE)
        every { metadata.getTableInfo(session, "power", "d1") } returns
            TableInfo("d1", updateTime = "2026-01-02", tableKind = TableKind.CHILD_TABLE)
        every { timeSeriesMetadata.inspectChildTable(session, "power", "d1") } returns
            TimeSeriesChildTable("d1", "power", "Meters")
        every { metadata.getTableInfo(session, "power", "Meters") } returns
            TableInfo("Meters", updateTime = "2026-01-03", tableKind = TableKind.SUPER_TABLE)
        every { timeSeriesMetadata.countChildTables(session, "power", "Meters") } returnsMany listOf(2L, 3L)

        val basic = adapter.inspectDelete(session, "power", "basic")
        val child = adapter.inspectDelete(session, "power", "d1")
        val stable = adapter.inspectDelete(session, "power", "Meters")
        val changed = adapter.inspectDelete(session, "power", "Meters")

        assertEquals(TimeSeriesDeleteObjectKind.BASIC_TABLE, basic.kind)
        assertEquals("DROP TABLE `power`.`basic`", adapter.buildDeleteSql("power", "basic", basic))
        assertEquals("Meters", child.stableName)
        assertEquals(TimeSeriesDeleteObjectKind.CHILD_TABLE, child.kind)
        assertEquals(2, stable.affectedChildTables)
        assertEquals("DROP STABLE `power`.`Meters`", adapter.buildDeleteSql("power", "Meters", stable))
        assertNotEquals(stable.fingerprint, changed.fingerprint)
    }

    @Test
    fun `inspect returns an exact snapshot with a stable structural fingerprint`() {
        every { metadata.getTableInfo(session, "power", "Meters") } returns
            TableInfo("Meters", tableKind = TableKind.SUPER_TABLE)
        every { metadata.getColumns(session, "power", "Meters") } returns listOf(
            ColumnInfo("ts", "TIMESTAMP", isPrimaryKey = true),
            ColumnInfo("payload", "BINARY(16)")
        )
        every { timeSeriesMetadata.listTagDefinitions(session, "power", "Meters") } returnsMany listOf(
            listOf(TimeSeriesTagDefinition("location", "NCHAR(32)"), TimeSeriesTagDefinition("group_id", "INT")),
            listOf(TimeSeriesTagDefinition("group_id", "INT"), TimeSeriesTagDefinition("location", "NCHAR(32)")),
            listOf(TimeSeriesTagDefinition("group_id", "INT"), TimeSeriesTagDefinition("location", "NCHAR(32)"))
        )
        every { timeSeriesMetadata.countChildTables(session, "power", "Meters") } returnsMany listOf(2L, 2L, 3L)

        val first = adapter.inspect(session, "power", "Meters")
        val reordered = adapter.inspect(session, "power", "Meters")
        val childCountChanged = adapter.inspect(session, "power", "Meters")

        assertEquals("Meters", first.stable)
        assertTrue(first.columns.first().primaryTimestamp)
        assertEquals(16, first.columns.last().length)
        assertEquals(2, first.affectedChildTables)
        assertEquals(first.fingerprint, reordered.fingerprint)
        assertNotEquals(first.fingerprint, childCountChanged.fingerprint)

        val changed = snapshot(tags = first.tags + TimeSeriesLifecycleField("site", "VARCHAR", 16))
        assertNotEquals(first.fingerprint, changed.fingerprint)
    }

    @Test
    fun `builds every supported operation as one quoted alter stable statement`() {
        val snapshot = snapshot()
        val cases = listOf(
            command(TimeSeriesLifecycleOperation.ADD_COLUMN, "temperature", TimeSeriesDataType.FLOAT) to
                "ALTER STABLE `power`.`Meters` ADD COLUMN `temperature` FLOAT",
            command(TimeSeriesLifecycleOperation.DROP_COLUMN, "value") to
                "ALTER STABLE `power`.`Meters` DROP COLUMN `value`",
            command(TimeSeriesLifecycleOperation.MODIFY_COLUMN, "payload", TimeSeriesDataType.BINARY, 64) to
                "ALTER STABLE `power`.`Meters` MODIFY COLUMN `payload` BINARY(64)",
            command(TimeSeriesLifecycleOperation.ADD_TAG, "site", TimeSeriesDataType.VARCHAR, 64) to
                "ALTER STABLE `power`.`Meters` ADD TAG `site` VARCHAR(64)",
            command(TimeSeriesLifecycleOperation.DROP_TAG, "group_id") to
                "ALTER STABLE `power`.`Meters` DROP TAG `group_id`",
            command(TimeSeriesLifecycleOperation.MODIFY_TAG, "location", TimeSeriesDataType.NCHAR, 64) to
                "ALTER STABLE `power`.`Meters` MODIFY TAG `location` NCHAR(64)",
            command(TimeSeriesLifecycleOperation.RENAME_TAG, "location", newName = "site") to
                "ALTER STABLE `power`.`Meters` RENAME TAG `location` `site`"
        )

        cases.forEach { (command, expected) ->
            assertEquals(expected, adapter.buildMutationSql("power", "Meters", snapshot, command))
        }
    }

    @Test
    fun `rejects primary timestamp destructive last tag shrinking and conflicting changes`() {
        val snapshot = snapshot()

        val primary = assertFailsWith<IllegalArgumentException> {
            adapter.buildMutationSql(
                "power",
                "Meters",
                snapshot,
                command(TimeSeriesLifecycleOperation.DROP_COLUMN, "ts")
            )
        }
        assertTrue(primary.message.orEmpty().contains("主时间戳"))

        val shorter = assertFailsWith<IllegalArgumentException> {
            adapter.buildMutationSql(
                "power",
                "Meters",
                snapshot,
                command(TimeSeriesLifecycleOperation.MODIFY_COLUMN, "payload", TimeSeriesDataType.BINARY, 8)
            )
        }
        assertTrue(shorter.message.orEmpty().contains("必须大于当前长度"))

        assertFailsWith<IllegalArgumentException> {
            adapter.buildMutationSql(
                "power",
                "Meters",
                snapshot,
                command(TimeSeriesLifecycleOperation.MODIFY_COLUMN, "payload", TimeSeriesDataType.NCHAR, 64)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildMutationSql(
                "power",
                "Meters",
                snapshot.copy(tags = snapshot.tags.take(1)),
                command(TimeSeriesLifecycleOperation.DROP_TAG, "location")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildMutationSql(
                "power",
                "Meters",
                snapshot,
                command(TimeSeriesLifecycleOperation.RENAME_TAG, "location", newName = "value")
            )
        }
    }

    @Test
    fun `rejects ambiguous command shapes mismatched snapshots and non supertables`() {
        val ambiguous = command(TimeSeriesLifecycleOperation.DROP_TAG, "location", TimeSeriesDataType.INT)
        assertFailsWith<IllegalArgumentException> {
            adapter.buildMutationSql("power", "Meters", snapshot(), ambiguous)
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildMutationSql("power", "Other", snapshot(), command(TimeSeriesLifecycleOperation.DROP_TAG, "group_id"))
        }

        every { metadata.getTableInfo(session, "power", "basic") } returns
            TableInfo("basic", tableKind = TableKind.BASIC_TABLE)
        assertFailsWith<IllegalArgumentException> {
            adapter.inspect(session, "power", "basic")
        }
    }

    @Test
    fun `child properties preserve null empty comment and build one alter table statement`() {
        every { timeSeriesMetadata.inspectChildTable(session, "power", "d1") } returns TimeSeriesChildTable(
            name = "d1",
            database = "power",
            stableName = "Meters",
            tagValues = listOf(
                TimeSeriesTagValue("location", "VARCHAR(16)", ""),
                TimeSeriesTagValue("group_id", "INT", null)
            ),
            comment = "",
            ttl = 30
        )
        val child = adapter.inspectChildProperties(session, "power", "d1")

        assertEquals("", child.tagValues.first().value)
        assertEquals(null, child.tagValues.last().value)
        assertEquals("", child.comment)
        assertEquals(30, child.ttl)
        assertEquals(
            "ALTER TABLE `power`.`d1` SET TAG `location` = ''",
            adapter.buildChildPropertyMutationSql(
                "power",
                "d1",
                child,
                TimeSeriesChildPropertyCommand(
                    operation = TimeSeriesChildPropertyOperation.SET_TAG,
                    tagName = "location",
                    value = "",
                    isNull = false
                )
            )
        )
        assertEquals(
            "ALTER TABLE `power`.`d1` SET TAG `group_id` = NULL",
            adapter.buildChildPropertyMutationSql(
                "power",
                "d1",
                child,
                TimeSeriesChildPropertyCommand(
                    operation = TimeSeriesChildPropertyOperation.SET_TAG,
                    tagName = "group_id",
                    isNull = true
                )
            )
        )
        assertEquals(
            "ALTER TABLE `power`.`d1` TTL 0",
            adapter.buildChildPropertyMutationSql(
                "power",
                "d1",
                child,
                TimeSeriesChildPropertyCommand(TimeSeriesChildPropertyOperation.SET_TTL, ttl = 0)
            )
        )
        assertEquals(
            "ALTER TABLE `power`.`d1` COMMENT ''",
            adapter.buildChildPropertyMutationSql(
                "power",
                "d1",
                child,
                TimeSeriesChildPropertyCommand(TimeSeriesChildPropertyOperation.SET_COMMENT, comment = "")
            )
        )
    }

    @Test
    fun `child property builder rejects ambiguous values and bounds`() {
        val child = com.easydb.common.TimeSeriesChildPropertySnapshot(
            database = "power",
            table = "d1",
            stableName = "Meters",
            tagValues = listOf(TimeSeriesTagValue("group_id", "INT", "1")),
            ttl = 0,
            fingerprint = "child-v1"
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.buildChildPropertyMutationSql(
                "power", "d1", child,
                TimeSeriesChildPropertyCommand(TimeSeriesChildPropertyOperation.SET_TAG, tagName = "group_id")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildChildPropertyMutationSql(
                "power", "d1", child,
                TimeSeriesChildPropertyCommand(TimeSeriesChildPropertyOperation.SET_TTL, ttl = -1)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildChildPropertyMutationSql(
                "power", "d1", child,
                TimeSeriesChildPropertyCommand(TimeSeriesChildPropertyOperation.SET_COMMENT, comment = "x".repeat(1025))
            )
        }
    }

    private fun snapshot(
        tags: List<TimeSeriesLifecycleField> = listOf(
            TimeSeriesLifecycleField("location", "NCHAR", 32),
            TimeSeriesLifecycleField("group_id", "INT")
        )
    ) = TimeSeriesLifecycleSnapshot(
        database = "power",
        stable = "Meters",
        columns = listOf(
            TimeSeriesLifecycleField("ts", "TIMESTAMP", primaryTimestamp = true),
            TimeSeriesLifecycleField("value", "DOUBLE"),
            TimeSeriesLifecycleField("payload", "BINARY", 16)
        ),
        tags = tags,
        fingerprint = "snapshot-v1"
    )

    private fun command(
        operation: TimeSeriesLifecycleOperation,
        name: String,
        type: TimeSeriesDataType? = null,
        length: Int? = null,
        newName: String? = null
    ) = TimeSeriesLifecycleCommand(operation, name, type, length, newName)
}
