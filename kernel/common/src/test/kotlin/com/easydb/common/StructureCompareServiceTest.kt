package com.easydb.common

import kotlin.test.Test
import kotlin.test.assertEquals

class StructureCompareServiceTest {
    @Test
    fun `treats database-generated primary index names as the same identity`() {
        val columns = listOf(ColumnInfo("ID", "BIGINT", nullable = false, isPrimaryKey = true))
        val source = definition(columns, IndexInfo("SYS_PK_1001", listOf("ID"), isUnique = true, isPrimary = true))
        val target = definition(columns, IndexInfo("SYS_PK_9009", listOf("ID"), isUnique = true, isPrimary = true))
        val service = StructureCompareService(
            StaticMetadataAdapter(source),
            StaticMetadataAdapter(target),
            NoopSqlGenerator
        )

        val result = service.compare(
            CompareConfig("source", "target", "SOURCE", "TARGET"),
            SessionPair(FakeSession("source"), FakeSession("target"))
        )

        assertEquals("identical", result.tables.single().status)
        assertEquals("PRIMARY", result.tables.single().indexDiffs.single().indexName)
        assertEquals("identical", result.tables.single().indexDiffs.single().status)
    }

    private fun definition(columns: List<ColumnInfo>, index: IndexInfo) = TableDefinition(
        table = TableInfo("USERS", type = "table"),
        columns = columns,
        indexes = listOf(index)
    )

    private class StaticMetadataAdapter(private val definition: TableDefinition) : MetadataAdapter {
        override fun listDatabases(session: DatabaseSession): List<DatabaseInfo> = emptyList()
        override fun listTables(session: DatabaseSession, database: String): List<TableInfo> = listOf(definition.table)
        override fun getTableDefinition(session: DatabaseSession, database: String, table: String): TableDefinition = definition
        override fun getTableDesign(session: DatabaseSession, database: String, table: String): TableDefinition = definition
        override fun getIndexes(session: DatabaseSession, database: String, table: String): List<IndexInfo> = definition.indexes
        override fun previewRows(session: DatabaseSession, database: String, table: String, limit: Int, where: String?, orderBy: String?, offset: Int): List<Map<String, String?>> = emptyList()
        override fun getDdl(session: DatabaseSession, database: String, table: String): String = "CREATE TABLE USERS (ID BIGINT)"
        override fun createDatabase(session: DatabaseSession, name: String, charset: String, collation: String) = Unit
        override fun dropDatabase(session: DatabaseSession, name: String) = Unit
        override fun renameTable(session: DatabaseSession, database: String, oldName: String, newName: String) = Unit
    }

    private class FakeSession(override val connectionId: String) : DatabaseSession {
        override val config = ConnectionConfig(id = connectionId, name = connectionId)
        override fun isValid(): Boolean = true
        override fun close() = Unit
        override fun getJdbcConnection(): java.sql.Connection = error("not used")
    }

    private object NoopSqlGenerator : StructureCompareSqlGenerator {
        override fun typesEquivalent(sourceType: String, targetType: String): Boolean = sourceType == targetType
        override fun createTableSql(sourceDdl: String, sourceDatabase: String, targetDatabase: String, tableName: String): String = sourceDdl
        override fun dropTableSql(targetDatabase: String, tableName: String): String = ""
        override fun alterTableSql(targetDatabase: String, tableName: String, sourceColumns: List<ColumnInfo>, columnDiffs: List<ColumnDiff>, indexDiffs: List<IndexDiff>, options: CompareOptions): String = ""
    }
}
