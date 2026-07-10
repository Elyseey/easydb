package com.easydb.common

import kotlin.test.Test
import kotlin.test.assertEquals

class DataEditServiceTest {
    private val dialect = object : DialectAdapter {
        override fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""
        override fun buildCreateTable(table: TableDefinition): String = error("not used")
        override fun buildInsert(tableName: String, columns: List<String>): String =
            buildInsertSql(tableName, columns)
        override fun buildSwitchDatabaseSql(database: String): String? = null
        override val paginationStrategy: PaginationStrategy = PaginationStrategy.OFFSET_FETCH
    }

    @Test
    fun `update binds multiline json instead of embedding it in executable sql`() {
        val json = """{
          "isEnable": true,
          "label": "owner's device"
        }""".trimIndent()

        val statements = DataEditService().generateStatements(
            dialect = dialect,
            tableName = "device_config",
            changes = listOf(
                RowChange(
                    type = "update",
                    primaryKeys = linkedMapOf("id" to "42"),
                    values = linkedMapOf("params" to json),
                    oldValues = linkedMapOf("params" to "{}")
                )
            )
        )

        assertEquals(1, statements.size)
        assertEquals(
            "UPDATE \"device_config\" SET \"params\" = ? WHERE \"id\" = ?",
            statements.single().sql
        )
        assertEquals(listOf(json, "42"), statements.single().parameters)
        assertEquals(false, statements.single().sql.contains(json))
        assertEquals(true, statements.single().previewSql.contains("owner''s device"))
    }

    @Test
    fun `insert and delete preserve placeholder parameter order including null`() {
        val statements = DataEditService().generateStatements(
            dialect = dialect,
            tableName = "device_config",
            changes = listOf(
                RowChange(
                    type = "insert",
                    values = linkedMapOf("id" to "7", "params" to null)
                ),
                RowChange(
                    type = "delete",
                    primaryKeys = linkedMapOf("tenant" to "acme", "id" to "7")
                )
            )
        )

        assertEquals(
            "INSERT INTO \"device_config\" (\"id\", \"params\") VALUES (?, ?)",
            statements[0].sql
        )
        assertEquals(listOf("7", null), statements[0].parameters)
        assertEquals(
            "DELETE FROM \"device_config\" WHERE \"tenant\" = ? AND \"id\" = ?",
            statements[1].sql
        )
        assertEquals(listOf("acme", "7"), statements[1].parameters)
    }
}
