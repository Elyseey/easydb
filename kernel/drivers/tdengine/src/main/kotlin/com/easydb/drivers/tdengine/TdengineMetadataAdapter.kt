package com.easydb.drivers.tdengine

import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.IndexInfo
import com.easydb.common.MetadataAdapter
import com.easydb.common.TableDefinition
import com.easydb.common.TableInfo
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesChildTable
import com.easydb.common.TimeSeriesChildTablePage
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesMetadataLimits
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagValue
import java.sql.Connection
import java.sql.ResultSet

internal object TdengineMetadataSql {
    const val MAX_PREVIEW_ROWS = 1_000

    val listDatabases = """
        SELECT name
        FROM information_schema.ins_databases
        ORDER BY name
    """.trimIndent()

    fun listStables(database: String) = """
        SELECT stable_name, db_name, create_time, table_comment
        FROM information_schema.ins_stables
        WHERE db_name = ${stringLiteral(database)}
        ORDER BY stable_name
    """.trimIndent()

    fun listBasicTables(database: String) = """
        SELECT table_name, db_name, create_time, columns, table_comment
        FROM information_schema.ins_tables
        WHERE db_name = ${stringLiteral(database)} AND type = 'NORMAL_TABLE'
        ORDER BY table_name
    """.trimIndent()

    fun listColumns(database: String, table: String) = """
        SELECT table_name, table_type, col_name, col_type,
               col_length, col_precision, col_scale, col_nullable
        FROM information_schema.ins_columns
        WHERE db_name = ${stringLiteral(database)} AND table_name = ${stringLiteral(table)}
    """.trimIndent()

    fun listTagDefinitions(database: String, stable: String) = """
        SELECT DISTINCT tag_name, tag_type
        FROM information_schema.ins_tags
        WHERE db_name = ${stringLiteral(database)} AND stable_name = ${stringLiteral(stable)}
        ORDER BY tag_name
    """.trimIndent()

    fun listTagValues(database: String, table: String) = """
        SELECT tag_name, tag_type, tag_value
        FROM information_schema.ins_tags
        WHERE db_name = ${stringLiteral(database)} AND table_name = ${stringLiteral(table)}
        ORDER BY tag_name
    """.trimIndent()

    val catalogQueries: List<String> = listOf(
        listDatabases,
        listStables("database"),
        listBasicTables("database"),
        listColumns("database", "table"),
        listTagDefinitions("database", "stable"),
        listTagValues("database", "table")
    )

    fun listChildTables(
        database: String,
        stable: String,
        search: String?,
        offset: Int,
        limit: Int
    ): String = buildString {
        append(
            """
            SELECT table_name, stable_name, create_time, table_comment
            FROM information_schema.ins_tables
            WHERE db_name = ${stringLiteral(database)}
              AND stable_name = ${stringLiteral(stable)}
              AND type = 'CHILD_TABLE'
            """.trimIndent()
        )
        if (search != null) append(" AND table_name LIKE ${stringLiteral("%$search%")}")
        append(" ORDER BY table_name LIMIT ${limit + 1} OFFSET $offset")
    }

    fun listTagsForTables(database: String, stable: String, tables: List<String>): String {
        require(tables.isNotEmpty()) { "tables must not be empty" }
        val tableNames = tables.joinToString(", ") { stringLiteral(it) }
        return """
            SELECT table_name, tag_name, tag_type, tag_value
            FROM information_schema.ins_tags
            WHERE db_name = ${stringLiteral(database)}
              AND stable_name = ${stringLiteral(stable)}
              AND table_name IN ($tableNames)
            ORDER BY table_name, tag_name
        """.trimIndent()
    }

    fun stringLiteral(value: String): String = "'${value.replace("'", "''")}'"
}

class TdengineMetadataAdapter : MetadataAdapter, TimeSeriesMetadataAdapter {
    private val dialect = TdengineDialectAdapter()

