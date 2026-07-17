package com.easydb.drivers.dameng

import com.easydb.common.*

class DamengDialectAdapter : DialectAdapter {

    override fun quoteIdentifier(name: String): String {
        return "\"${name.replace("\"", "\"\"")}\""
    }

    override fun buildCreateTable(table: TableDefinition): String {
        val lines = mutableListOf<String>()

        for (col in table.columns) {
            val line = buildString {
                append("  ${quoteIdentifier(col.name)} ${mapDataType(col)}")
                if (!col.nullable) append(" NOT NULL")
                if (!col.defaultValue.isNullOrBlank() && !col.isAutoIncrement) {
                    val dv = col.defaultValue!!.trim()
                    if (dv.uppercase() in listOf("CURRENT_TIMESTAMP", "NULL", "SYSDATE") || dv.startsWith("'")) {
                        append(" DEFAULT $dv")
                    } else {
                        append(" DEFAULT '$dv'")
                    }
                }
                if (col.isAutoIncrement) append(" IDENTITY(1,1)")
            }
            lines.add(line)
        }

        val pkColumns = table.columns.filter { it.isPrimaryKey }
        if (pkColumns.isNotEmpty()) {
            val pkCols = pkColumns.joinToString(", ") { quoteIdentifier(it.name) }
            lines.add("  PRIMARY KEY ($pkCols)")
        }

        for (idx in table.indexes) {
            if (idx.isPrimary) continue
            val idxCols = idx.columns.joinToString(", ") { quoteIdentifier(it) }
            val prefix = if (idx.isUnique) "UNIQUE " else ""
            lines.add("  ${prefix}INDEX ${quoteIdentifier(idx.name)} ($idxCols)")
        }

        return "CREATE TABLE ${quoteIdentifier(table.table.name)} (\n${lines.joinToString(",\n")}\n)"
    }

    override fun buildCreateTableStatements(table: TableDefinition): List<String> {
        val statements = mutableListOf(buildCreateTable(table))
        val tableName = quoteIdentifier(table.table.name)

        if (!table.table.comment.isNullOrBlank()) {
            statements.add("COMMENT ON TABLE $tableName IS ${escapeValue(table.table.comment)}")
        }

        table.columns
            .filter { !it.comment.isNullOrBlank() }
            .forEach { column ->
                statements.add("COMMENT ON COLUMN $tableName.${quoteIdentifier(column.name)} IS ${escapeValue(column.comment)}")
            }

        return statements
    }

    private fun mapDataType(col: ColumnInfo): String {
        val rawType = col.type.trim()
        val upper = rawType.uppercase().replace(Regex("\\s+AUTO_INCREMENT\\b"), "")
        val match = Regex("""^([A-Z0-9_]+)(?:\(([^)]*)\))?$""").matchEntire(upper)
        val base = match?.groupValues?.getOrNull(1) ?: upper
        val args = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }

        return when (base) {
            "AUTO_INCREMENT" -> "INT"
            "TINYINT" -> if (args == "1") "BIT" else "SMALLINT"
            "SMALLINT", "INT", "INTEGER", "BIGINT" -> base
            "VARCHAR", "VARCHAR2" -> "VARCHAR2(${args ?: "255"})"
            "CHAR", "NCHAR", "NVARCHAR2" -> args?.let { "$base($it)" } ?: base
            "DECIMAL", "NUMERIC", "NUMBER" -> args?.let { "NUMBER($it)" } ?: "NUMBER"
            "TEXT", "MEDIUMTEXT", "LONGTEXT", "JSON" -> "CLOB"
            "BLOB", "MEDIUMBLOB", "LONGBLOB" -> "BLOB"
            "DATETIME", "TIMESTAMP" -> "TIMESTAMP"
            "DATE", "TIME", "CLOB", "BINARY", "VARBINARY", "DOUBLE", "FLOAT" -> args?.let { "$base($it)" } ?: base
            else -> rawType
        }
    }

    override fun buildInsert(tableName: String, columns: List<String>): String {
        val cols = columns.joinToString(", ") { quoteIdentifier(it) }
        val placeholders = columns.joinToString(", ") { "?" }
        return "INSERT INTO ${quoteIdentifier(tableName)} ($cols) VALUES ($placeholders)"
    }

    override fun buildSwitchDatabaseSql(database: String): String? {
        return "ALTER SESSION SET CURRENT_SCHEMA = ${quoteIdentifier(database)}"
    }

    override fun buildCreateNamespaceSql(name: String, charset: String?, collation: String?): String =
        "CREATE SCHEMA ${quoteIdentifier(name)}"

    override fun normalizeNewNamespaceName(name: String): String =
        DamengIdentifierPolicy.newUnquotedName(name)

    override fun executeLogicalRestoreDdl(connection: java.sql.Connection, ddl: String, objectType: String) {
        DamengRestoreDdl.statements(ddl, objectType).forEach { statement ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    override val paginationStrategy: PaginationStrategy = PaginationStrategy.OFFSET_FETCH
}
