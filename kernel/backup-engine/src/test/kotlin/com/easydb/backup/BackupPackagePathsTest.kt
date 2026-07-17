package com.easydb.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BackupPackagePathsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `paths never contain database object names`() {
        val dangerousName = "../../schema/table\\name"
        val paths = listOf(
            BackupPackagePaths.tableDdl(7),
            BackupPackagePaths.tableData(7, 2),
            BackupPackagePaths.routineDdl(7),
            BackupPackagePaths.viewDdl(7),
            BackupPackagePaths.triggerDdl(7)
        )

        paths.forEach { path ->
            assertFalse(path.contains(dangerousName))
            assertFalse(path.contains(".."))
            assertFalse(path.contains('\\'))
        }
        assertEquals("data/table_00007.part002.sql.gz", BackupPackagePaths.tableData(7, 2))
        assertEquals("ENERGY_20260714_1200.edbkp", BackupPackagePaths.outputFileName("ENERGY", "20260714_1200"))
        val dangerousOutput = BackupPackagePaths.outputFileName("../../schema/name", "20260714_1200")
        assertFalse(dangerousOutput.contains("/"))
        assertFalse(dangerousOutput.contains(".."))
    }

    @Test
    fun `package writer rejects path traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupPackageWriter(tempDir).writeString("../escape.sql", "SELECT 1")
        }
        assertFalse(File(tempDir.parentFile, "escape.sql").exists())
    }
}
