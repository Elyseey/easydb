package com.easydb.drivers.dameng

import com.easydb.common.ColumnDiff
import com.easydb.common.ColumnInfo
import com.easydb.common.CompareOptions
import com.easydb.common.IndexDiff
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DamengStructureCompareSqlGeneratorTest {
    private val generator = DamengStructureCompareSqlGenerator()

    @Test
    fun `remaps source schema in same-database create ddl`() {
        val sql = generator.createTableSql(
            "CREATE TABLE \"SOURCE\".\"Mixed Table\" (\"ID\" BIGINT);",
            "SOURCE",
            "TARGET",
            "Mixed Table"
        )

        assertContains(sql, "\"TARGET\".\"Mixed Table\"")
        assertFalse(sql.contains("\"SOURCE\""))
    }

    @Test
    fun `generates dameng alter comment and standalone index statements`() {
        val sql = generator.alterTableSql(
            targetDatabase = "TARGET",
            tableName = "Mixed Table",
            sourceColumns = listOf(ColumnInfo("NEW COL", "VARCHAR2(20)")),
            columnDiffs = listOf(
                ColumnDiff(
                    columnName = "NEW COL",
                    status = "added",
                    sourceType = "VARCHAR2(20)",
                    sourceNullable = false,
                    sourceComment = "O'Reilly"
                )
            ),
            indexDiffs = listOf(
                IndexDiff(
                    indexName = "UK_NEW_COL",
                    status = "added",
                    sourceColumns = listOf("NEW COL"),
                    sourceUnique = true
                )
            ),
            options = CompareOptions()
        )

        assertContains(sql, "ALTER TABLE \"TARGET\".\"Mixed Table\" ADD \"NEW COL\" VARCHAR2(20) NOT NULL;")
        assertContains(sql, "COMMENT ON COLUMN \"TARGET\".\"Mixed Table\".\"NEW COL\" IS 'O''Reilly';")
        assertContains(sql, "CREATE UNIQUE INDEX \"UK_NEW_COL\" ON \"TARGET\".\"Mixed Table\" (\"NEW COL\");")
        assertFalse(sql.contains(" AFTER "))
        assertFalse(sql.contains(" FIRST"))
        assertFalse(sql.contains("MODIFY COLUMN"))
        assertFalse(sql.contains("ADD UNIQUE INDEX"))
    }

    @Test
    fun `normalizes equivalent dameng type aliases`() {
        assertTrue(generator.typesEquivalent("varchar(20)", "VARCHAR2(20)"))
        assertTrue(generator.typesEquivalent("decimal(18, 2)", "NUMBER(18, 2)"))
    }
}
