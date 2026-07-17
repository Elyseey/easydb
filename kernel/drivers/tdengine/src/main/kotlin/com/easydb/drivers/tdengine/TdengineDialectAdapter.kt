package com.easydb.drivers.tdengine

import com.easydb.common.DialectAdapter
import com.easydb.common.PaginationStrategy
import com.easydb.common.TableDefinition

class TdengineDialectAdapter : DialectAdapter {

    override fun quoteIdentifier(name: String): String =
        "`${name.replace("`", "``")}`"

    override fun buildCreateTable(table: TableDefinition): String {
        throw UnsupportedOperationException("TDengine 可视化表设计将在后续阶段实现，请先使用 SQL 编辑器")
    }

    override fun buildInsert(tableName: String, columns: List<String>): String {
        val quotedColumns = columns.joinToString(", ") { quoteIdentifier(it) }
        val placeholders = columns.joinToString(", ") { "?" }
        return "INSERT INTO ${quoteIdentifier(tableName)} ($quotedColumns) VALUES ($placeholders)"
    }

    override fun buildSwitchDatabaseSql(database: String): String? =
        database.takeIf { it.isNotBlank() }?.let { "USE ${quoteIdentifier(it)}" }

    override val paginationStrategy: PaginationStrategy = PaginationStrategy.LIMIT_OFFSET
}
