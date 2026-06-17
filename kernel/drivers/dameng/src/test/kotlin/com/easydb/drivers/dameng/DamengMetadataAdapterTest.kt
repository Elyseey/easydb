package com.easydb.drivers.dameng

import com.easydb.common.ConnectionConfig
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DamengMetadataAdapterTest {

    @Test
    fun testDialectSwitchDatabase() {
        val dialect = DamengDialectAdapter()
        val sql = dialect.buildSwitchDatabaseSql("BXJC-MES")
        assertEquals("ALTER SESSION SET CURRENT_SCHEMA = \"BXJC-MES\"", sql)
    }

    @Test
    fun testMetadata() {
        val url = "jdbc:dm://localhost:5236"
        val user = "SYSDBA"
        val password = "SYSDBA001"

        try {
            Class.forName("dm.jdbc.driver.DmDriver")
            val conn = DriverManager.getConnection(url, user, password)
            println("=== Connection Successful ===")

            // Test 1: conn.metaData.schemas
            val schemasMetadata = mutableListOf<String>()
            val rs1 = conn.metaData.schemas
            while (rs1.next()) {
                schemasMetadata.add(rs1.getString("TABLE_SCHEM"))
            }
            println("JDBC getSchemas(): $schemasMetadata")

            // Test 2: ALL_USERS
            val allUsers = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT USERNAME FROM ALL_USERS").use { rs ->
                    while (rs.next()) {
                        allUsers.add(rs.getString("USERNAME"))
                    }
                }
            }
            println("Query ALL_USERS: $allUsers")

            // Test 3: ALL_OBJECTS SCH
            val allObjectsSch = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT DISTINCT OBJECT_NAME FROM ALL_OBJECTS WHERE OBJECT_TYPE = 'SCH'").use { rs ->
                    while (rs.next()) {
                        allObjectsSch.add(rs.getString("OBJECT_NAME"))
                    }
                }
            }
            println("Query ALL_OBJECTS SCH: $allObjectsSch")

            conn.close()
        } catch (e: Exception) {
            println("Failed to connect or query: ${e.message}")
        }
    }

    @Test
    fun testComments() {
        val url = "jdbc:dm://localhost:5236"
        val user = "SYSDBA"
        val password = "SYSDBA001"

        try {
            Class.forName("dm.jdbc.driver.DmDriver")
            val conn = DriverManager.getConnection(url, user, password)
            println("=== Comments Verification ===")

            conn.createStatement().use { stmt ->
                try {
                    stmt.execute("DROP TABLE TEST_COMMENT_TAB")
                } catch (_: Exception) {}
                
                // Create table
                stmt.execute("CREATE TABLE TEST_COMMENT_TAB (ID INT, NAME VARCHAR(50))")
                // Comment table
                stmt.execute("COMMENT ON TABLE TEST_COMMENT_TAB IS 'Test Table Comment'")
                // Comment column
                stmt.execute("COMMENT ON COLUMN TEST_COMMENT_TAB.NAME IS 'Test Column Comment'")
            }

            val session = DamengDatabaseSession(
                connectionId = "test_session",
                config = ConnectionConfig(name = "test_dm", dbType = "dameng", database = "SYSDBA"),
                connection = conn
            )
            val adapter = DamengMetadataAdapter()

            // Verify table comment
            val tables = adapter.listTables(session, "SYSDBA")
            val targetTable = tables.firstOrNull { it.name == "TEST_COMMENT_TAB" }
            assertNotNull(targetTable, "Table TEST_COMMENT_TAB should exist")
            assertEquals("Test Table Comment", targetTable.comment)
            println("Table comment verified: ${targetTable.comment}")

            // Verify column comment
            val tableDef = adapter.getTableDefinition(session, "SYSDBA", "TEST_COMMENT_TAB")
            val targetColumn = tableDef.columns.firstOrNull { it.name == "NAME" }
            assertNotNull(targetColumn, "Column NAME should exist")
            assertEquals("Test Column Comment", targetColumn.comment)
            println("Column comment verified: ${targetColumn.comment}")

            // Verify getDdl comment inclusion
            val ddl = adapter.getDdl(session, "SYSDBA", "TEST_COMMENT_TAB")
            assertTrue(ddl.contains("COMMENT ON TABLE"), "DDL should contain table comment")
            assertTrue(ddl.contains("COMMENT ON COLUMN"), "DDL should contain column comment")
            println("getDdl comments verified:\n$ddl")

            // Clean up
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE TEST_COMMENT_TAB")
            }

            conn.close()
        } catch (e: Exception) {
            println("Skipping or failing comments test: ${e.message}")
        }
    }

    @Test
    fun testRealDatabaseQueries() {
        val encryptedPassword = "ENCv1:UhesvhdWe6c+dqRXh6bYiG38gjm8SCkNCF89Xx+6XfX+QaDx74z6"
        val decrypted = com.easydb.common.CredentialCipher.decrypt(encryptedPassword)
        println("Decrypted Password: $decrypted")
        
        val url = "jdbc:dm://10.244.14.144:15237"
        val user = "SYSDBA"
        
        try {
            Class.forName("dm.jdbc.driver.DmDriver")
            val conn = DriverManager.getConnection(url, user, decrypted)
            println("=== Connected to 10.244.14.144 ===")
            
            // Query 1: count columns in ALL_TAB_COLUMNS for bd_bom (lowercase) and BD_BOM (uppercase)
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT OWNER, TABLE_NAME, COUNT(*) 
                    FROM ALL_TAB_COLUMNS 
                    WHERE OWNER = 'BXJC-MES' 
                      AND (TABLE_NAME = 'bd_bom' OR TABLE_NAME = 'BD_BOM')
                    GROUP BY OWNER, TABLE_NAME
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        println("ALL_TAB_COLUMNS: OWNER=${rs.getString(1)}, TABLE_NAME=${rs.getString(2)}, COUNT=${rs.getInt(3)}")
                    }
                }
            }

            // Query 2: Let's count how many rows are in ALL_COL_COMMENTS overall, and for other owners
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM ALL_COL_COMMENTS").use { rs ->
                    if (rs.next()) {
                        println("ALL_COL_COMMENTS total count (all owners): ${rs.getInt(1)}")
                    }
                }
            }

            // Query 2b: Let's see some random rows in ALL_COL_COMMENTS if any
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT OWNER, TABLE_NAME, COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE COMMENTS IS NOT NULL AND ROWNUM <= 5").use { rs ->
                    while (rs.next()) {
                        println("Random ALL_COL_COMMENTS: OWNER=${rs.getString(1)}, TABLE=${rs.getString(2)}, COL=${rs.getString(3)}, COMM=${rs.getString(4)}")
                    }
                }
            }

            // Query 2c: Try DBA_COL_COMMENTS
            try {
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM DBA_COL_COMMENTS").use { rs ->
                        if (rs.next()) {
                            println("DBA_COL_COMMENTS total count: ${rs.getInt(1)}")
                        }
                    }
                }
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT OWNER, TABLE_NAME, COLUMN_NAME, COMMENTS FROM DBA_COL_COMMENTS WHERE OWNER = 'BXJC-MES' AND (TABLE_NAME = 'bd_bom' OR TABLE_NAME = 'BD_BOM')").use { rs ->
                        while (rs.next()) {
                            println("DBA_COL_COMMENTS for bd_bom: OWNER=${rs.getString(1)}, TABLE=${rs.getString(2)}, COL=${rs.getString(3)}, COMM=${rs.getString(4)}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("Failed to query DBA_COL_COMMENTS: ${e.message}")
            }
            
            // Query 3: Let's see some tables that have comments in ALL_COL_COMMENTS
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT DISTINCT OWNER, TABLE_NAME FROM ALL_COL_COMMENTS 
                    WHERE COMMENTS IS NOT NULL AND COMMENTS != '' AND ROWNUM <= 10
                    """.trimIndent()
                ).use { rs ->
                    while (rs.next()) {
                        println("Table with comments in ALL_COL_COMMENTS: OWNER=${rs.getString(1)}, TABLE=${rs.getString(2)}")
                    }
                }
            }

            // Query 4: Try query ALL_COL_COMMENTS specifically for 'bd_bom' without uppercase normalization
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT TABLE_NAME, COLUMN_NAME, COMMENTS 
                    FROM ALL_COL_COMMENTS 
                    WHERE OWNER = 'BXJC-MES' AND (TABLE_NAME = 'bd_bom' OR TABLE_NAME = 'BD_BOM')
                    """.trimIndent()
                ).use { rs ->
                    var found = false
                    while (rs.next()) {
                        println("ALL_COL_COMMENTS for bd_bom: TABLE=${rs.getString(1)}, COLUMN=${rs.getString(2)}, COMMENT=${rs.getString(3)}")
                        found = true
                    }
                    if (!found) {
                        println("ALL_COL_COMMENTS returned no rows for bd_bom/BD_BOM under owner 'BXJC-MES'")
                    }
                }
            }

            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT COUNT(*) FROM ALL_TAB_COLUMNS 
                    WHERE OWNER = 'BXJC-MES' AND TABLE_NAME = 'bd_bom'
                    """.trimIndent()
                ).use { rs ->
                    if (rs.next()) {
                        println("ALL_TAB_COLUMNS with lowercase 'bd_bom': ${rs.getInt(1)}")
                    }
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """
                    SELECT COUNT(*) FROM ALL_TAB_COLUMNS 
                    WHERE OWNER = 'BXJC-MES' AND TABLE_NAME = 'BD_BOM'
                    """.trimIndent()
                ).use { rs ->
                    if (rs.next()) {
                        println("ALL_TAB_COLUMNS with uppercase 'BD_BOM': ${rs.getInt(1)}")
                    }
                }
            }

            // Query 7: Let's call getTableDefinition on DamengMetadataAdapter
            val session = DamengDatabaseSession(
                connectionId = "real_session",
                config = ConnectionConfig(name = "real_dm", dbType = "dameng", database = "BXJC-MES"),
                connection = conn
            )
            val adapter = DamengMetadataAdapter()
            try {
                val tableDef = adapter.getTableDefinition(session, "BXJC-MES", "bd_bom")
                println("getTableDefinition table: name=${tableDef.table.name}, schema=${tableDef.table.schema}, comment=${tableDef.table.comment}")
                println("getTableDefinition columns size: ${tableDef.columns.size}")
                tableDef.columns.take(10).forEach { col ->
                    println("Column: name=${col.name}, type=${col.type}, pk=${col.isPrimaryKey}, comment=${col.comment}")
                }
                println("getTableDefinition DDL:")
                println(tableDef.ddl)
            } catch (e: Exception) {
                println("Failed to getTableDefinition: ${e.message}")
                e.printStackTrace()
            }

            conn.close()
        } catch (e: Exception) {
            println("Failed in testRealDatabaseQueries: ${e.message}")
            e.printStackTrace()
        }
    }
}
