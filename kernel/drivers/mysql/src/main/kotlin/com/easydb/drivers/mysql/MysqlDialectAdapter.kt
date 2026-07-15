package com.easydb.drivers.mysql

import com.easydb.common.*

/**
 * MySQL 方言适配器
 * 处理 MySQL 特定的 SQL 生成和标识符引用
 */
class MysqlDialectAdapter : DialectAdapter {

    override fun quoteIdentifier(name: String): String {
        return "`${name.replace("`", "``")}`"
    }

    override fun buildCreateTable(table: TableDefinition): String {
        val sb = StringBuilder()
        sb.appendLine("CREATE TABLE ${quoteIdentifier(table.table.name)} (")

        val lines = mutableListOf<String>()

        // 字段定义
        for (col in table.columns) {
            val line = buildString {
                append("  ${quoteIdentifier(col.name)} ${col.type}")
                if (!col.nullable) append(" NOT NULL")
                if (!col.defaultValue.isNullOrBlank() && !col.isAutoIncrement) {
                    val dv = col.defaultValue!!.trim()
                    // CURRENT_TIMESTAMP、NULL 等关键字不加引号
                    if (dv.uppercase() in listOf("CURRENT_TIMESTAMP", "NULL", "NOW()") || dv.startsWith("'")) {
                        append(" DEFAULT $dv")
                    } else {
                        append(" DEFAULT '$dv'")
                    }
                }
                if (col.isAutoIncrement) append(" AUTO_INCREMENT")
                if (!col.comment.isNullOrBlank()) append(" COMMENT ${escapeValue(col.comment)}")
            }
            lines.add(line)
        }

        // 主键
        val pkColumns = table.columns.filter { it.isPrimaryKey }
        if (pkColumns.isNotEmpty()) {
            val pkCols = pkColumns.joinToString(", ") { quoteIdentifier(it.name) }
            lines.add("  PRIMARY KEY ($pkCols)")
        }

        // 索引
        for (idx in table.indexes) {
            if (idx.isPrimary) continue
            val idxCols = idx.columns.joinToString(", ") { quoteIdentifier(it) }
            val prefix = if (idx.isUnique) "UNIQUE KEY" else "KEY"
            lines.add("  $prefix ${quoteIdentifier(idx.name)} ($idxCols)")
        }

        sb.append(lines.joinToString(",\n"))
        sb.appendLine()
        sb.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        if (!table.table.comment.isNullOrBlank()) {
            sb.append(" COMMENT=${escapeValue(table.table.comment)}")
        }

        return sb.toString()
    }

    override fun buildInsert(tableName: String, columns: List<String>): String {
        val cols = columns.joinToString(", ") { quoteIdentifier(it) }
        val placeholders = columns.joinToString(", ") { "?" }
        return "INSERT INTO ${quoteIdentifier(tableName)} ($cols) VALUES ($placeholders)"
    }

    override fun buildSwitchDatabaseSql(database: String): String {
        return "USE `${database.replace("`", "``")}`"
    }

    override fun formatExportStringLiteral(value: String): String {
        val escaped = buildString(value.length + 16) {
            value.forEach { ch ->
                when (ch) {
                    '\u0000' -> append("\\0")
                    '\'' -> append("\\'")
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u0008' -> append("\\b")
                    '\u001A' -> append("\\Z")
                    else -> append(ch)
                }
            }
        }
        return "'$escaped'"
    }

    override fun buildCreateNamespaceSql(name: String, charset: String?, collation: String?): String {
        val charsetSql = charset?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: "utf8mb4"
        val collationSql = collation?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
            ?: "utf8mb4_general_ci"
        return "CREATE DATABASE ${quoteIdentifier(name)} CHARACTER SET $charsetSql COLLATE $collationSql"
    }

    override fun beforeLogicalRestore(connection: java.sql.Connection) {
        connection.createStatement().use { it.execute("SET FOREIGN_KEY_CHECKS=0") }
        connection.createStatement().use { it.execute("SET UNIQUE_CHECKS=0") }
    }

    override fun afterLogicalRestore(connection: java.sql.Connection) {
        connection.createStatement().use { it.execute("SET FOREIGN_KEY_CHECKS=1") }
        connection.createStatement().use { it.execute("SET UNIQUE_CHECKS=1") }
    }

    override val paginationStrategy: PaginationStrategy = PaginationStrategy.LIMIT_OFFSET
}
