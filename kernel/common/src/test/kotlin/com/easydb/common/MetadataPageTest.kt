package com.easydb.common

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetadataPageTest {
    private val session = object : DatabaseSession {
        override val connectionId = "test"
        override val config = ConnectionConfig(id = connectionId, name = connectionId)
        override fun isValid() = true
        override fun close() = Unit
        override fun getJdbcConnection(): java.sql.Connection = error("not used")
    }

    @Test
    fun `default adapter page filters sorts and reports total`() {
        val adapter = StaticMetadataAdapter(
            listOf(
                TableInfo(name = "Meter10", type = "table"),
                TableInfo(name = "meter02", type = "table"),
                TableInfo(name = "other", type = "stable"),
                TableInfo(name = "meter01", type = "table")
            )
        )

        val page = adapter.listTablesPage(
            session,
            "power",
            MetadataPageRequest(search = " METER ", type = "TABLE", offset = 1, limit = 1)
        )

        assertEquals(listOf("meter02"), page.items.map { it.name })
        assertEquals(3, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `page request rejects unsafe bounds and page is serializable`() {
        assertFailsWith<IllegalArgumentException> { MetadataPageRequest(offset = -1) }
        assertFailsWith<IllegalArgumentException> { MetadataPageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> {
            MetadataPageRequest(limit = MetadataPageLimits.MAX_PAGE_SIZE + 1)
        }

        val page = MetadataPage(
            items = listOf(TableInfo(name = "meters", type = "stable")),
            total = 1,
            offset = 0,
            limit = 100,
            hasMore = false
        )
        val decoded = Json.decodeFromString<MetadataPage<TableInfo>>(Json.encodeToString(page))
        assertEquals(page, decoded)
        assertFalse(decoded.hasMore)
    }

    private class StaticMetadataAdapter(private val tables: List<TableInfo>) : MetadataAdapter {
        override fun listDatabases(session: DatabaseSession) = emptyList<DatabaseInfo>()
        override fun listTables(session: DatabaseSession, database: String) = tables
        override fun getTableDefinition(session: DatabaseSession, database: String, table: String) =
            TableDefinition(
                table = tables.first { it.name == table },
                columns = emptyList(),
                indexes = emptyList()
            )
        override fun getTableDesign(session: DatabaseSession, database: String, table: String) =
            getTableDefinition(session, database, table)
        override fun getIndexes(session: DatabaseSession, database: String, table: String) = emptyList<IndexInfo>()
        override fun previewRows(
            session: DatabaseSession,
            database: String,
            table: String,
            limit: Int,
            where: String?,
            orderBy: String?,
            offset: Int
        ) = emptyList<Map<String, String?>>()
        override fun getDdl(session: DatabaseSession, database: String, table: String) = ""
        override fun createDatabase(
            session: DatabaseSession,
            name: String,
            charset: String,
            collation: String
        ) = Unit
        override fun dropDatabase(session: DatabaseSession, name: String) = Unit
        override fun renameTable(
            session: DatabaseSession,
            database: String,
            oldName: String,
            newName: String
        ) = Unit
    }
}
