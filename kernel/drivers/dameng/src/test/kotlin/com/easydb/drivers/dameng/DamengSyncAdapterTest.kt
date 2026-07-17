package com.easydb.drivers.dameng

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DamengSyncAdapterTest {
    @Test
    fun `builds a parameterized merge using the reliable key`() {
        val sql = DamengSyncAdapter().buildMergeSql(
            targetTable = "\"TARGET\".\"users\"",
            columns = listOf("id", "display name"),
            keyColumns = listOf("id")
        )

        assertContains(sql, "MERGE INTO \"TARGET\".\"users\" t")
        assertContains(sql, "SELECT ? AS \"id\", ? AS \"display name\" FROM DUAL")
        assertContains(sql, "ON (t.\"id\" = s.\"id\")")
        assertContains(sql, "WHEN MATCHED THEN UPDATE SET t.\"display name\" = s.\"display name\"")
        assertContains(sql, "WHEN NOT MATCHED THEN INSERT (\"id\", \"display name\")")
        assertFalse(sql.contains("ON DUPLICATE KEY", ignoreCase = true))
    }

    @Test
    fun `omits matched update when every column belongs to the key`() {
        val sql = DamengSyncAdapter().buildMergeSql(
            targetTable = "\"TARGET\".\"keys_only\"",
            columns = listOf("id"),
            keyColumns = listOf("id")
        )

        assertFalse(sql.contains("WHEN MATCHED", ignoreCase = true))
        assertContains(sql, "WHEN NOT MATCHED")
    }
}
