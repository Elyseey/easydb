package com.easydb.drivers.tdengine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdengineIntegrationTestConfigTest {
    private val safeEnvironment = mapOf(
        "EASYDB_TDENGINE_INTEGRATION_TEST" to "true",
        "EASYDB_TDENGINE_JDBC_URL" to "jdbc:TAOS-WS://td-test.invalid:6041/easydb_test",
        "EASYDB_TDENGINE_USERNAME" to "easydb_test_user",
        "EASYDB_TDENGINE_PASSWORD" to "unit-test-secret",
        "EASYDB_TDENGINE_TEST_DATABASE" to "easydb_test",
        "EASYDB_TDENGINE_PRECISION_DATABASES" to
            "ms=easydb_test_ms,us=easydb_test_us,ns=easydb_test_ns"
    )

    @Test
    fun `requires explicit opt in and every variable`() {
        val disabled = assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(emptyMap())
        }
        assertTrue(disabled.message.orEmpty().contains("EASYDB_TDENGINE_INTEGRATION_TEST"))

        val missingPassword = assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(
                safeEnvironment - "EASYDB_TDENGINE_PASSWORD"
            )
        }
        assertTrue(missingPassword.message.orEmpty().contains("EASYDB_TDENGINE_PASSWORD"))
        assertFalse(missingPassword.message.orEmpty().contains("unit-test-secret"))
    }

    @Test
    fun `requires websocket url without credentials and dedicated user`() {
        assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(
                safeEnvironment + ("EASYDB_TDENGINE_JDBC_URL" to "jdbc:TAOS://td-test.invalid:6030/easydb_test")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(
                safeEnvironment + ("EASYDB_TDENGINE_JDBC_URL" to "jdbc:TAOS-WS://td-test.invalid:6041/easydb_test?user=x&password=y")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(
                safeEnvironment + ("EASYDB_TDENGINE_USERNAME" to "root")
            )
        }
    }

    @Test
    fun `redacts connection target and password`() {
        val config = TdengineIntegrationTestConfig.fromEnvironment(safeEnvironment)
        val diagnostic = config.toString()
        val connectionConfig = config.toConnectionConfig()

        assertEquals("easydb_test", config.database)
        assertEquals("easydb_test_ns", config.precisionDatabases["ns"])
        assertEquals("td-test.invalid", connectionConfig.host)
        assertEquals(6041, connectionConfig.port)
        assertEquals("easydb_test", connectionConfig.database)
        assertFalse(diagnostic.contains("td-test.invalid"))
        assertFalse(diagnostic.contains("unit-test-secret"))
        assertTrue(diagnostic.contains("jdbcUrl=<redacted>"))
        assertTrue(diagnostic.contains("password=<redacted>"))
    }

    @Test
    fun `ddl mode requires an explicitly safe test database`() {
        assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(
                safeEnvironment +
                    ("EASYDB_TDENGINE_ALLOW_DDL" to "true") +
                    ("EASYDB_TDENGINE_TEST_DATABASE" to "production")
            )
        }

        val config = TdengineIntegrationTestConfig.fromEnvironment(
            safeEnvironment + ("EASYDB_TDENGINE_ALLOW_DDL" to "true")
        )
        assertTrue(config.allowDdl)
    }

    @Test
    fun `requires an explicit and complete precision database matrix`() {
        assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(
                safeEnvironment + ("EASYDB_TDENGINE_PRECISION_DATABASES" to "ms=one,us=two")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TdengineIntegrationTestConfig.fromEnvironment(
                safeEnvironment +
                    ("EASYDB_TDENGINE_ALLOW_DDL" to "true") +
                    ("EASYDB_TDENGINE_PRECISION_DATABASES" to
                        "ms=easydb_test_ms,us=production_us,ns=easydb_test_ns")
            )
        }
    }
}
