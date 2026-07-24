package com.easydb.drivers.tdengine

import com.easydb.common.ColumnInfo
import com.easydb.common.DatabaseInfo
import com.easydb.common.DatabaseSession
import com.easydb.common.IndexInfo
import com.easydb.common.InvalidReadOnlyClauseException
import com.easydb.common.MetadataAdapter
import com.easydb.common.MetadataPage
import com.easydb.common.MetadataPageRequest
import com.easydb.common.TableDefinition
import com.easydb.common.TableInfo
import com.easydb.common.TableKind
import com.easydb.common.TimeSeriesChildTable
import com.easydb.common.TimeSeriesChildTablePage
import com.easydb.common.TimeSeriesChildTableQuery
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesMetadataLimits
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagFilter
import com.easydb.common.TimeSeriesTagFilterOperator
import com.easydb.common.TimeSeriesTagValue
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp

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

    fun countStables(database: String, search: String?): String = """
        SELECT COUNT(*) AS total
        FROM information_schema.ins_stables
        WHERE db_name = ${stringLiteral(database)}${nameFilter("stable_name", search)}
    """.trimIndent()

    fun listStablesPage(database: String, search: String?, offset: Int, limit: Int): String = """
        SELECT stable_name, db_name, create_time, table_comment
        FROM information_schema.ins_stables
        WHERE db_name = ${stringLiteral(database)}${nameFilter("stable_name", search)}
        ORDER BY stable_name
        LIMIT $limit OFFSET $offset
    """.trimIndent()

    fun countBasicTables(database: String, search: String?): String = """
        SELECT COUNT(*) AS total
        FROM information_schema.ins_tables
        WHERE db_name = ${stringLiteral(database)} AND type = 'NORMAL_TABLE'${nameFilter("table_name", search)}
    """.trimIndent()

    fun listBasicTablesPage(database: String, search: String?, offset: Int, limit: Int): String = """
        SELECT table_name, db_name, create_time, table_comment
        FROM information_schema.ins_tables
        WHERE db_name = ${stringLiteral(database)} AND type = 'NORMAL_TABLE'${nameFilter("table_name", search)}
        ORDER BY table_name
        LIMIT $limit OFFSET $offset
    """.trimIndent()

    private fun nameFilter(column: String, search: String?): String =
        search?.let { " AND $column LIKE ${likeContainsLiteral(it)}" }.orEmpty()

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
            SELECT table_name, stable_name, create_time, table_comment, `ttl` AS ttl_value
            FROM information_schema.ins_tables
            WHERE db_name = ${stringLiteral(database)}
              AND stable_name = ${stringLiteral(stable)}
              AND type = 'CHILD_TABLE'
            """.trimIndent()
        )
        if (search != null) append(" AND table_name LIKE ${likeContainsLiteral(search)}")
        append(" ORDER BY table_name LIMIT ${limit + 1} OFFSET $offset")
    }

    fun listChildTablesByName(database: String, stable: String, tables: List<String>): String {
        require(tables.isNotEmpty()) { "tables must not be empty" }
        val tableNames = tables.joinToString(", ") { stringLiteral(it) }
        return """
            SELECT table_name, stable_name, create_time, table_comment, `ttl` AS ttl_value
            FROM information_schema.ins_tables
            WHERE db_name = ${stringLiteral(database)}
              AND stable_name = ${stringLiteral(stable)}
              AND type = 'CHILD_TABLE'
              AND table_name IN ($tableNames)
            ORDER BY table_name
        """.trimIndent()
    }

    fun findChildTable(database: String, table: String): String = """
        SELECT table_name, stable_name, create_time, table_comment, `ttl` AS ttl_value
        FROM information_schema.ins_tables
        WHERE db_name = ${stringLiteral(database)}
          AND table_name = ${stringLiteral(table)}
          AND type = 'CHILD_TABLE'
    """.trimIndent()

    fun countChildTables(database: String, stable: String): String = """
        SELECT COUNT(*) AS total
        FROM information_schema.ins_tables
        WHERE db_name = ${stringLiteral(database)}
          AND stable_name = ${stringLiteral(stable)}
          AND type = 'CHILD_TABLE'
    """.trimIndent()

    fun listTagFilterCandidates(
        database: String,
        stable: String,
        tagNames: List<String>,
        search: String?
    ): String = buildString {
        require(tagNames.isNotEmpty()) { "tagNames must not be empty" }
        append(
            """
            SELECT table_name, tag_name, tag_type, tag_value
            FROM information_schema.ins_tags
            WHERE db_name = ${stringLiteral(database)}
              AND stable_name = ${stringLiteral(stable)}
              AND tag_name IN (${tagNames.joinToString(", ") { stringLiteral(it) }})
            """.trimIndent()
        )
        if (search != null) append(" AND table_name LIKE ${likeContainsLiteral(search)}")
        append(" ORDER BY table_name, tag_name")
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

    fun likeContainsLiteral(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return stringLiteral("%$escaped%")
    }
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

    override fun listTablesPage(
        session: DatabaseSession,
        database: String,
        request: MetadataPageRequest
    ): MetadataPage<TableInfo> {
        val search = request.search?.trim()?.takeIf { it.isNotEmpty() }
        val type = normalizePagedObjectType(request.type)
        if (request.type?.isNotBlank() == true && type == null) {
            return MetadataPage(
                items = emptyList(),
                total = 0,
                offset = request.offset,
                limit = request.limit,
                hasMore = false
            )
        }

        val connection = session.getJdbcConnection()
        val stableTotal = if (type == null || type == "stable") {
            count(connection, TdengineMetadataSql.countStables(database, search))
        } else 0L
        val tableTotal = if (type == null || type == "table") {
            count(connection, TdengineMetadataSql.countBasicTables(database, search))
        } else 0L
        val total = stableTotal + tableTotal
        val items = buildList {
            if ((type == null || type == "stable") && request.offset.toLong() < stableTotal) {
                val stableLimit = minOf(request.limit.toLong(), stableTotal - request.offset).toInt()
                addAll(
                    listObjectPage(
                        connection,
                        TdengineMetadataSql.listStablesPage(database, search, request.offset, stableLimit),
                        nameColumn = "stable_name",
                        type = "stable",
                        tableKind = TableKind.SUPER_TABLE
                    )
                )
            }
            val remaining = request.limit - size
            if (remaining > 0 && (type == null || type == "table")) {
                val tableOffset = if (type == "table") {
                    request.offset
                } else {
                    (request.offset.toLong() - stableTotal).coerceAtLeast(0).toInt()
                }
                if (tableOffset.toLong() < tableTotal) {
                    addAll(
                        listObjectPage(
                            connection,
                            TdengineMetadataSql.listBasicTablesPage(database, search, tableOffset, remaining),
                            nameColumn = "table_name",
                            type = "table",
                            tableKind = TableKind.BASIC_TABLE
                        )
                    )
                }
            }
        }
        return MetadataPage(
            items = items,
            total = total,
            offset = request.offset,
            limit = request.limit,
            hasMore = request.offset.toLong() + items.size < total
        )
    }

    private fun normalizePagedObjectType(type: String?): String? = when (type?.trim()?.lowercase()) {
        null, "" -> null
        "stable", "super_table" -> "stable"
        "table", "basic_table" -> "table"
        else -> null
    }

    private fun count(connection: Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                if (result.next()) result.getLong("total") else 0L
            }
        }

    private fun listObjectPage(
        connection: Connection,
        sql: String,
        nameColumn: String,
        type: String,
        tableKind: TableKind
    ): List<TableInfo> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            buildList {
                while (result.next()) {
                    add(
                        TableInfo(
                            name = result.getString(nameColumn),
                            schema = result.getString("db_name"),
                            type = type,
                            comment = result.getString("table_comment")?.takeIf { it.isNotBlank() },
                            updateTime = result.getString("create_time"),
                            engine = "TDengine",
                            tableKind = tableKind
                        )
                    )
                }
            }
        }
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
        val safeWhere = validateTdengineReadOnlyClause(where, "where")
        val safeOrderBy = validateTdengineReadOnlyClause(orderBy, "orderBy")
        val sql = buildString {
            append("SELECT * FROM ")
            append(qualified(database, table))
            if (safeWhere != null) append(" WHERE $safeWhere")
            if (safeOrderBy != null) append(" ORDER BY $safeOrderBy")
            append(" LIMIT $safeLimit OFFSET $safeOffset")
        }

        return session.getJdbcConnection().createStatement().use { statement ->
            statement.executeQuery(sql).use { result -> result.readTdengineStringRows() }
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
    ): TimeSeriesChildTablePage = queryChildTables(
        session,
        database,
        stable,
        TimeSeriesChildTableQuery(offset = offset, limit = limit, search = search)
    )

    override fun queryChildTables(
        session: DatabaseSession,
        database: String,
        stable: String,
        query: TimeSeriesChildTableQuery
    ): TimeSeriesChildTablePage {
        val safeOffset = query.offset
        val safeLimit = query.limit
        val normalizedSearch = query.search?.trim()?.takeIf { it.isNotEmpty() }
        val connection = session.getJdbcConnection()
        if (query.filters.isNotEmpty()) {
            return queryFilteredChildTables(
                connection,
                session,
                database,
                stable,
                query.copy(search = normalizedSearch)
            )
        }
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
                        add(result.readChildTable(database))
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

    override fun inspectChildTable(
        session: DatabaseSession,
        database: String,
        table: String
    ): TimeSeriesChildTable {
        val connection = session.getJdbcConnection()
        val child = connection.createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.findChildTable(database, table)).use { result ->
                if (result.next()) result.readChildTable(database, preserveBlankComment = true) else null
            }
        } ?: throw IllegalArgumentException("TDengine 子表不存在：$database.$table")
        val definitions = listTagDefinitions(session, database, child.stableName).associateBy { it.name }
        val values = listTagValues(session, database, table).map { value ->
            value.copy(type = definitions[value.name]?.type ?: value.type)
        }
        return child.copy(tagValues = values)
    }

    override fun countChildTables(
        session: DatabaseSession,
        database: String,
        stable: String
    ): Long = count(session.getJdbcConnection(), TdengineMetadataSql.countChildTables(database, stable))

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

    private fun queryFilteredChildTables(
        connection: Connection,
        session: DatabaseSession,
        database: String,
        stable: String,
        query: TimeSeriesChildTableQuery
    ): TimeSeriesChildTablePage {
        val definitions = listTagDefinitions(session, database, stable).associateBy { it.name }
        val compiled = query.filters.map { filter -> compileFilter(filter, definitions[filter.name]) }
        require(compiled.map { it.name }.distinct().size == compiled.size) { "同一个 Tag 只能设置一个筛选条件" }
        val valuesByTable = connection.createStatement().use { statement ->
            statement.executeQuery(
                TdengineMetadataSql.listTagFilterCandidates(
                    database,
                    stable,
                    compiled.map { it.name },
                    query.search
                )
            ).use { result ->
                buildMap<String, MutableMap<String, String?>> {
                    while (result.next()) {
                        getOrPut(result.getString("table_name")) { mutableMapOf() }[
                            result.getString("tag_name")
                        ] = result.getString("tag_value")
                    }
                }
            }
        }
        val matchingNames = valuesByTable.entries
            .asSequence()
            .filter { (_, values) -> compiled.all { it.matches(values[it.name]) } }
            .map { it.key }
            .sorted()
            .toList()
        val pageNames = matchingNames.drop(query.offset).take(query.limit + 1)
        val hasMore = pageNames.size > query.limit
        val visibleNames = pageNames.take(query.limit)
        if (visibleNames.isEmpty()) {
            return TimeSeriesChildTablePage(emptyList(), query.offset, query.limit, hasMore = false)
        }
        val childrenByName = connection.createStatement().use { statement ->
            statement.executeQuery(TdengineMetadataSql.listChildTablesByName(database, stable, visibleNames)).use { result ->
                buildMap {
                    while (result.next()) {
                        val child = result.readChildTable(database)
                        put(child.name, child)
                    }
                }
            }
        }
        val tagsByTable = loadTagsForTables(connection, database, stable, visibleNames)
        return TimeSeriesChildTablePage(
            items = visibleNames.mapNotNull { name ->
                childrenByName[name]?.copy(tagValues = tagsByTable[name].orEmpty())
            },
            offset = query.offset,
            limit = query.limit,
            hasMore = hasMore
        )
    }

    private fun compileFilter(
        filter: TimeSeriesTagFilter,
        definition: TimeSeriesTagDefinition?
    ): CompiledTagFilter {
        require(filter.name.isNotBlank()) { "Tag 名不能为空" }
        val tag = definition ?: throw IllegalArgumentException("Tag 不存在：${filter.name}")
        val family = TagTypeFamily.from(tag.type)
        val allowed = family.allowedOperators
        require(filter.operator in allowed) { "Tag ${filter.name} 的类型 ${tag.type} 不支持 ${filter.operator}" }
        val nullOperator = filter.operator == TimeSeriesTagFilterOperator.IS_NULL ||
            filter.operator == TimeSeriesTagFilterOperator.IS_NOT_NULL
        if (nullOperator) {
            require(filter.value == null) { "${filter.operator} 不能提供 value" }
            return CompiledTagFilter(filter.name, filter.operator, family, null)
        }
        val value = filter.value ?: throw IllegalArgumentException("${filter.operator} 必须提供 value")
        require(value.length <= MAX_TAG_FILTER_VALUE_CHARS) { "Tag ${filter.name} 的筛选值过长" }
        family.validate(value, "Tag ${filter.name}")
        return CompiledTagFilter(filter.name, filter.operator, family, value)
    }

    private fun ResultSet.readChildTable(
        database: String,
        preserveBlankComment: Boolean = false
    ): TimeSeriesChildTable {
        val rawComment = getString("table_comment")
        return TimeSeriesChildTable(
            name = getString("table_name"),
            database = database,
            stableName = getString("stable_name"),
            createdAt = getString("create_time"),
            comment = if (preserveBlankComment) rawComment else rawComment?.takeIf { it.isNotBlank() },
            ttl = getNullableInt("ttl_value") ?: 0
        )
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

    private fun qualified(database: String, table: String): String =
        "${dialect.quoteIdentifier(database)}.${dialect.quoteIdentifier(table)}"

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

    private companion object {
        const val MAX_TAG_FILTER_VALUE_CHARS = 65_536
    }
}

private data class CompiledTagFilter(
    val name: String,
    val operator: TimeSeriesTagFilterOperator,
    val family: TagTypeFamily,
    val expected: String?
) {
    fun matches(actual: String?): Boolean {
        return when (operator) {
            TimeSeriesTagFilterOperator.IS_NULL -> actual == null
            TimeSeriesTagFilterOperator.IS_NOT_NULL -> actual != null
            else -> {
                if (actual == null) return false
                if (operator == TimeSeriesTagFilterOperator.CONTAINS) {
                    return actual.contains(requireNotNull(expected))
                }
                val comparison = family.compare(actual, requireNotNull(expected)) ?: return false
                when (operator) {
                    TimeSeriesTagFilterOperator.EQ -> comparison == 0
                    TimeSeriesTagFilterOperator.NE -> comparison != 0
                    TimeSeriesTagFilterOperator.GT -> comparison > 0
                    TimeSeriesTagFilterOperator.GTE -> comparison >= 0
                    TimeSeriesTagFilterOperator.LT -> comparison < 0
                    TimeSeriesTagFilterOperator.LTE -> comparison <= 0
                    TimeSeriesTagFilterOperator.CONTAINS,
                    TimeSeriesTagFilterOperator.IS_NULL,
                    TimeSeriesTagFilterOperator.IS_NOT_NULL -> false
                }
            }
        }
    }
}

private enum class TagTypeFamily(
    val allowedOperators: Set<TimeSeriesTagFilterOperator>
) {
    TEXT(
        setOf(
            TimeSeriesTagFilterOperator.EQ,
            TimeSeriesTagFilterOperator.NE,
            TimeSeriesTagFilterOperator.CONTAINS,
            TimeSeriesTagFilterOperator.IS_NULL,
            TimeSeriesTagFilterOperator.IS_NOT_NULL
        )
    ),
    NUMBER(comparableTagOperators()),
    TIMESTAMP(comparableTagOperators()),
    BOOL(
        setOf(
            TimeSeriesTagFilterOperator.EQ,
            TimeSeriesTagFilterOperator.NE,
            TimeSeriesTagFilterOperator.IS_NULL,
            TimeSeriesTagFilterOperator.IS_NOT_NULL
        )
    );

    fun validate(value: String, label: String) {
        when (this) {
            TEXT -> Unit
            NUMBER -> require(value.trim().toBigDecimalOrNull() != null) { "$label 的筛选值必须是数值" }
            BOOL -> require(value.trim().lowercase() in setOf("true", "false")) {
                "$label 的筛选值必须是 true 或 false"
            }
            TIMESTAMP -> require(parseTimestamp(value) != null) {
                "$label 的筛选值必须是时间文本（YYYY-MM-DD HH:mm:ss[.fraction]）或 epoch 整数"
            }
        }
    }

    fun compare(actual: String, expected: String): Int? = when (this) {
        TEXT -> actual.compareTo(expected)
        NUMBER -> compareValues(actual.trim().toBigDecimalOrNull(), expected.trim().toBigDecimalOrNull())
        BOOL -> compareValues(parseBoolean(actual), parseBoolean(expected))
        TIMESTAMP -> compareValues(parseTimestamp(actual), parseTimestamp(expected))
    }

    companion object {
        fun from(rawType: String): TagTypeFamily {
            val type = rawType.trim().uppercase().substringBefore('(').replace(Regex("\\s+"), " ")
            return when (type) {
                "BINARY", "VARCHAR", "NCHAR" -> TEXT
                "TINYINT", "TINYINT UNSIGNED", "SMALLINT", "SMALLINT UNSIGNED",
                "INT", "INT UNSIGNED", "BIGINT", "BIGINT UNSIGNED", "FLOAT", "DOUBLE" -> NUMBER
                "TIMESTAMP" -> TIMESTAMP
                "BOOL", "BOOLEAN" -> BOOL
                else -> throw IllegalArgumentException("暂不支持按 Tag 类型 $rawType 筛选")
            }
        }

        private fun parseBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

        private fun parseTimestamp(value: String): ComparableTimestamp? {
            val normalized = value.trim()
            normalized.toBigDecimalOrNull()?.let { return ComparableTimestamp.Epoch(it) }
            return runCatching {
                ComparableTimestamp.DateTime(Timestamp.valueOf(normalized.replace('T', ' ')).toLocalDateTime())
            }.getOrNull()
        }

        private fun <T : Comparable<T>> compareValues(left: T?, right: T?): Int? =
            if (left == null || right == null) null else left.compareTo(right)

    }
}

private fun comparableTagOperators(): Set<TimeSeriesTagFilterOperator> = setOf(
    TimeSeriesTagFilterOperator.EQ,
    TimeSeriesTagFilterOperator.NE,
    TimeSeriesTagFilterOperator.GT,
    TimeSeriesTagFilterOperator.GTE,
    TimeSeriesTagFilterOperator.LT,
    TimeSeriesTagFilterOperator.LTE,
    TimeSeriesTagFilterOperator.IS_NULL,
    TimeSeriesTagFilterOperator.IS_NOT_NULL
)

private sealed interface ComparableTimestamp : Comparable<ComparableTimestamp> {
    data class Epoch(val value: BigDecimal) : ComparableTimestamp
    data class DateTime(val value: java.time.LocalDateTime) : ComparableTimestamp

    override fun compareTo(other: ComparableTimestamp): Int = when {
        this is Epoch && other is Epoch -> value.compareTo(other.value)
        this is DateTime && other is DateTime -> value.compareTo(other.value)
        else -> throw IllegalArgumentException("时间筛选值与目录值的表示方式不一致")
    }
}

internal fun validateTdengineReadOnlyClause(value: String?, label: String): String? {
    val clause = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (clause.contains(';') || clause.contains("--") || clause.contains("/*")) {
        throw InvalidReadOnlyClauseException("TDengine $label 不允许多语句或 SQL 注释")
    }
    val forbidden = Regex(
        "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|USE)\\b"
    )
    if (forbidden.containsMatchIn(clause)) {
        throw InvalidReadOnlyClauseException("TDengine $label 仅允许只读表达式")
    }
    return clause
}

internal fun ResultSet.readTdengineStringRows(): List<Map<String, String?>> {
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
