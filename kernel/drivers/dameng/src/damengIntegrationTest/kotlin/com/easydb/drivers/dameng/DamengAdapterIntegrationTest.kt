package com.easydb.drivers.dameng

import com.easydb.common.ConnectionConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DamengAdapterIntegrationTest {
    private val config = DamengIntegrationTestConfig.fromEnvironment()
    private val metadata = DamengMetadataAdapter()
    private val procedures = DamengProcedureAdapter()
    private val dialect = DamengDialectAdapter()

    @Test
    fun `reads visible metadata with a non-system account`() = withSession { session ->
        assertTrue(session.isValid(), "The dedicated Dameng test connection must be valid")

        val visibleObjectCount = session.connection.prepareStatement(
            "SELECT COUNT(*) FROM ALL_OBJECTS WHERE OWNER = ?"
        ).use { statement ->
            statement.setString(1, config.schema)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getLong(1)
            }
        }
        assertTrue(
            visibleObjectCount > 0,
            "The configured test schema must expose at least one object through ALL_OBJECTS"
        )

        assertTrue(metadata.listDatabases(session).any { it.name == config.schema })
        assertTrue(metadata.listRoutines(session, config.schema).all { it.name.isNotBlank() })
        assertTrue(metadata.listTriggers(session, config.schema).all { it.name.isNotBlank() })
    }

    @Test
    fun `preserves quoted mixed case objects through metadata preview ddl and routine calls`() {
        assumeTrue(config.allowDdl, "Set EASYDB_DM_ALLOW_DDL=true to run destructive lifecycle coverage")

        withSession { session ->
            val token = UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
            val table = "EASYDB_IT_${token}_mixedTable"
            val index = "EASYDB_IT_${token}_mixedIndex"
            val procedure = "EASYDB_IT_${token}_mixedProcedure"
            val function = "EASYDB_IT_${token}_mixedFunction"
            val trigger = "EASYDB_IT_${token}_mixedTrigger"
            val column = "mixedColumn"
            val valueColumn = "valueText"
            val createdObjects = mutableListOf<Pair<String, String>>()
            var primaryFailure: Throwable? = null

            try {
                execute(
                    session,
                    "CREATE TABLE ${qualified(table)} (${quote(column)} INT PRIMARY KEY, ${quote(valueColumn)} VARCHAR2(50))"
                )
                createdObjects += "TABLE" to table

                execute(
                    session,
                    "CREATE INDEX ${qualified(index)} ON ${qualified(table)} (${quote(valueColumn)})"
                )
                createdObjects += "INDEX" to index

                execute(
                    session,
                    "CREATE OR REPLACE PROCEDURE ${qualified(procedure)} AS BEGIN NULL; END;"
                )
                createdObjects += "PROCEDURE" to procedure

                execute(
                    session,
                    "CREATE OR REPLACE FUNCTION ${qualified(function)} RETURN INT AS BEGIN RETURN 7; END;"
                )
                createdObjects += "FUNCTION" to function

                execute(
                    session,
                    "CREATE OR REPLACE TRIGGER ${qualified(trigger)} BEFORE INSERT ON ${qualified(table)} BEGIN NULL; END;"
                )
                createdObjects += "TRIGGER" to trigger

                execute(
                    session,
                    "INSERT INTO ${qualified(table)} (${quote(column)}, ${quote(valueColumn)}) VALUES (1, 'mixed-case-ok')"
                )

                assertTrue(metadata.listTables(session, config.schema).any { it.name == table })
                assertTrue(metadata.getColumns(session, config.schema, table).any { it.name == column })
                assertTrue(metadata.getIndexes(session, config.schema, table).any { it.name == index })
                assertTrue(metadata.getDdl(session, config.schema, table).contains(table))

                val preview = metadata.previewRows(session, config.schema, table, limit = 10)
                assertEquals("1", preview.single()[column])
                assertEquals("mixed-case-ok", preview.single()[valueColumn])

                assertTrue(metadata.listRoutines(session, config.schema).any { it.name == procedure })
                assertTrue(metadata.listRoutines(session, config.schema).any { it.name == function })
                assertTrue(metadata.listTriggers(session, config.schema).any { it.name == trigger })

                session.connection.prepareCall(procedures.buildCallSql(config.schema, procedure, 0)).use { call ->
                    call.execute()
                }
                session.connection.prepareStatement(
                    procedures.buildFunctionCallSql(config.schema, function, 0)
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals(7, result.getInt("result"))
                    }
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                val cleanupFailures = cleanup(session, createdObjects.asReversed())
                if (cleanupFailures.isNotEmpty()) {
                    val cleanupFailure = AssertionError(
                        "Failed to clean ${cleanupFailures.size} EASYDB_IT_ object(s) from the dedicated test schema"
                    )
                    cleanupFailures.forEach(cleanupFailure::addSuppressed)
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure)
                    } else {
                        throw cleanupFailure
                    }
                }
            }
        }
    }

    private fun withSession(block: (DamengDatabaseSession) -> Unit) {
        Class.forName("dm.jdbc.driver.DmDriver")
        val properties = Properties().apply {
            setProperty("user", config.username)
            setProperty("password", config.password)
            setProperty("connectTimeout", "5000")
            setProperty("socketTimeout", "300000")
        }
        DriverManager.getConnection(config.jdbcUrl, properties).use { connection ->
            val session = DamengDatabaseSession(
                connectionId = "dameng-integration-test",
                config = ConnectionConfig(
                    id = "dameng-integration-test",
                    name = "Dameng integration test",
                    dbType = "dameng",
                    username = config.username,
                    password = "",
                    database = config.schema
                ),
                connection = connection
            )
            block(session)
        }
    }

    private fun execute(session: DamengDatabaseSession, sql: String) {
        session.connection.createStatement().use { statement -> statement.execute(sql) }
    }

    private fun cleanup(
        session: DamengDatabaseSession,
        objects: List<Pair<String, String>>
    ): List<SQLException> {
        val failures = mutableListOf<SQLException>()
        objects.forEach { (type, name) ->
            try {
                execute(session, "DROP $type ${qualified(name)}")
            } catch (failure: SQLException) {
                failures += failure
            }
        }
        return failures
    }

    private fun qualified(name: String): String = "${quote(config.schema)}.${quote(name)}"

    private fun quote(name: String): String = dialect.quoteIdentifier(name)
}
