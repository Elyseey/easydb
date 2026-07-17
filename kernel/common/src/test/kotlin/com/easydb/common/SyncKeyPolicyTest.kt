package com.easydb.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncKeyPolicyTest {
    @Test
    fun `prefers primary key columns in catalog order`() {
        val definition = definition(
            columns = listOf(
                ColumnInfo("TENANT_ID", "BIGINT", nullable = false, isPrimaryKey = true),
                ColumnInfo("ID", "BIGINT", nullable = false, isPrimaryKey = true),
                ColumnInfo("EMAIL", "VARCHAR(100)", nullable = false)
            ),
            indexes = listOf(IndexInfo("UK_EMAIL", listOf("EMAIL"), isUnique = true))
        )

        assertEquals(listOf("TENANT_ID", "ID"), SyncKeyPolicy.reliableKey(definition))
    }

    @Test
    fun `accepts only non-null unique keys when no primary key exists`() {
        val valid = definition(
            columns = listOf(ColumnInfo("CODE", "VARCHAR(20)", nullable = false)),
            indexes = listOf(IndexInfo("UK_CODE", listOf("CODE"), isUnique = true))
        )
        val nullable = definition(
            columns = listOf(ColumnInfo("CODE", "VARCHAR(20)", nullable = true)),
            indexes = listOf(IndexInfo("UK_CODE", listOf("CODE"), isUnique = true))
        )

        assertEquals(listOf("CODE"), SyncKeyPolicy.reliableKey(valid))
        assertNull(SyncKeyPolicy.reliableKey(nullable))
    }

    @Test
    fun `accepts a catalog primary index when column flags are unavailable`() {
        val definition = definition(
            columns = listOf(ColumnInfo("ID", "BIGINT", nullable = false)),
            indexes = listOf(IndexInfo("SYS_PK_42", listOf("ID"), isUnique = true, isPrimary = true))
        )

        assertEquals(listOf("ID"), SyncKeyPolicy.reliableKey(definition))
        assertTrue(SyncKeyPolicy.hasReliableKey(definition, listOf("ID")))
    }

    @Test
    fun `checks that target enforces the selected source key`() {
        val target = definition(
            columns = listOf(
                ColumnInfo("ID", "BIGINT", nullable = false),
                ColumnInfo("CODE", "VARCHAR(20)", nullable = false)
            ),
            indexes = listOf(IndexInfo("UK_CODE", listOf("CODE"), isUnique = true))
        )

        assertTrue(SyncKeyPolicy.hasReliableKey(target, listOf("CODE")))
        assertFalse(SyncKeyPolicy.hasReliableKey(target, listOf("ID")))
    }

    private fun definition(columns: List<ColumnInfo>, indexes: List<IndexInfo>) = TableDefinition(
        table = TableInfo("T"),
        columns = columns,
        indexes = indexes
    )
}
