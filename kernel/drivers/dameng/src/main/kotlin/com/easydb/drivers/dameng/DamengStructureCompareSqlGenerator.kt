package com.easydb.drivers.dameng

import com.easydb.common.ColumnDiff
import com.easydb.common.ColumnInfo
import com.easydb.common.CompareOptions
import com.easydb.common.IndexDiff
import com.easydb.common.StructureCompareSqlGenerator

class DamengStructureCompareSqlGenerator : StructureCompareSqlGenerator {
    private val dialect = DamengDialectAdapter()

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
                    statements += "ALTER TABLE $table ADD ${columnDefinition(diff)};"
                    addComment(statements, table, column, diff.sourceComment)
                }
                "removed" -> if (options.includeDropStatements) {
                    statements += "ALTER TABLE $table DROP COLUMN $column;"
                }
                "modified" -> {
                    statements += "ALTER TABLE $table MODIFY ${columnDefinition(diff, includeNullability = false)};"
                    if (diff.sourceNullable != diff.targetNullable) {
                        val nullability = if (diff.sourceNullable == false) "SET NOT NULL" else "SET NULL"
                        statements += "ALTER TABLE $table ALTER COLUMN $column $nullability;"
                    }
                    if (!options.ignoreComment && diff.sourceComment != diff.targetComment) {
                        statements += "COMMENT ON COLUMN $table.$column IS ${dialect.escapeValue(diff.sourceComment)};"
                    }
                }
            }
        }

        indexDiffs.forEach { diff ->
            when (diff.status) {
                "added" -> statements += addIndexSql(targetDatabase, tableName, diff)
                "removed" -> if (options.includeDropStatements) statements += dropIndexSql(targetDatabase, tableName, diff)
                "modified" -> {
                    statements += dropIndexSql(targetDatabase, tableName, diff)
                    statements += addIndexSql(targetDatabase, tableName, diff)
                }
            }
        }
        return statements.joinToString("\n")
    }

    private fun columnDefinition(diff: ColumnDiff, includeNullability: Boolean = true): String = buildString {
        append("${dialect.quoteIdentifier(diff.columnName)} ${requireNotNull(diff.sourceType)}")
        diff.sourceDefault?.let { append(" DEFAULT $it") }
        if (includeNullability && diff.sourceNullable == false) append(" NOT NULL")
    }

    private fun addComment(statements: MutableList<String>, table: String, column: String, comment: String?) {
        comment?.takeIf { it.isNotBlank() }?.let {
            statements += "COMMENT ON COLUMN $table.$column IS ${dialect.escapeValue(it)};"
        }
    }

    private fun addIndexSql(database: String, tableName: String, diff: IndexDiff): String {
        val columns = requireNotNull(diff.sourceColumns).joinToString(", ") { dialect.quoteIdentifier(it) }
        val table = qualified(database, tableName)
        return if (isPrimary(diff)) {
            "ALTER TABLE $table ADD PRIMARY KEY ($columns);"
        } else {
            val unique = if (diff.sourceUnique == true) "UNIQUE " else ""
            "CREATE ${unique}INDEX ${dialect.quoteIdentifier(diff.indexName)} ON $table ($columns);"
        }
    }

    private fun dropIndexSql(database: String, tableName: String, diff: IndexDiff): String =
        if (isPrimary(diff)) {
            "ALTER TABLE ${qualified(database, tableName)} DROP PRIMARY KEY;"
        } else {
            "DROP INDEX ${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(diff.indexName)};"
        }

    private fun isPrimary(diff: IndexDiff): Boolean =
        diff.sourcePrimary == true || diff.targetPrimary == true || diff.indexName.equals("PRIMARY", ignoreCase = true)

    private fun qualified(database: String, table: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"

    private fun normalizeType(type: String): String = type.trim()
        .replace(Regex("\\s+"), " ")
        .replace("VARCHAR(", "VARCHAR2(", ignoreCase = true)
        .replace("NUMERIC(", "NUMBER(", ignoreCase = true)
        .replace("DECIMAL(", "NUMBER(", ignoreCase = true)
        .uppercase()
}
