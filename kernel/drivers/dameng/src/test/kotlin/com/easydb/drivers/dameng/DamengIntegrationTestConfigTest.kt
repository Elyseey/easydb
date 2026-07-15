package com.easydb.drivers.dameng

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DamengIntegrationTestConfigTest {
    private val safeEnvironment = mapOf(
        "EASYDB_DM_INTEGRATION_TEST" to "true",
        "EASYDB_DM_JDBC_URL" to "jdbc:dm://dm-test.invalid:5236",
        "EASYDB_DM_USERNAME" to "EASYDB_TEST_USER",
        "EASYDB_DM_PASSWORD" to "unit-test-secret",
        "EASYDB_DM_TEST_SCHEMA" to "visibleSchema"
    )

    @Test
    fun `requires explicit opt in and every connection variable`() {
        val disabled = assertFailsWith<IllegalArgumentException> {
            DamengIntegrationTestConfig.fromEnvironment(emptyMap())
        }
        assertTrue(disabled.message.orEmpty().contains("EASYDB_DM_INTEGRATION_TEST"))

        val missingPassword = assertFailsWith<IllegalArgumentException> {
            DamengIntegrationTestConfig.fromEnvironment(safeEnvironment - "EASYDB_DM_PASSWORD")
        }
        assertTrue(missingPassword.message.orEmpty().contains("EASYDB_DM_PASSWORD"))
        assertFalse(missingPassword.message.orEmpty().contains("unit-test-secret"))
    }

    @Test
    fun `rejects system accounts and credentials embedded in jdbc url`() {
        listOf("SYS", "SYSDBA", "SYSAUDITOR", "SYSSSO", "SYSJOB").forEach { username ->
            assertFailsWith<IllegalArgumentException> {
                DamengIntegrationTestConfig.fromEnvironment(
                    safeEnvironment + ("EASYDB_DM_USERNAME" to username)
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            DamengIntegrationTestConfig.fromEnvironment(
                safeEnvironment + (
                    "EASYDB_DM_JDBC_URL" to
                        "jdbc:dm://dm-test.invalid:5236?user=test&password=secret"
                )
            )
        }
    }

    @Test
    fun `requires a dedicated schema prefix only when ddl is enabled`() {
        val readOnly = DamengIntegrationTestConfig.fromEnvironment(safeEnvironment)
        assertFalse(readOnly.allowDdl)
        assertEquals("visibleSchema", readOnly.schema)

        assertFailsWith<IllegalArgumentException> {
            DamengIntegrationTestConfig.fromEnvironment(
                safeEnvironment + ("EASYDB_DM_ALLOW_DDL" to "true")
            )
        }

        val ddl = DamengIntegrationTestConfig.fromEnvironment(
            safeEnvironment + mapOf(
                "EASYDB_DM_ALLOW_DDL" to "true",
                "EASYDB_DM_TEST_SCHEMA" to "EASYDB_TEST_DRIVER"
            )
        )
        assertTrue(ddl.allowDdl)
    }

    @Test
    fun `redacts password and jdbc url from diagnostic text`() {
        val config = DamengIntegrationTestConfig.fromEnvironment(safeEnvironment)
        val diagnostic = config.toString()

        assertFalse(diagnostic.contains("unit-test-secret"))
        assertFalse(diagnostic.contains("dm-test.invalid"))
        assertTrue(diagnostic.contains("password=<redacted>"))
        assertTrue(diagnostic.contains("jdbcUrl=<redacted>"))
    }
}
