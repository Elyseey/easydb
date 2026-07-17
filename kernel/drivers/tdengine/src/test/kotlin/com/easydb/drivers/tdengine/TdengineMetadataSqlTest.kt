package com.easydb.drivers.tdengine

import com.easydb.common.TimeSeriesMetadataLimits
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdengineMetadataSqlTest {

    @Test
    fun `catalog queries use explicit columns`() {
        val dynamicCatalogQueries = listOf(
            TdengineMetadataSql.listChildTables(
                database = "power",
                stable = "meters",
                search = "d1001",
                offset = 0,
                limit = TimeSeriesMetadataLimits.MAX_CHILD_TABLE_PAGE_SIZE
            ),
            TdengineMetadataSql.listTagsForTables("power", "meters", listOf("d1", "d2", "d3"))
        )

        (TdengineMetadataSql.catalogQueries + dynamicCatalogQueries).forEach { sql ->
            assertFalse(sql.contains("SELECT *", ignoreCase = true), sql)
        }
    }

    @Test
    fun `child table query is database paged with one row lookahead`() {
        val sql = TdengineMetadataSql.listChildTables(
            database = "power",
            stable = "meters",
            search = "d1001",
            offset = 10_000,
            limit = TimeSeriesMetadataLimits.MAX_CHILD_TABLE_PAGE_SIZE
        )

        assertTrue(sql.contains("table_name LIKE '%d1001%'"))
        assertTrue(sql.contains("LIMIT 201 OFFSET 10000"))
        assertFalse(sql.contains("CHILD_TABLE\nORDER BY"))
    }

    @Test
    fun `tag batch query has one placeholder per page item`() {
        val sql = TdengineMetadataSql.listTagsForTables("power", "meters", listOf("d1", "d2", "d3"))

        assertTrue(sql.contains("table_name IN ('d1', 'd2', 'd3')"))
        assertEquals(0, sql.count { it == '?' })
    }

    @Test
    fun `catalog string literals escape quotes`() {
        assertEquals("'prod''s'", TdengineMetadataSql.stringLiteral("prod's"))
    }
}
