package com.easydb.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DamengSourceMigrationAdapterTest {

    @Test
    fun `both dameng source targets support every required migration mode`() {
        DamengSourceMigrationAdapter.Target.entries.forEach { target ->
            listOf("structure_and_data", "structure_only", "data_only").forEach {
                DamengSourceMigrationAdapter.validateMode(it)
                assertTrue(target.qualified("Mixed Schema", "Order Items").isNotBlank())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DamengSourceMigrationAdapter.validateMode("objects_only")
        }
    }

    @Test
    fun `maps dameng character lob numeric and temporal types conservatively to mysql`() {
        assertEquals("VARCHAR(64)", DamengSourceMigrationAdapter.mapDamengTypeToMysql("VARCHAR2(64)"))
        assertEquals("LONGTEXT", DamengSourceMigrationAdapter.mapDamengTypeToMysql("CLOB"))
        assertEquals("LONGBLOB", DamengSourceMigrationAdapter.mapDamengTypeToMysql("BLOB"))
        assertEquals("DECIMAL(18,2)", DamengSourceMigrationAdapter.mapDamengTypeToMysql("NUMBER(18,2)"))
        assertEquals("DECIMAL(65,27)", DamengSourceMigrationAdapter.mapDamengTypeToMysql("NUMBER"))
        assertEquals("DATETIME", DamengSourceMigrationAdapter.mapDamengTypeToMysql("TIMESTAMP"))
        assertEquals("VARCHAR(255)", DamengSourceMigrationAdapter.mapDamengTypeToMysql("INTERVAL DAY"))
        assertEquals("LONGTEXT", DamengSourceMigrationAdapter.mapDamengTypeToMysql("CUSTOM_DM_TYPE"))
    }

    @Test
    fun `preserves dameng type text for dameng targets`() {
        assertEquals("VARCHAR2(32)", DamengSourceMigrationAdapter.preserveDamengType(" VARCHAR2(32) "))
        assertEquals(
            "CURRENT_TIMESTAMP(6)",
            DamengSourceMigrationAdapter.Target.DAMENG.normalizeDefault(" CURRENT_TIMESTAMP(6) ")
        )
        assertEquals(
            "CURRENT_TIMESTAMP(6)",
            DamengSourceMigrationAdapter.Target.MYSQL.normalizeDefault(" CURRENT_TIMESTAMP(6) ")
        )
    }

    @Test
    fun `target quoting preserves mixed case and spaces`() {
        assertEquals(
            "`Mixed Schema`.`Order Items`",
            DamengSourceMigrationAdapter.Target.MYSQL.qualified("Mixed Schema", "Order Items")
        )
        assertEquals(
            "\"Mixed Schema\".\"Order Items\"",
            DamengSourceMigrationAdapter.Target.DAMENG.qualified("Mixed Schema", "Order Items")
        )
        assertTrue(DamengSourceMigrationAdapter.Target.DAMENG.mapType("Number(18,2)").contains("Number"))
    }
}
