package com.easydb.backup

import com.easydb.common.DatabaseAdapter
import com.easydb.common.DatabaseCapabilities
import com.easydb.common.DbType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RestorePolicyTest {
    private val damengAdapter = mockk<DatabaseAdapter>().also { adapter ->
        every { adapter.dbType() } returns DbType.DAMENG
        every { adapter.capabilities() } returns DatabaseCapabilities(
            supportsLogicalBackup = true,
            supportsLogicalRestore = true,
            supportsOverwriteRestore = false
        )
    }

    @Test
    fun `dameng rejects overwrite strategy`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorePolicy.validateOrThrow(
                config(strategy = "overwrite_existing"),
                manifest(),
                damengAdapter,
                targetExists = true
            )
        }
    }

    @Test
    fun `dameng rejects data only restore into new schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorePolicy.validateOrThrow(
                config(mode = "data_only"),
                manifest(),
                damengAdapter,
                targetExists = false
            )
        }
    }

    @Test
    fun `dameng rejects data only backup package`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorePolicy.validateOrThrow(
                config(),
                manifest(mode = "data_only"),
                damengAdapter,
                targetExists = false
            )
        }
    }

    @Test
    fun `restore to new rejects existing schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorePolicy.validateOrThrow(config(), manifest(), damengAdapter, targetExists = true)
        }
    }

    @Test
    fun `database type must match target adapter`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorePolicy.validateOrThrow(config(), manifest(dbType = "mysql"), damengAdapter, targetExists = false)
        }
    }

    @Test
    fun `selected table must exist in manifest`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorePolicy.validateOrThrow(
                config().copy(selectedTables = listOf("missing")),
                manifest(),
                damengAdapter,
                targetExists = false
            )
        }
    }

    @Test
    fun `mysql overwrite remains supported`() {
        val mysqlAdapter = mockk<DatabaseAdapter>().also { adapter ->
            every { adapter.dbType() } returns DbType.MYSQL
            every { adapter.capabilities() } returns DatabaseCapabilities(
                supportsLogicalBackup = true,
                supportsLogicalRestore = true,
                supportsOverwriteRestore = true
            )
        }

        assertDoesNotThrow {
            RestorePolicy.validateOrThrow(
                config(strategy = "overwrite_existing"),
                manifest(dbType = "mysql"),
                mysqlAdapter,
                targetExists = true
            )
        }
    }

    private fun config(mode: String = "restore_all", strategy: String = "restore_to_new") = RestoreConfig(
        targetConnectionId = "dm",
        backupFilePath = "/tmp/backup.edbkp",
        targetDatabase = "RESTORED_SCHEMA",
        mode = mode,
        strategy = strategy
    )

    private fun manifest(dbType: String = "dameng", mode: String = "full") = BackupManifest(
        formatVersion = 1,
        appVersion = "1.3.2",
        dbType = dbType,
        serverVersion = "8",
        database = "SOURCE_SCHEMA",
        mode = mode,
        startedAt = "2026-07-14T10:00:00",
        consistency = "snapshot",
        tables = emptyList(),
        objects = emptyList()
    )
}
