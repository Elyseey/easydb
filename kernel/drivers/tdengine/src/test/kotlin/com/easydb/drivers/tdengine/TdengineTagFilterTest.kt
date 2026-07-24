package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesChildTableQuery
import com.easydb.common.TimeSeriesTagFilter
import com.easydb.common.TimeSeriesTagFilterOperator
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TdengineTagFilterTest {
    @Test
    fun `filters use typed AND semantics before server-side page materialization`() {
        val connection = mockk<Connection>()
        every { connection.createStatement() } returnsMany listOf(
            statementReturning(rows(
                mapOf("tag_name" to "group_id", "tag_type" to "INT"),
                mapOf("tag_name" to "location", "tag_type" to "VARCHAR(16)")
            )),
            statementReturning(rows(
                tagRow("d1", "location", "VARCHAR(16)", "north"),
                tagRow("d1", "group_id", "INT", "5"),
                tagRow("d2", "location", "VARCHAR(16)", "north"),
                tagRow("d2", "group_id", "INT", "2"),
                tagRow("d3", "location", "VARCHAR(16)", "south"),
                tagRow("d3", "group_id", "INT", "7")
            )),
            statementReturning(rows(
                mapOf(
                    "table_name" to "d1",
                    "stable_name" to "meters",
                    "create_time" to "2026-07-21 12:00:00",
                    "table_comment" to "sensor",
                    "ttl_value" to 30
                )
            )),
            statementReturning(rows(
                tagRow("d1", "group_id", "INT", "5"),
                tagRow("d1", "location", "VARCHAR(16)", "north")
            ))
        )
        val session = mockk<DatabaseSession>()
        every { session.getJdbcConnection() } returns connection

        val page = TdengineMetadataAdapter().queryChildTables(
            session,
            "power",
            "meters",
            TimeSeriesChildTableQuery(
                filters = listOf(
                    TimeSeriesTagFilter("location", TimeSeriesTagFilterOperator.CONTAINS, "north"),
                    TimeSeriesTagFilter("group_id", TimeSeriesTagFilterOperator.GTE, "5")
                )
            )
        )

        assertEquals(listOf("d1"), page.items.map { it.name })
        assertEquals(30, page.items.single().ttl)
        assertEquals(2, page.items.single().tagValues.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `rejects operators that do not match current tag definitions`() {
        val connection = mockk<Connection>()
        every { connection.createStatement() } returns statementReturning(rows(
            mapOf("tag_name" to "enabled", "tag_type" to "BOOL")
        ))
        val session = mockk<DatabaseSession>()
        every { session.getJdbcConnection() } returns connection

        assertFailsWith<IllegalArgumentException> {
            TdengineMetadataAdapter().queryChildTables(
                session,
                "power",
                "meters",
                TimeSeriesChildTableQuery(
                    filters = listOf(TimeSeriesTagFilter("enabled", TimeSeriesTagFilterOperator.GT, "true"))
                )
            )
        }
    }

    private fun tagRow(
        table: String,
        name: String,
        type: String,
        value: String?
    ): Map<String, Any?> = mapOf(
        "table_name" to table,
        "tag_name" to name,
        "tag_type" to type,
        "tag_value" to value
    )

    private fun statementReturning(result: ResultSet): Statement = mockk<Statement>().also { statement ->
        every { statement.executeQuery(any()) } returns result
        every { statement.close() } returns Unit
    }

    private fun rows(vararg values: Map<String, Any?>): ResultSet {
        val result = mockk<ResultSet>()
        var index = -1
        var lastValue: Any? = null
        every { result.next() } answers {
            index += 1
            index < values.size
        }
        every { result.getString(any<String>()) } answers {
            lastValue = values[index][firstArg<String>()]
            lastValue as String?
        }
        every { result.getInt(any<String>()) } answers {
            lastValue = values[index][firstArg<String>()]
            (lastValue as? Number)?.toInt() ?: 0
        }
        every { result.wasNull() } answers { lastValue == null }
        every { result.close() } returns Unit
        return result
    }
}
