package com.easydb.drivers.mysql

import com.easydb.common.ColumnDiff
import com.easydb.common.ColumnInfo
import com.easydb.common.CompareOptions
import com.easydb.common.IndexDiff
import com.easydb.common.StructureCompareSqlGenerator

class MysqlStructureCompareSqlGenerator : StructureCompareSqlGenerator {
    private val dialect = MysqlDialectAdapter()

    override fun typesEquivalent(sourceType: String, targetType: String): Boolean =
        normalizeType(sourceType) == normalizeType(targetType)

    override fun createTableSql(
        sourceDdl: String,
        sourceDatabase: String,
        targetDatabase: String,
        tableName: String
    ): String = dialect.remapNamespaceInDdl(sourceDdl, sourceDatabase, targetDatabase)

    override fun dropTableSql(targetDatabase: String, tableName: String): String =
        "DROP TABLE ${qualified(targetDatabase, tableName)};"

    override fun alterTableSql(
        targetDatabase: String,
        tableName: String,
        sourceColumns: List<ColumnInfo>,
        columnDiffs: List<ColumnDiff>,
        indexDiffs: List<IndexDiff>,
        options: CompareOptions
    ): String {
        val table = qualified(targetDatabase, tableName)
        val statements = mutableListOf<String>()
        columnDiffs.forEach { diff ->
            val column = dialect.quoteIdentifier(diff.columnName)
            when (diff.status) {
                "added" -> {
                    val definition = columnDefinition(diff)
                    val previous = previousColumn(sourceColumns, diff.columnName)
                    val position = previous?.let { " AFTER ${dialect.quoteIdentifier(it)}" } ?: " FIRST"
                    statements += "ALTER TABLE $table ADD COLUMN $definition$position;"
                }
                "removed" -> if (options.includeDropStatements) {
                    statements += "ALTER TABLE $table DROP COLUMN $column;"
                }
                "modified" -> statements += "ALTER TABLE $table MODIFY COLUMN ${columnDefinition(diff)};"
            }
        }
        indexDiffs.forEach { diff ->
            when (diff.status) {
                "added" -> statements += addIndexSql(table, diff)
                "removed" -> if (options.includeDropStatements) statements += dropIndexSql(table, diff)
                "modified" -> {
                    statements += dropIndexSql(table, diff)
                    statements += addIndexSql(table, diff)
                }
            }
        }
        return statements.joinToString("\n")
    }

    private fun columnDefinition(diff: ColumnDiff): String = buildString {
        append("${dialect.quoteIdentifier(diff.columnName)} ${requireNotNull(diff.sourceType)}")
        if (diff.sourceNullable == false) append(" NOT NULL")
        diff.sourceDefault?.let { append(" DEFAULT $it") }
        diff.sourceComment?.takeIf { it.isNotBlank() }?.let { append(" COMMENT ${dialect.escapeValue(it)}") }
    }

    private fun addIndexSql(table: String, diff: IndexDiff): String {
        val columns = requireNotNull(diff.sourceColumns).joinToString(", ") { dialect.quoteIdentifier(it) }
        return if (isPrimary(diff)) {
            "ALTER TABLE $table ADD PRIMARY KEY ($columns);"
        } else {
            val unique = if (diff.sourceUnique == true) "UNIQUE " else ""
            "ALTER TABLE $table ADD ${unique}INDEX ${dialect.quoteIdentifier(diff.indexName)} ($columns);"
        }
    }

    private fun dropIndexSql(table: String, diff: IndexDiff): String =
        if (isPrimary(diff)) "ALTER TABLE $table DROP PRIMARY KEY;"
        else "ALTER TABLE $table DROP INDEX ${dialect.quoteIdentifier(diff.indexName)};"

    private fun isPrimary(diff: IndexDiff): Boolean =
        diff.sourcePrimary == true || diff.targetPrimary == true || diff.indexName.equals("PRIMARY", ignoreCase = true)

    private fun previousColumn(columns: List<ColumnInfo>, name: String): String? {
        val index = columns.indexOfFirst { it.name == name }
        return if (index > 0) columns[index - 1].name else null
    }

    private fun qualified(database: String, table: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"

    private fun normalizeType(type: String): String = type.trim().replace(Regex("\\s+"), " ").uppercase()
}
