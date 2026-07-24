package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.MetadataPageRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TdengineMetadataPaginationTest {
    @Test
    fun `combined page continues from stables into tables and reports total`() {
        val connection = mockk<Connection>()
        val stableCountStatement = statementReturning(countResult(2))
        val tableCountStatement = statementReturning(countResult(3))
        val stablePageStatement = statementReturning(
            objectResult(
                nameColumn = "stable_name",
                rows = listOf(ObjectRow("MixedCaseStable", "stable comment"))
            )
        )
        val tablePageStatement = statementReturning(
            objectResult(
                nameColumn = "table_name",
                rows = listOf(
                    ObjectRow("Table_A", null),
                    ObjectRow("table_b", "table comment")
                )
            )
        )
        every { connection.createStatement() } returnsMany listOf(
            stableCountStatement,
            tableCountStatement,
            stablePageStatement,
            tablePageStatement
        )
        val session = mockk<DatabaseSession>()
        every { session.getJdbcConnection() } returns connection

        val page = TdengineMetadataAdapter().listTablesPage(
            session,
            "PowerDb",
            MetadataPageRequest(offset = 1, limit = 3)
        )

        assertEquals(listOf("MixedCaseStable", "Table_A", "table_b"), page.items.map { it.name })
        assertEquals(listOf("stable", "table", "table"), page.items.map { it.type })
        assertEquals(5, page.total)
        assertEquals(1, page.offset)
        assertEquals(3, page.limit)
        assertTrue(page.hasMore)
        verify(exactly = 1) {
            stablePageStatement.executeQuery(match { it.contains("LIMIT 1 OFFSET 1") })
            tablePageStatement.executeQuery(match { it.contains("LIMIT 2 OFFSET 0") })
        }
    }

    private fun statementReturning(result: ResultSet): Statement = mockk<Statement>().also { statement ->
        every { statement.executeQuery(any()) } returns result
        every { statement.close() } returns Unit
    }

    private fun countResult(total: Long): ResultSet = mockk<ResultSet>().also { result ->
        every { result.next() } returnsMany listOf(true, false)
        every { result.getLong("total") } returns total
        every { result.close() } returns Unit
    }

    private fun objectResult(nameColumn: String, rows: List<ObjectRow>): ResultSet {
        val result = mockk<ResultSet>()
        var index = -1
        every { result.next() } answers {
            index += 1
            index < rows.size
        }
        every { result.getString(nameColumn) } answers { rows[index].name }
        every { result.getString("db_name") } returns "PowerDb"
        every { result.getString("create_time") } returns "2026-07-21 10:00:00"
        every { result.getString("table_comment") } answers { rows[index].comment }
        every { result.close() } returns Unit
        return result
    }

    private data class ObjectRow(val name: String, val comment: String?)
}
