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

        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT USER").use { rs ->
                if (rs.next()) schemas.add(rs.getString(1))
            }
        }

        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                SELECT USERNAME
                FROM ALL_USERS
                ORDER BY USERNAME
                """.trimIndent()
            ).use { rs ->
                while (rs.next()) {
                    schemas.add(rs.getString("USERNAME"))
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
            SELECT OWNER, TABLE_NAME, NUM_ROWS
            FROM ALL_TABLES
            WHERE OWNER = ?
            ORDER BY TABLE_NAME
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
                            engine = "DM"
                        )
                    )
                }
            }
        }

        conn.prepareStatement(
            """
            SELECT OWNER, VIEW_NAME
            FROM ALL_VIEWS
            WHERE OWNER = ?
            ORDER BY VIEW_NAME
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
        val tableInfo = listTables(session, schema).firstOrNull { it.name.equals(objectName, ignoreCase = true) }
            ?: TableInfo(name = objectName, schema = schema, type = "table", engine = "DM")

        val (ddl, ddlSource) = resolveDdl(session, schema, objectName)

        return TableDefinition(
            table = tableInfo,
            columns = getColumns(session, schema, objectName),
            indexes = getIndexes(session, schema, objectName),
            ddl = ddl,
            ddlSource = ddlSource
        )
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
        return resolveDdl(session, normalizeName(database), normalizeName(table)).first
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
        throw UnsupportedOperationException("达梦暂不支持创建 schema")
    }

    override fun dropDatabase(session: DatabaseSession, name: String) {
        throw UnsupportedOperationException("达梦暂不支持删除 schema")
    }

    override fun listCharsets(session: DatabaseSession): List<CharsetInfo> = emptyList()

    private fun getColumns(session: DatabaseSession, schema: String, table: String): List<ColumnInfo> {
        val primaryColumns = getPrimaryColumns(session, schema, table)
        val conn = session.getJdbcConnection()
        val columns = mutableListOf<ColumnInfo>()

        conn.prepareStatement(
            """
            SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE,
                   NULLABLE, DATA_DEFAULT
            FROM ALL_TAB_COLUMNS
            WHERE OWNER = ?
              AND TABLE_NAME = ?
            ORDER BY COLUMN_ID
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, schema)
            stmt.setString(2, table)
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
                            comment = null
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
        val precision = rs.getNullableLong("DATA_PRECISION")
        val scale = rs.getNullableLong("DATA_SCALE")

        return when (dataType.uppercase()) {
            "CHAR", "NCHAR", "VARCHAR", "VARCHAR2", "NVARCHAR2", "BINARY", "VARBINARY" ->
                if (length != null) "$dataType($length)" else dataType
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
