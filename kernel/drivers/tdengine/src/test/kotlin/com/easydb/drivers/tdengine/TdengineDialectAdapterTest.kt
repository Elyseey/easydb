package com.easydb.drivers.tdengine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TdengineDialectAdapterTest {
    private val dialect = TdengineDialectAdapter()

    @Test
    fun `quotes identifiers and switches database`() {
        assertEquals("`a``b`", dialect.quoteIdentifier("a`b"))
        assertEquals("USE `power`", dialect.buildSwitchDatabaseSql("power"))
        assertEquals(null, dialect.buildSwitchDatabaseSql(""))
    }

    @Test
    fun `uses limit offset pagination`() {
        assertEquals(
            "SELECT * FROM (SELECT ts, value FROM meters) _easydb_page LIMIT 200 OFFSET 20",
            dialect.buildPaginationSql("SELECT ts, value FROM meters", 200, 20)
        )
    }

    @Test
    fun `escapes tdengine string literal control characters`() {
        assertEquals("'a\\\\b\\'c\\n\\r\\t'", dialect.escapeValue("a\\b'c\n\r\t"))
        assertEquals("NULL", dialect.escapeValue(null))
    }

    @Test
    fun `builds parameterized insert`() {
        assertEquals(
            "INSERT INTO `d1001` (`ts`, `value`) VALUES (?, ?)",
            dialect.buildInsert("d1001", listOf("ts", "value"))
        )
    }
}
