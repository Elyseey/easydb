package com.easydb.drivers.dameng

import com.easydb.common.*

class DamengDialectAdapter : DialectAdapter {

    override fun quoteIdentifier(name: String): String {
        return "\"${name.replace("\"", "\"\"")}\""
    }

    override fun buildCreateTable(table: TableDefinition): String {
        throw UnsupportedOperationException("达梦暂不支持 CREATE TABLE 生成")
    }

    override fun buildInsert(tableName: String, columns: List<String>): String {
        val cols = columns.joinToString(", ") { quoteIdentifier(it) }
        val placeholders = columns.joinToString(", ") { "?" }
        return "INSERT INTO ${quoteIdentifier(tableName)} ($cols) VALUES ($placeholders)"
    }

    override fun buildSwitchDatabaseSql(database: String): String? {
        return null
    }

    override val paginationStrategy: PaginationStrategy = PaginationStrategy.OFFSET_FETCH
}
