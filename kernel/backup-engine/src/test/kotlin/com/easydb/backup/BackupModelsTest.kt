package com.easydb.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class BackupModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `BackupConfig serialization roundtrip`() {
        val config = BackupConfig(
            connectionId = "conn-001",
            database = "testdb",
            mode = "full",
            tables = listOf("users", "orders"),
            includeRoutines = true,
            includeViews = false,
            includeTriggers = true,
            compression = "gzip"
        )

        val encoded = json.encodeToString(config)
        assertNotNull(encoded)
        assert(encoded.contains("testdb"))

        val decoded = json.decodeFromString<BackupConfig>(encoded)
        assertEquals(config.connectionId, decoded.connectionId)
        assertEquals(config.database, decoded.database)
        assertEquals(config.tables, decoded.tables)
        assertEquals(config.includeViews, decoded.includeViews)
    }

    @Test
    fun `BackupManifest with minimal fields`() {
        val manifest = BackupManifest(
            formatVersion = 1,
            appVersion = "1.3.1",
            dbType = "mysql",
            serverVersion = "8.0.32",
            database = "mydb",
            mode = "full",
            startedAt = "2026-04-18T15:00:00",
            consistency = "snapshot",
            tables = emptyList(),
            objects = emptyList()
        )

        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<BackupManifest>(encoded)

        assertEquals(1, decoded.formatVersion)
        assertEquals("snapshot", decoded.consistency)
        assertEquals(null, decoded.binlogFile)
    }

    @Test
    fun `BackupManifest with tables and binlog info`() {
        val manifest = BackupManifest(
            formatVersion = 1,
            appVersion = "1.3.1",
            dbType = "mysql",
            serverVersion = "8.0.32",
            database = "mydb",
            mode = "full",
            charset = "utf8mb4",
            collation = "utf8mb4_general_ci",
            startedAt = "2026-04-18T15:00:00",
            completedAt = "2026-04-18T15:05:23",
            consistency = "snapshot",
            binlogFile = "binlog.000123",
            binlogPosition = 45678L,
            tables = listOf(
                BackupTableEntry(
                    tableName = "users",
                    ddlFile = "schema/tables/users.sql",
                    rowEstimate = 50000L,
                    dataFiles = listOf("data/users.part001.sql.gz")
                )
            ),
            objects = listOf(
                BackupObjectEntry(
                    name = "v_summary",
                    type = "view",
                    ddlFile = "schema/views/v_summary.sql"
                )
            )
        )

        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<BackupManifest>(encoded)

        assertEquals(1, decoded.tables.size)
        assertEquals("users", decoded.tables[0].tableName)
        assertEquals("binlog.000123", decoded.binlogFile)
        assertEquals(45678L, decoded.binlogPosition)
        assertEquals(1, decoded.objects.size)
        assertEquals("view", decoded.objects[0].type)
    }

    @Test
    fun `RestoreConfig with default values`() {
        val config = RestoreConfig(
            targetConnectionId = "conn-002",
            backupFilePath = "/path/to/backup.edbkp",
            targetDatabase = "targetdb"
        )

        assertEquals("restore_all", config.mode)
        assertEquals("restore_to_new", config.strategy)
        assertEquals(emptyList<String>(), config.selectedTables)
    }

    @Test
    fun `BackupEstimateResult serialization`() {
        val estimate = BackupEstimateResult(
            database = "testdb",
            selectedTables = 10,
            estimatedRows = 1_000_000L,
            estimatedBytes = 512_000_000L,
            largeTableCount = 2,
            warnings = listOf("Table 'logs' exceeds 1M rows")
        )

        val encoded = json.encodeToString(estimate)
        val decoded = json.decodeFromString<BackupEstimateResult>(encoded)

        assertEquals(10, decoded.selectedTables)
        assertEquals(2, decoded.largeTableCount)
        assertEquals(1, decoded.warnings.size)
    }
}