    override fun listDatabases(session: DatabaseSession): List<DatabaseInfo> =
        session.getJdbcConnection().createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.listDatabases).use { result ->
                buildList {
                    while (result.next()) add(DatabaseInfo(name = result.getString("name")))
                }
            }
        }

    override fun listTables(session: DatabaseSession, database: String): List<TableInfo> {
        val connection = session.getJdbcConnection()
        val stables = connection.createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.listStables(database)).use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            TableInfo(
                                name = result.getString("stable_name"),
                                schema = result.getString("db_name"),
                                type = "stable",
                                comment = result.getString("table_comment")?.takeIf { it.isNotBlank() },
                                updateTime = result.getString("create_time"),
                                engine = "TDengine",
                                tableKind = TableKind.SUPER_TABLE
                            )
                        )
                    }
                }
            }
        }
        val basicTables = connection.createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.listBasicTables(database)).use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            TableInfo(
                                name = result.getString("table_name"),
                                schema = result.getString("db_name"),
                                type = "table",
                                comment = result.getString("table_comment")?.takeIf { it.isNotBlank() },
                                updateTime = result.getString("create_time"),
                                engine = "TDengine",
                                tableKind = TableKind.BASIC_TABLE
                            )
                        )
                    }
                }
            }
        }
        return (stables + basicTables).sortedBy { it.name }
    }

    override fun getTableDefinition(
        session: DatabaseSession,
        database: String,
        table: String
    ): TableDefinition {
        val ddl = getDdl(session, database, table)
        return TableDefinition(
            table = getTableInfo(session, database, table),
            columns = getColumns(session, database, table),
            indexes = emptyList(),
            ddl = ddl,
            ddlSource = "native"
        )
    }

    override fun getTableDesign(
        session: DatabaseSession,
        database: String,
        table: String
    ): TableDefinition = TableDefinition(
        table = getTableInfo(session, database, table),
        columns = getColumns(session, database, table),
        indexes = emptyList()
    )

    override fun getTableInfo(session: DatabaseSession, database: String, table: String): TableInfo {
        val connection = session.getJdbcConnection()
        findStable(connection, database, table)?.let { return it }
        findTable(connection, database, table)?.let { return it }
        throw IllegalArgumentException("TDengine 对象不存在：$database.$table")
    }

    override fun getColumns(
        session: DatabaseSession,
        database: String,
        table: String
    ): List<ColumnInfo> {
        val connection = session.getJdbcConnection()
        return connection.createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.listColumns(database, table)).use { result ->
                buildList {
                    var columnIndex = 0
                    while (result.next()) {
                        val type = formatType(result)
                        add(
                            ColumnInfo(
                                name = result.getString("col_name"),
                                type = type,
                                nullable = result.getNullableInt("col_nullable") != 0,
                                isPrimaryKey = columnIndex == 0 && type.equals("TIMESTAMP", ignoreCase = true)
                            )
                        )
                        columnIndex += 1
                    }
                }
            }
        }
    }

    override fun getIndexes(
        session: DatabaseSession,
        database: String,
        table: String
    ): List<IndexInfo> = emptyList()

    override fun previewRows(
        session: DatabaseSession,
        database: String,
        table: String,
        limit: Int,
        where: String?,
        orderBy: String?,
        offset: Int
    ): List<Map<String, String?>> {
        val safeLimit = limit.coerceIn(1, TdengineMetadataSql.MAX_PREVIEW_ROWS)
        val safeOffset = offset.coerceAtLeast(0)
        val safeWhere = validateReadOnlyClause(where, "where")
        val safeOrderBy = validateReadOnlyClause(orderBy, "orderBy")
        val sql = buildString {
            append("SELECT * FROM ")
            append(qualified(database, table))
            if (safeWhere != null) append(" WHERE $safeWhere")
            if (safeOrderBy != null) append(" ORDER BY $safeOrderBy")
            append(" LIMIT $safeLimit OFFSET $safeOffset")
        }

        return session.getJdbcConnection().createStatement().use { statement ->
            statement.executeQuery(sql).use { result -> result.readRows() }
        }
    }

    override fun getDdl(session: DatabaseSession, database: String, table: String): String {
        val tableInfo = getTableInfo(session, database, table)
        val command = if (tableInfo.tableKind == TableKind.SUPER_TABLE) {
            "SHOW CREATE STABLE ${qualified(database, table)}"
        } else {
            "SHOW CREATE TABLE ${qualified(database, table)}"
        }
        return session.getJdbcConnection().createStatement().use { statement ->
            statement.executeQuery(command).use { result ->
                if (result.next()) result.getString(2) else ""
            }
        }
    }

    override fun listChildTables(
        session: DatabaseSession,
        database: String,
        stable: String,
        offset: Int,
        limit: Int,
        search: String?
    ): TimeSeriesChildTablePage {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, TimeSeriesMetadataLimits.MAX_CHILD_TABLE_PAGE_SIZE)
        val normalizedSearch = search?.trim()?.takeIf { it.isNotEmpty() }
        val connection = session.getJdbcConnection()
        val rows = connection.createStatement().use { statement ->
            statement.executeQuery(
                TdengineMetadataSql.listChildTables(
                    database = database,
                    stable = stable,
                    search = normalizedSearch,
                    offset = safeOffset,
                    limit = safeLimit
                )
            ).use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            TimeSeriesChildTable(
                                name = result.getString("table_name"),
                                database = database,
                                stableName = result.getString("stable_name"),
                                createdAt = result.getString("create_time"),
                                comment = result.getString("table_comment")?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            }
        }
        val hasMore = rows.size > safeLimit
        val pageRows = rows.take(safeLimit)
        val tagsByTable = loadTagsForTables(connection, database, stable, pageRows.map { it.name })
        return TimeSeriesChildTablePage(
            items = pageRows.map { child ->
                child.copy(tagValues = tagsByTable[child.name].orEmpty())
            },
            offset = safeOffset,
            limit = safeLimit,
            hasMore = hasMore
        )
    }

    override fun listTagDefinitions(
        session: DatabaseSession,
        database: String,
        stable: String
    ): List<TimeSeriesTagDefinition> {
        val connection = session.getJdbcConnection()
        val fromCatalog = connection.createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.listTagDefinitions(database, stable)).use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            TimeSeriesTagDefinition(
                                name = result.getString("tag_name"),
                                type = result.getString("tag_type")
                            )
                        )
                    }
                }
            }
        }
        if (fromCatalog.isNotEmpty()) return fromCatalog

        return connection.createStatement().use { statement ->
            statement.executeQuery("DESCRIBE ${qualified(database, stable)}").use { result ->
                buildList {
                    while (result.next()) {
                        if (!result.getString("note").equals("TAG", ignoreCase = true)) continue
                        val baseType = result.getString("type")
                        val length = result.getInt("length")
                        val type = if (
                            baseType.uppercase() in setOf("VARCHAR", "NCHAR", "BINARY", "VARBINARY", "GEOMETRY")
                        ) {
                            "$baseType($length)"
                        } else {
                            baseType
                        }
                        add(TimeSeriesTagDefinition(name = result.getString("field"), type = type))
                    }
                }
            }
        }
    }

    override fun listTagValues(
        session: DatabaseSession,
        database: String,
        table: String
    ): List<TimeSeriesTagValue> =
        session.getJdbcConnection().createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.listTagValues(database, table)).use { result ->
                result.readTagValues()
            }
        }

    override fun createDatabase(
        session: DatabaseSession,
        name: String,
        charset: String,
        collation: String
    ): Unit = unsupportedMutation()

    override fun dropDatabase(session: DatabaseSession, name: String): Unit = unsupportedMutation()

    override fun renameTable(
        session: DatabaseSession,
        database: String,
        oldName: String,
        newName: String
    ): Unit = unsupportedMutation()

    private fun findStable(connection: Connection, database: String, table: String): TableInfo? {
        val sql = """
            SELECT stable_name, db_name, create_time, table_comment
            FROM information_schema.ins_stables
            WHERE db_name = ${TdengineMetadataSql.stringLiteral(database)}
              AND stable_name = ${TdengineMetadataSql.stringLiteral(table)}
        """.trimIndent()
        return connection.createStatement().use { statement ->
            statement.executeQuery(sql).use resultUse@ { result ->
                if (!result.next()) return@resultUse null
                TableInfo(
                    name = result.getString("stable_name"),
                    schema = result.getString("db_name"),
                    type = "stable",
                    comment = result.getString("table_comment")?.takeIf { it.isNotBlank() },
                    updateTime = result.getString("create_time"),
                    engine = "TDengine",
                    tableKind = TableKind.SUPER_TABLE
                )
            }
        }
    }

    private fun findTable(connection: Connection, database: String, table: String): TableInfo? {
        val sql = """
            SELECT table_name, db_name, stable_name, create_time, table_comment, type
            FROM information_schema.ins_tables
            WHERE db_name = ${TdengineMetadataSql.stringLiteral(database)}
              AND table_name = ${TdengineMetadataSql.stringLiteral(table)}
        """.trimIndent()
        return connection.createStatement().use { statement ->
            statement.executeQuery(sql).use resultUse@ { result ->
                if (!result.next()) return@resultUse null
                val kind = when (result.getString("type")) {
                    "CHILD_TABLE" -> TableKind.CHILD_TABLE
                    else -> TableKind.BASIC_TABLE
                }
                TableInfo(
                    name = result.getString("table_name"),
                    schema = result.getString("db_name"),
                    type = "table",
                    comment = result.getString("table_comment")?.takeIf { it.isNotBlank() },
                    updateTime = result.getString("create_time"),
                    engine = "TDengine",
                    tableKind = kind
                )
            }
        }
    }

    private fun loadTagsForTables(
        connection: Connection,
        database: String,
        stable: String,
        tables: List<String>
    ): Map<String, List<TimeSeriesTagValue>> {
        if (tables.isEmpty()) return emptyMap()
        return connection.createStatement().use { statement ->
            statement.executeQuery(
                TdengineMetadataSql.listTagsForTables(database, stable, tables)
            ).use { result ->
                buildMap<String, MutableList<TimeSeriesTagValue>> {
                    while (result.next()) {
                        getOrPut(result.getString("table_name")) { mutableListOf() }
                            .add(result.readTagValue())
                    }
                }
            }
        }
    }

    private fun formatType(result: ResultSet): String {
        val base = result.getString("col_type")
        val length = result.getNullableInt("col_length")
        val precision = result.getNullableInt("col_precision")
        val scale = result.getNullableInt("col_scale")
        return when {
            base.contains('(') -> base
            base.uppercase() in setOf("VARCHAR", "NCHAR", "BINARY", "VARBINARY", "GEOMETRY") && length != null ->
                "$base($length)"
            base.equals("DECIMAL", ignoreCase = true) && precision != null && scale != null ->
                "$base($precision,$scale)"
            base.equals("DECIMAL", ignoreCase = true) && precision != null -> "$base($precision)"
            else -> base
        }
    }

    private fun validateReadOnlyClause(value: String?, label: String): String? {
        val clause = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(!clause.contains(';') && !clause.contains("--") && !clause.contains("/*")) {
            "TDengine $label 不允许多语句或 SQL 注释"
        }
        val forbidden = Regex(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|USE)\\b"
        )
        require(!forbidden.containsMatchIn(clause)) { "TDengine $label 仅允许只读表达式" }
        return clause
    }

    private fun qualified(database: String, table: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"

    private fun ResultSet.readRows(): List<Map<String, String?>> {
        val metadata = metaData
        return buildList {
            while (next()) {
                add(
                    buildMap {
                        for (index in 1..metadata.columnCount) {
                            put(metadata.getColumnLabel(index), getString(index))
                        }
                    }
                )
            }
        }
    }

    private fun ResultSet.readTagValues(): List<TimeSeriesTagValue> = buildList {
        while (next()) add(readTagValue())
    }

    private fun ResultSet.readTagValue(): TimeSeriesTagValue = TimeSeriesTagValue(
        name = getString("tag_name"),
        type = getString("tag_type"),
        value = getString("tag_value")
    )

    private fun ResultSet.getNullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private fun unsupportedMutation(): Nothing =
        throw UnsupportedOperationException("TDengine 当前阶段仅支持时序元数据浏览和只读预览")
}
