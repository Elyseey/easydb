package com.easydb.drivers.mysql

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MysqlDatabaseAdapterTest {

    @Test
    fun `advertises logical export and sql file import`() {
        val capabilities = MysqlDatabaseAdapter().capabilities()

        assertTrue(capabilities.supportsLogicalExport)
        assertTrue(capabilities.supportsSqlFileImport)
        assertTrue(capabilities.supportsLogicalBackup)
        assertTrue(capabilities.supportsLogicalRestore)
        assertTrue(capabilities.supportsOverwriteRestore)
    }

    @Test
    fun `preserves mysql escape rules for exported string literals`() {
        val dialect = MysqlDialectAdapter()

        assertEquals("'O\\'Reilly\\\\path\\nnext'", dialect.formatExportStringLiteral("O'Reilly\\path\nnext"))
        assertEquals(
            "CREATE DATABASE `restore_db` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci",
            dialect.buildCreateNamespaceSql("restore_db")
        )
    }
}
