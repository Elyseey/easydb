package com.easydb.drivers.dameng

import com.easydb.common.ColumnInfo
import com.easydb.common.TableDefinition
import com.easydb.common.TableInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class DamengDatabaseAdapterTest {

    @Test
    fun `does not advertise runtime database charset alteration`() {
        val capabilities = DamengDatabaseAdapter().capabilities()

        assertFalse(capabilities.supportsAlterDatabaseCharset)
        assertTrue(capabilities.supportsTransactions)
        assertTrue(capabilities.supportsLogicalExport)
        assertTrue(capabilities.supportsSqlFileImport)
        assertTrue(capabilities.supportsLogicalBackup)
        assertTrue(capabilities.supportsLogicalRestore)
        assertFalse(capabilities.supportsOverwriteRestore)
    }

    @Test
    fun `quotes identifiers and switches the current schema with dameng syntax`() {
        val dialect = DamengDialectAdapter()

        assertEquals("\"order\"\"detail\"", dialect.quoteIdentifier("order\"detail"))
        assertEquals(
            "ALTER SESSION SET CURRENT_SCHEMA = \"BXJC-MES\"",
            dialect.buildSwitchDatabaseSql("BXJC-MES")
        )
        assertEquals("'O''Reilly\\path'", dialect.formatExportStringLiteral("O'Reilly\\path"))
        assertEquals("CREATE SCHEMA \"mixedCase\"", dialect.buildCreateNamespaceSql("mixedCase"))
    }

    @Test
    fun `preserves catalog identifier case and normalizes only newly entered unquoted names`() {
        assertEquals(" mixedCase ", DamengIdentifierPolicy.catalogName(" mixedCase "))
        assertEquals("MIXEDCASE", DamengIdentifierPolicy.newUnquotedName(" mixedCase "))
    }

    @Test
    fun `deduplicates native ddl object type attempts while preserving fallback order`() {
        assertEquals(
            listOf("TABLE", "VIEW", "PROCEDURE", "FUNCTION"),
            DamengDdlPolicy.candidateTypes("TABLE")
        )
        assertEquals(
            listOf("TRIGGER", "TABLE", "VIEW", "PROCEDURE", "FUNCTION"),
            DamengDdlPolicy.candidateTypes("TRIGGER")
        )
    }

    @Test
    fun `reuses fallback columns and loads native ddl columns once`() {
        val cachedColumns = listOf(ColumnInfo(name = "ID", type = "BIGINT"))
        var fallbackLoads = 0
        val fallbackResolution = DamengDdlResolution(
            ddl = "CREATE TABLE \"T\" (\"ID\" BIGINT);",
            source = "synthesized",
            columns = cachedColumns
        )

        assertEquals(cachedColumns, fallbackResolution.columnsOrLoad { fallbackLoads += 1; emptyList() })
        assertEquals(0, fallbackLoads)

        var nativeLoads = 0
        val nativeResolution = DamengDdlResolution(ddl = "CREATE TABLE \"T\";", source = "native")
        assertEquals(
            cachedColumns,
            nativeResolution.columnsOrLoad { nativeLoads += 1; cachedColumns }
        )
        assertEquals(1, nativeLoads)
    }

    @Test
    fun `preserves routine case in generated call SQL`() {
        val adapter = DamengProcedureAdapter()

        assertEquals(
            "CALL \"appSchema\".\"refreshCache\"(?)",
            adapter.buildCallSql("appSchema", "refreshCache", 1)
        )
        assertEquals(
            "SELECT \"appSchema\".\"findUser\"(?) AS result FROM dual",
            adapter.buildFunctionCallSql("appSchema", "findUser", 1)
        )
    }

    @Test
    fun `keeps successful schema sources when another catalog source fails`() {
        val names = linkedSetOf("JDBC_SCHEMA")

        DamengCatalogPolicy.mergeNames(names) { throw IllegalStateException("no permission") }
        DamengCatalogPolicy.mergeNames(names) { listOf("visibleSchema", "", null) }

        assertEquals(linkedSetOf("JDBC_SCHEMA", "visibleSchema"), names)
    }

    @Test
    fun `uses system catalog only when visible object catalog is unavailable or empty`() {
        assertEquals(
            listOf("PUBLIC_OBJECT"),
            DamengCatalogPolicy.visibleFirst(
                visibleLoader = { listOf("PUBLIC_OBJECT") },
                fallbackLoader = { fail("fallback should not be called") }
            )
        )
        assertEquals(
            listOf("SYSTEM_OBJECT"),
            DamengCatalogPolicy.visibleFirst(
                visibleLoader = { throw IllegalStateException("no permission") },
                fallbackLoader = { listOf("SYSTEM_OBJECT") }
            )
        )
        assertEquals(
            listOf("SYSTEM_OBJECT"),
            DamengCatalogPolicy.visibleFirst(
                visibleLoader = { emptyList() },
                fallbackLoader = { listOf("SYSTEM_OBJECT") }
            )
        )
    }

    @Test
    fun `creates comments as separate dameng statements`() {
        val dialect = DamengDialectAdapter()
        val definition = TableDefinition(
            table = TableInfo(name = "USERS", comment = "用户表"),
            columns = listOf(
                ColumnInfo(
                    name = "ID",
                    type = "BIGINT",
                    nullable = false,
                    isPrimaryKey = true,
                    comment = "主键"
                )
            ),
            indexes = emptyList()
        )

        val statements = dialect.buildCreateTableStatements(definition)

        assertEquals(3, statements.size)
        assertTrue(statements[0].startsWith("CREATE TABLE \"USERS\""))
        assertEquals("COMMENT ON TABLE \"USERS\" IS '用户表'", statements[1])
        assertEquals("COMMENT ON COLUMN \"USERS\".\"ID\" IS '主键'", statements[2])
    }

    @Test
    fun `splits table comments without splitting semicolons inside literals`() {
        val statements = DamengRestoreDdl.statements(
            """
            CREATE TABLE "T" ("TEXT" VARCHAR2(40) DEFAULT 'a;b', "Q" VARCHAR2(40) DEFAULT q'[c;d]');
            COMMENT ON TABLE "T" IS 'table;comment';
            COMMENT ON COLUMN "T"."TEXT" IS 'column;comment';
            """.trimIndent(),
            "table"
        )

        assertEquals(3, statements.size)
        assertTrue(statements[0].contains("'a;b'"))
        assertTrue(statements[0].contains("q'[c;d]'"))
        assertTrue(statements[1].contains("'table;comment'"))
    }

    @Test
    fun `keeps routine blocks as one restore statement`() {
        val ddl = "CREATE PROCEDURE \"P\" AS BEGIN INSERT INTO \"T\" VALUES (1); COMMIT; END;"

        assertEquals(listOf(ddl), DamengRestoreDdl.statements(ddl, "procedure"))
    }
}
