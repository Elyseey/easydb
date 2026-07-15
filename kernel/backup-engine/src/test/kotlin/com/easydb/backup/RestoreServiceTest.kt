package com.easydb.backup

import com.easydb.common.ConnectionAdapter
import com.easydb.common.ConnectionConfig
import com.easydb.common.DatabaseAdapter
import com.easydb.common.DatabaseCapabilities
import com.easydb.common.DatabaseSession
import com.easydb.common.DbType
import com.easydb.common.DialectAdapter
import com.easydb.common.MetadataAdapter
import com.easydb.common.TaskReporter
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RestoreServiceTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `failure cleans only normalized namespace created by current task`() {
        val packageFile = createPackageWithBrokenTableDdl()
        val adapter = mockk<DatabaseAdapter>()
        val connectionAdapter = mockk<ConnectionAdapter>()
        val metadata = mockk<MetadataAdapter>()
        val dialect = mockk<DialectAdapter>()
        val restoreSession = mockk<DatabaseSession>()
        val cleanupSession = mockk<DatabaseSession>()
        val connection = mockk<Connection>()
        val reporter = mockk<TaskReporter>(relaxed = true)

        every { adapter.dbType() } returns DbType.DAMENG
        every { adapter.capabilities() } returns DatabaseCapabilities(
            supportsLogicalRestore = true,
            supportsOverwriteRestore = false
        )
        every { adapter.connectionAdapter() } returns connectionAdapter
        every { adapter.metadataAdapter() } returns metadata
        every { adapter.dialectAdapter() } returns dialect
        every { connectionAdapter.open(any()) } returnsMany listOf(restoreSession, cleanupSession)
        every { restoreSession.getJdbcConnection() } returns connection
        every { connection.autoCommit = true } just runs
        every { restoreSession.close() } just runs
        every { cleanupSession.close() } just runs
        every { metadata.listDatabases(restoreSession) } returns emptyList()
        every { metadata.createDatabase(restoreSession, "RESTORE_ME", any(), any()) } just runs
        every { metadata.dropDatabase(cleanupSession, "RESTORE_ME") } just runs
        every { dialect.normalizeNewNamespaceName(" restore_me ") } returns "RESTORE_ME"
        every { dialect.buildSwitchDatabaseSql("RESTORE_ME") } returns null
        every { dialect.beforeLogicalRestore(connection) } just runs
        every { dialect.afterLogicalRestore(connection) } just runs
        every { dialect.remapNamespaceInDdl(any(), "SOURCE", "RESTORE_ME") } returns "BROKEN DDL"
        every { dialect.executeLogicalRestoreDdl(connection, "BROKEN DDL", "table") } throws SQLException("invalid ddl")

        assertThrows(SQLException::class.java) {
            RestoreService().execute(
                RestoreConfig(
                    targetConnectionId = "dm",
                    backupFilePath = packageFile.absolutePath,
                    targetDatabase = " restore_me "
                ),
                ConnectionConfig(id = "dm", name = "DM", dbType = "dameng"),
                reporter,
                adapter
            )
        }

        verify(exactly = 1) { metadata.createDatabase(restoreSession, "RESTORE_ME", any(), any()) }
        verify(exactly = 1) { metadata.dropDatabase(cleanupSession, "RESTORE_ME") }
        verify(exactly = 1) { dialect.afterLogicalRestore(connection) }
    }

    private fun createPackageWithBrokenTableDdl(): File {
        val json = Json { encodeDefaults = true }
        val manifest = BackupManifest(
            formatVersion = 1,
            appVersion = "1.3.2",
            dbType = "dameng",
            serverVersion = "8",
            database = "SOURCE",
            mode = "full",
            startedAt = "2026-07-14T10:00:00",
            consistency = "snapshot",
            tables = listOf(
                BackupTableEntry("T", "schema/010_tables/table_00000.sql", 0, emptyList())
            ),
            objects = emptyList()
        )
        val payload = linkedMapOf(
            "manifest.json" to json.encodeToString(manifest),
            "schema/000_database.sql" to "CREATE SCHEMA \"SOURCE\";",
            "schema/010_tables/table_00000.sql" to "CREATE TABLE T(ID INT)"
        )
        val checksums = payload.mapValues { (_, value) -> sha256(value.toByteArray()) }
        payload["checksums.json"] = json.encodeToString(checksums)

        val file = File(tempDir, "broken.edbkp")
        ZipOutputStream(file.outputStream()).use { zip ->
            payload.forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
