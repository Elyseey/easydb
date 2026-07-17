package com.easydb.common

/**
 * 数据编辑 SQL 生成引擎
 * 将前端提交的行变更（insert/update/delete）转换为可执行的 SQL 语句
 */
data class DataEditStatement(
    val sql: String,
    val parameters: List<String?>,
    val previewSql: String
)

class DataEditService {

    /**
     * 根据变更请求生成 SQL 语句列表
     * @param dialect 方言适配器，用于正确引用标识符和转义值
     * @param tableName 目标表名
     * @param changes 变更列表
     * @return 生成的 SQL 语句列表
     */
    fun generateStatements(
        dialect: DialectAdapter,
        tableName: String,
        changes: List<RowChange>
    ): List<DataEditStatement> {
        return changes.mapNotNull { change ->
            when (change.type) {
                "insert" -> generateInsertStatement(dialect, tableName, change)
                "update" -> generateUpdateStatement(dialect, tableName, change)
                "delete" -> generateDeleteStatement(dialect, tableName, change)
                else -> null
            }
        }
    }

    /**
     * 生成 INSERT 语句
     */
    private fun generateInsertStatement(
        dialect: DialectAdapter,
        tableName: String,
        change: RowChange
    ): DataEditStatement {
        val columns = change.values.keys.toList()
        val parameters = columns.map { change.values[it] }
        val colNames = columns.joinToString(", ") { dialect.quoteIdentifier(it) }
        val previewValues = parameters.joinToString(", ") { dialect.escapeValue(it) }
        return DataEditStatement(
            sql = dialect.buildInsertSql(tableName, columns),
            parameters = parameters,
            previewSql = "INSERT INTO ${dialect.quoteIdentifier(tableName)} ($colNames) VALUES ($previewValues)"
        )
    }

    /**
     * 生成 UPDATE 语句
     * 使用主键作为 WHERE 条件，只更新有变化的列
     */
    private fun generateUpdateStatement(
        dialect: DialectAdapter,
        tableName: String,
        change: RowChange
    ): DataEditStatement? {
        if (change.primaryKeys.isEmpty()) return null

        // 只更新有变化的列（对比 values 和 oldValues）
        val changedCols = change.values.filter { (k, v) ->
            change.oldValues[k] != v
        }
        if (changedCols.isEmpty()) return null

        val setClause = changedCols.entries.joinToString(", ") { (col, value) ->
            "${dialect.quoteIdentifier(col)} = ${dialect.escapeValue(value)}"
        }
        val whereClause = change.primaryKeys.entries.joinToString(" AND ") { (col, value) ->
            "${dialect.quoteIdentifier(col)} = ${dialect.escapeValue(value)}"
        }
        return DataEditStatement(
            sql = dialect.buildUpdateSql(tableName, changedCols.keys.toList(), change.primaryKeys.keys.toList()),
            parameters = changedCols.values.toList() + change.primaryKeys.values.toList(),
            previewSql = "UPDATE ${dialect.quoteIdentifier(tableName)} SET $setClause WHERE $whereClause"
        )
    }

    /**
     * 生成 DELETE 语句
     */
    private fun generateDeleteStatement(
        dialect: DialectAdapter,
        tableName: String,
        change: RowChange
    ): DataEditStatement? {
        if (change.primaryKeys.isEmpty()) return null

        val whereClause = change.primaryKeys.entries.joinToString(" AND ") { (col, value) ->
            "${dialect.quoteIdentifier(col)} = ${dialect.escapeValue(value)}"
        }
        return DataEditStatement(
            sql = dialect.buildDeleteSql(tableName, change.primaryKeys.keys.toList()),
            parameters = change.primaryKeys.values.toList(),
            previewSql = "DELETE FROM ${dialect.quoteIdentifier(tableName)} WHERE $whereClause"
        )
    }
}
