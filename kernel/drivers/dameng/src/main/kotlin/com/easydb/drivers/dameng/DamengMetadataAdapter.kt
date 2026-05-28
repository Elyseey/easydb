package com.easydb.drivers.dameng

import com.easydb.common.CharsetInfo
import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.IndexInfo
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableDefinition
import com.easydb.common.TableInfo
import java.sql.ResultSet
import java.sql.Types

class DamengMetadataAdapter : MetadataAdapter {

    private val dialect = DamengDialectAdapter()

    override fun listDatabases(session: DatabaseSession): List<DatabaseInfo> {
        val conn = session.getJdbcConnection()
        val schemas = linkedSetOf<String>()

        try {
            conn.metaData.schemas.use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_SCHEM"))
                }
            }
        } catch (_: Exception) {
            // Some Dameng deployments expose fewer schemas through JDBC metadata.
        }

        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT NAME
                FROM (
                    SELECT USER AS NAME FROM DUAL
                    UNION
                    SELECT USERNAME AS NAME FROM ALL_USERS
                    UNION
                    SELECT OWNER AS NAME
                    FROM ALL_OBJECTS
                    WHERE OBJECT_TYPE IN ('TABLE', 'VIEW')
                )
                ORDER BY NAME
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString("NAME"))
                }
            }
        }

        return schemas
            .filter { it.isNotBlank() }
            .sorted()
            .map { DatabaseInfo(name = it) }
    }

    override fun listTables(session: DatabaseSession, database: String): List<TableInfo> {
        val schema = normalizeName(database)
        val result = mutableListOf<TableInfo>()
        val conn = session.getJdbcConnection()

        conn.prepareStatement(
            """
            SELECT t.OWNER, t.TABLE_NAME, t.NUM_ROWS, comm.COMMENTS
            FROM ALL_TABLES t
            LEFT JOIN ALL_TAB_COMMENTS comm
              ON t.OWNER = comm.OWNER
             AND t.TABLE_NAME = comm.TABLE_NAME
            WHERE t.OWNER = ?
            ORDER BY t.TABLE_NAME
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        TableInfo(
                            name = rs.getString("TABLE_NAME"),
                            schema = rs.getString("OWNER"),
                            type = "table",
                            rowCount = rs.getNullableLong("NUM_ROWS"),
                            comment = rs.getString("COMMENTS")?.trim(),
                            engine = "DM"
                        )
                    )
                }
            }
        }

        conn.prepareStatement(
            """
            SELECT v.OWNER, v.VIEW_NAME, comm.COMMENTS
            FROM ALL_VIEWS v
            LEFT JOIN ALL_TAB_COMMENTS comm
              ON v.OWNER = comm.OWNER
             AND v.VIEW_NAME = comm.TABLE_NAME
            WHERE v.OWNER = ?
            ORDER BY v.VIEW_NAME
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        TableInfo(
                            name = rs.getString("VIEW_NAME"),
                            schema = rs.getString("OWNER"),
                            type = "view",
                            comment = rs.getString("COMMENTS")?.trim(),
                            engine = "DM"
                        )
                    )
                }
            }
        }

        return result
            .distinctBy { "${it.type}:${it.schema}:${it.name}" }
            .sortedWith(compareBy<TableInfo> { it.type }.thenBy { it.name })
    }

    override fun getTableDefinition(session: DatabaseSession, database: String, table: String): TableDefinition {
        val schema = normalizeName(database)
        val objectName = normalizeName(table)
        val tableInfo = findTableInfo(session, schema, objectName)
            ?: TableInfo(name = objectName, schema = schema, type = "table", engine = "DM")

        val (ddl, ddlSource) = resolveDdl(session, schema, objectName)
        val columns = getColumns(session, schema, objectName)

        return TableDefinition(
            table = tableInfo,
            columns = columns,
            indexes = getIndexes(session, schema, objectName),
            ddl = appendCommentsToDdl(session, schema, objectName, ddl, columns),
            ddlSource = ddlSource
        )
    }

    override fun getTableInfo(session: DatabaseSession, database: String, table: String): TableInfo {
        val schema = normalizeName(database)
        val objectName = normalizeName(table)
        return findTableInfo(session, schema, objectName)
            ?: TableInfo(name = objectName, schema = schema, type = "table", engine = "DM")
    }

    private fun findTableInfo(session: DatabaseSession, schema: String, objectName: String): TableInfo? {
        val conn = session.getJdbcConnection()
        conn.prepareStatement(
            """
            SELECT t.OWNER, t.TABLE_NAME, t.NUM_ROWS, comm.COMMENTS
            FROM ALL_TABLES t
            LEFT JOIN ALL_TAB_COMMENTS comm
              ON t.OWNER = comm.OWNER
             AND t.TABLE_NAME = comm.TABLE_NAME
            WHERE t.OWNER = ?
              AND t.TABLE_NAME = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, objectName)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return TableInfo(
                        name = rs.getString("TABLE_NAME"),
                        schema = rs.getString("OWNER"),
                        type = "table",
                        rowCount = rs.getNullableLong("NUM_ROWS"),
                        comment = rs.getString("COMMENTS")?.trim(),
                        engine = "DM"
                    )
                }
            }
        }

        conn.prepareStatement(
            """
            SELECT v.OWNER, v.VIEW_NAME, comm.COMMENTS
            FROM ALL_VIEWS v
            LEFT JOIN ALL_TAB_COMMENTS comm
              ON v.OWNER = comm.OWNER
             AND v.VIEW_NAME = comm.TABLE_NAME
            WHERE v.OWNER = ?
              AND v.VIEW_NAME = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, objectName)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return TableInfo(
                        name = rs.getString("VIEW_NAME"),
                        schema = rs.getString("OWNER"),
                        type = "view",
                        comment = rs.getString("COMMENTS")?.trim(),
                        engine = "DM"
                    )
                }
            }
        }

        return null
    }

    override fun getIndexes(session: DatabaseSession, database: String, table: String): List<IndexInfo> {
        val schema = normalizeName(database)
        val objectName = normalizeName(table)
        val conn = session.getJdbcConnection()
        val primaryIndexNames = mutableSetOf<String>()
        val indexMeta = linkedMapOf<String, IndexMeta>()

        conn.prepareStatement(
            """
            SELECT INDEX_NAME
            FROM ALL_CONSTRAINTS
            WHERE OWNER = ?
              AND TABLE_NAME = ?
              AND CONSTRAINT_TYPE = 'P'
              AND INDEX_NAME IS NOT NULL
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, objectName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) primaryIndexNames.add(rs.getString("INDEX_NAME"))
            }
        }

        conn.prepareStatement(
            """
            SELECT OWNER, INDEX_NAME, UNIQUENESS, INDEX_TYPE
            FROM ALL_INDEXES
            WHERE TABLE_OWNER = ?
              AND TABLE_NAME = ?
              AND INDEX_NAME IN (
                  SELECT DISTINCT INDEX_NAME
                  FROM ALL_IND_COLUMNS
                  WHERE TABLE_OWNER = ? AND TABLE_NAME = ?
              )
            ORDER BY INDEX_NAME
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, objectName)
            stmt.setString(3, schema)
            stmt.setString(4, objectName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = rs.getString("INDEX_NAME")
                    indexMeta[indexName] = IndexMeta(
                        unique = rs.getString("UNIQUENESS").equals("UNIQUE", ignoreCase = true),
                        primary = indexName in primaryIndexNames,
                        type = rs.getString("INDEX_TYPE") ?: "BTREE"
                    )
                }
            }
        }

        conn.prepareStatement(
            """
            SELECT INDEX_NAME, COLUMN_NAME
            FROM ALL_IND_COLUMNS
            WHERE TABLE_OWNER = ?
              AND TABLE_NAME = ?
            ORDER BY INDEX_NAME, COLUMN_POSITION
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, objectName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val indexName = rs.getString("INDEX_NAME")
                    val meta = indexMeta.getOrPut(indexName) {
                        IndexMeta(unique = false, primary = indexName in primaryIndexNames, type = "BTREE")
                    }
                    meta.columns.add(rs.getString("COLUMN_NAME"))
                }
            }
        }

        return indexMeta
            .filter { it.value.columns.isNotEmpty() }
            .map { (name, meta) ->
                IndexInfo(
                    name = if (meta.primary) "PRIMARY" else name,
                    columns = meta.columns,
                    isUnique = meta.unique || meta.primary,
                    isPrimary = meta.primary,
                    type = meta.type
                )
            }
    }

    override fun previewRows(
        session: DatabaseSession,
        database: String,
        table: String,
        limit: Int,
        where: String?,
        orderBy: String?,
        offset: Int
    ): List<Map<String, String?>> {
        val conn = session.getJdbcConnection()
        val sql = buildPreviewSql(database, table, limit.coerceIn(1, 5000), where, orderBy, offset.coerceAtLeast(0))
        val rows = mutableListOf<Map<String, String?>>()

        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                val meta = rs.metaData
                val columnCount = meta.columnCount
                while (rs.next()) {
                    val row = linkedMapOf<String, String?>()
                    for (i in 1..columnCount) {
                        val columnName = meta.getColumnLabel(i) ?: meta.getColumnName(i)
                        val colType = meta.getColumnType(i)
                        row[columnName] = if (isBinaryColumn(colType)) {
                            rs.getBytes(i)?.let { bytes ->
                                val sizeLabel = when {
                                    bytes.size < 1024 -> "${bytes.size} B"
                                    bytes.size < 1024 * 1024 -> "${bytes.size / 1024} KB"
                                    else -> "${bytes.size / (1024 * 1024)} MB"
                                }
                                "[BLOB $sizeLabel]"
                            }
                        } else {
                            rs.getString(i)
                        }
                    }
                    rows.add(row)
                }
            }
        }

        return rows
    }

    override fun getDdl(session: DatabaseSession, database: String, table: String): String {
        val schema = normalizeName(database)
        val objectName = normalizeName(table)
        val ddl = resolveDdl(session, schema, objectName).first
        return appendCommentsToDdl(session, schema, objectName, ddl)
    }

    /**
     * 返回 (ddl, ddlSource)：先试原生 DBMS_METADATA.GET_DDL；失败降级拼装。
     */
    private fun resolveDdl(session: DatabaseSession, schema: String, objectName: String): Pair<String, String?> {
        val objectType = getObjectType(session, schema, objectName)
        for (type in listOfNotNull(objectType, "TABLE", "VIEW")) {
            val ddl = tryGetDdl(session, type, schema, objectName)
            if (!ddl.isNullOrBlank()) return ddl to "native"
        }
        val fallback = buildFallbackDdl(session, schema, objectName)
        return if (fallback.isBlank()) "" to null else fallback to "synthesized"
    }

    override fun createDatabase(session: DatabaseSession, name: String, charset: String, collation: String) {
        val schema = normalizeName(name)
        require(schema.isNotBlank()) { "Schema 名称不能为空" }
        val conn = session.getJdbcConnection()
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE SCHEMA ${dialect.quoteIdentifier(schema)}")
        }
    }

    override fun dropDatabase(session: DatabaseSession, name: String) {
        throw UnsupportedOperationException("达梦暂不支持删除 schema")
    }

    override fun listCharsets(session: DatabaseSession): List<CharsetInfo> = emptyList()

    override fun getColumns(session: DatabaseSession, database: String, table: String): List<ColumnInfo> {
        val schema = normalizeName(database)
        val objectName = normalizeName(table)
        val primaryColumns = getPrimaryColumns(session, schema, objectName)

        return try {
            getColumnsWithCommentView(session, schema, objectName, primaryColumns, "DBA_COL_COMMENTS")
        } catch (_: Exception) {
            getColumnsWithCommentView(session, schema, objectName, primaryColumns, "ALL_COL_COMMENTS")
        }
    }

    private fun getColumnsWithCommentView(
        session: DatabaseSession,
        schema: String,
        objectName: String,
        primaryColumns: Set<String>,
        commentView: String
    ): List<ColumnInfo> {
        val conn = session.getJdbcConnection()
        val columns = mutableListOf<ColumnInfo>()
        conn.prepareStatement(
            """
            SELECT c.COLUMN_NAME, c.DATA_TYPE, c.DATA_LENGTH, c.CHAR_LENGTH, c.DATA_PRECISION, c.DATA_SCALE,
                   c.NULLABLE, c.DATA_DEFAULT, comm.COMMENTS
            FROM ALL_TAB_COLUMNS c
            LEFT JOIN $commentView comm
              ON c.OWNER = comm.OWNER
             AND c.TABLE_NAME = comm.TABLE_NAME
             AND c.COLUMN_NAME = comm.COLUMN_NAME
            WHERE c.OWNER = ?
              AND c.TABLE_NAME = ?
            ORDER BY c.COLUMN_ID
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, objectName)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("COLUMN_NAME")
                    columns.add(
                        ColumnInfo(
                            name = name,
                            type = buildColumnType(rs),
                            nullable = rs.getString("NULLABLE") != "N",
                            defaultValue = rs.getString("DATA_DEFAULT")?.trim(),
                            isPrimaryKey = name in primaryColumns,
                            isAutoIncrement = false,
                            comment = rs.getString("COMMENTS")?.trim()
                        )
                    )
                }
            }
        }

        return columns
    }

    private fun getPrimaryColumns(session: DatabaseSession, schema: String, table: String): Set<String> {
        val conn = session.getJdbcConnection()
        val columns = linkedSetOf<String>()
        conn.prepareStatement(
            """
            SELECT c.COLUMN_NAME
            FROM ALL_CONSTRAINTS t
            JOIN ALL_CONS_COLUMNS c
              ON t.OWNER = c.OWNER
             AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME
             AND t.TABLE_NAME = c.TABLE_NAME
            WHERE t.OWNER = ?
              AND t.TABLE_NAME = ?
              AND t.CONSTRAINT_TYPE = 'P'
            ORDER BY c.POSITION
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
            stmt.executeQuery().use { rs ->
                while (rs.next()) columns.add(rs.getString("COLUMN_NAME"))
            }
        }
        return columns
    }

    private fun buildColumnType(rs: ResultSet): String {
        val dataType = rs.getString("DATA_TYPE") ?: return "UNKNOWN"
        val length = rs.getNullableLong("DATA_LENGTH")
        val charLength = rs.getNullableLong("CHAR_LENGTH")
        val precision = rs.getNullableLong("DATA_PRECISION")
        val scale = rs.getNullableLong("DATA_SCALE")

        return when (dataType.uppercase()) {
            "CHAR", "NCHAR", "VARCHAR", "VARCHAR2", "NVARCHAR2" ->
                (charLength ?: length)?.let { "$dataType($it)" } ?: dataType
            "BINARY", "VARBINARY" ->
                length?.let { "$dataType($it)" } ?: dataType
            "DECIMAL", "NUMERIC", "NUMBER" -> when {
                precision != null && scale != null -> "$dataType($precision,$scale)"
                precision != null -> "$dataType($precision)"
                else -> dataType
            }
            "TIMESTAMP" -> if (scale != null) "$dataType($scale)" else dataType
            else -> dataType
        }
    }

    private fun buildPreviewSql(
        database: String,
        table: String,
        limit: Int,
        where: String?,
        orderBy: String?,
        offset: Int
    ): String {
        val baseSql = buildString {
            append("SELECT * FROM ${quoteQualified(database, table)}")
            sanitizeWhere(where)?.let { append(" WHERE $it") }
            sanitizeOrderBy(orderBy)?.let { append(" ORDER BY $it") }
        }
        return dialect.buildPaginationSql(baseSql, limit, offset)
    }

    private fun sanitizeWhere(where: String?): String? {
        val sanitized = where?.trim()?.replace(Regex(";.*$"), "") ?: return null
        if (sanitized.isBlank()) return null
        val upper = sanitized.uppercase()
        val forbidden = listOf("DROP ", "DELETE ", "ALTER ", "TRUNCATE ", "INSERT ", "UPDATE ", "CREATE ", "GRANT ", "REVOKE ")
        return sanitized.takeIf { forbidden.none { keyword -> upper.contains(keyword) } }
    }

    private fun sanitizeOrderBy(orderBy: String?): String? {
        val sanitized = orderBy?.trim()?.replace(Regex("[;'\"()]"), "") ?: return null
        return sanitized.takeIf { it.isNotBlank() }
    }

    private fun getObjectType(session: DatabaseSession, schema: String, name: String): String? {
        val conn = session.getJdbcConnection()
        conn.prepareStatement(
            """
            SELECT OBJECT_TYPE
            FROM ALL_OBJECTS
            WHERE OWNER = ?
              AND OBJECT_NAME = ?
              AND OBJECT_TYPE IN ('TABLE', 'VIEW')
            ORDER BY CASE OBJECT_TYPE WHEN 'TABLE' THEN 1 ELSE 2 END
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, name)
            stmt.executeQuery().use { rs ->
                if (rs.next()) return rs.getString("OBJECT_TYPE")
            }
        }
        return null
    }

    private fun tryGetDdl(session: DatabaseSession, type: String, schema: String, name: String): String? {
        return try {
            session.getJdbcConnection().prepareStatement(
                "SELECT DBMS_METADATA.GET_DDL(?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, type)
                stmt.setString(2, name)
                stmt.setString(3, schema)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFallbackDdl(session: DatabaseSession, schema: String, table: String): String {
        val columns = getColumns(session, schema, table)
        if (columns.isEmpty()) return ""

        val columnLines = columns.map { column ->
            buildString {
                append("  ${dialect.quoteIdentifier(column.name)} ${column.type}")
                if (!column.nullable) append(" NOT NULL")
                if (!column.defaultValue.isNullOrBlank()) append(" DEFAULT ${column.defaultValue}")
            }
        }

        return buildString {
            appendLine("-- Fallback DDL generated from Dameng catalog metadata.")
            appendLine("CREATE TABLE ${quoteQualified(schema, table)} (")
            append(columnLines.joinToString(",\n"))
            appendLine()
            append(");")
        }
    }

    private fun appendCommentsToDdl(
        session: DatabaseSession,
        schema: String,
        objectName: String,
        ddl: String,
        columns: List<ColumnInfo> = getColumns(session, schema, objectName)
    ): String {
        if (ddl.isBlank()) return ddl

        val tableComment = getTableComment(session, schema, objectName)
        val commentStatements = mutableListOf<String>()
        val qualifiedName = quoteQualified(schema, objectName)

        if (!tableComment.isNullOrBlank() && !ddl.containsIgnoreCase("COMMENT ON TABLE $qualifiedName")) {
            commentStatements.add(
                "COMMENT ON TABLE $qualifiedName IS ${quoteStringLiteral(tableComment)};"
            )
        }

        columns
            .filter { !it.comment.isNullOrBlank() }
            .forEach { column ->
                val columnTarget = "$qualifiedName.${dialect.quoteIdentifier(column.name)}"
                if (!ddl.containsIgnoreCase("COMMENT ON COLUMN $columnTarget")) {
                    commentStatements.add(
                        "COMMENT ON COLUMN $columnTarget IS ${quoteStringLiteral(column.comment!!)};"
                    )
                }
            }

        if (commentStatements.isEmpty()) return ddl
        return buildString {
            append(ddl.trimEnd())
            appendLine()
            appendLine()
            append(commentStatements.joinToString("\n"))
        }
    }

    private fun getTableComment(session: DatabaseSession, schema: String, objectName: String): String? {
        val conn = session.getJdbcConnection()
        return conn.prepareStatement(
            """
            SELECT COMMENTS
            FROM ALL_TAB_COMMENTS
            WHERE OWNER = ?
              AND TABLE_NAME = ?
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, objectName)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("COMMENTS")?.trim() else null
            }
        }
    }

    private fun quoteStringLiteral(value: String): String {
        return "'${value.replace("'", "''")}'"
    }

    private fun String.containsIgnoreCase(value: String): Boolean {
        return indexOf(value, ignoreCase = true) >= 0
    }

    private fun quoteQualified(schema: String, name: String): String {
        return "${dialect.quoteIdentifier(normalizeName(schema))}.${dialect.quoteIdentifier(normalizeName(name))}"
    }

    private fun normalizeName(value: String): String = value.trim().uppercase()

    private fun isBinaryColumn(columnType: Int): Boolean {
        return columnType in setOf(
            Types.BLOB,
            Types.BINARY,
            Types.VARBINARY,
            Types.LONGVARBINARY
        )
    }

    private fun ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private data class IndexMeta(
        val unique: Boolean,
        val primary: Boolean,
        val type: String,
        val columns: MutableList<String> = mutableListOf()
    )
}
