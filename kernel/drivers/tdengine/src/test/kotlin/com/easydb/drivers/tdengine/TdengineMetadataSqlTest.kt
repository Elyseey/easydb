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
        assertTrue(sql.contains("`ttl` AS ttl_value"))
        assertTrue(sql.contains("LIMIT 201 OFFSET 10000"))
        assertFalse(sql.contains("CHILD_TABLE\nORDER BY"))
    }

    @Test
    fun `object page query filters in catalog and orders deterministically`() {
        val stableSql = TdengineMetadataSql.listStablesPage("power", "00007", 100, 100)
        val tableSql = TdengineMetadataSql.listBasicTablesPage("power", "00007", 100, 100)
        val countSql = TdengineMetadataSql.countBasicTables("power", "00007")

        assertTrue(stableSql.contains("stable_name LIKE '%00007%'"))
        assertTrue(stableSql.contains("ORDER BY stable_name"))
        assertTrue(tableSql.contains("table_name LIKE '%00007%'"))
        assertTrue(tableSql.contains("ORDER BY table_name"))
        assertTrue(tableSql.contains("LIMIT 100 OFFSET 100"))
        assertTrue(countSql.contains("COUNT(*) AS total"))
        assertFalse(countSql.contains("LIMIT"))
        assertFalse(stableSql.contains("UNION", ignoreCase = true))
        assertFalse(tableSql.contains("UNION", ignoreCase = true))
    }

    @Test
    fun `object page catalog queries stay separated for legacy compatibility`() {
        val stableSql = TdengineMetadataSql.listStablesPage("power", null, 0, 100)
        val tableSql = TdengineMetadataSql.listBasicTablesPage("power", null, 0, 100)

        assertTrue(stableSql.contains("information_schema.ins_stables"))
        assertFalse(stableSql.contains("information_schema.ins_tables"))
        assertTrue(tableSql.contains("information_schema.ins_tables"))
        assertFalse(tableSql.contains("information_schema.ins_stables"))
    }

    @Test
    fun `object search escapes catalog string literals`() {
        val sql = TdengineMetadataSql.listBasicTablesPage("prod's", "meter_100%\\x'", 0, 100)

        assertTrue(sql.contains("db_name = 'prod''s'"))
        assertTrue(sql.contains("LIKE '%meter\\_100\\%\\\\x''%'"))
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

    @Test
    fun `tag filter candidate query is bounded to requested tags and escapes search`() {
        val sql = TdengineMetadataSql.listTagFilterCandidates(
            database = "power",
            stable = "meters",
            tagNames = listOf("location", "group_id"),
            search = "d_100%"
        )

        assertTrue(sql.contains("tag_name IN ('location', 'group_id')"))
        assertTrue(sql.contains("table_name LIKE '%d\\_100\\%%'"))
        assertTrue(sql.contains("ORDER BY table_name, tag_name"))
        assertFalse(sql.contains("SELECT *", ignoreCase = true))
    }

    @Test
    fun `stable child count is exact and scoped to child table kind`() {
        val sql = TdengineMetadataSql.countChildTables("prod's", "Meters")

        assertTrue(sql.contains("COUNT(*) AS total"))
        assertTrue(sql.contains("db_name = 'prod''s'"))
        assertTrue(sql.contains("stable_name = 'Meters'"))
        assertTrue(sql.contains("type = 'CHILD_TABLE'"))
    }
}
