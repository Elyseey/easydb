/*
 * Copyright (c) 2024-2026 EasyDB Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.easydb.common

import kotlinx.serialization.Serializable

/**
 * 数据库适配器接口 - 支持多数据库扩展的核心抽象
 * 首版仅实现 MySQL，架构上预留扩展基础
 */
interface DatabaseAdapter {
    fun dbType(): DbType
    fun capabilities(): DatabaseCapabilities
    fun connectionAdapter(): ConnectionAdapter
    fun metadataAdapter(): MetadataAdapter
    fun dialectAdapter(): DialectAdapter
    fun syncAdapter(): SyncAdapter
    fun migrationAdapter(): MigrationAdapter
    fun procedureAdapter(): ProcedureAdapter  // 存储过程/函数适配器

    /** 时序目录能力是可选的；关系型驱动无需提供空实现。 */
    fun timeSeriesMetadataAdapter(): TimeSeriesMetadataAdapter? = null

    /** 逻辑备份读取一致性由具体数据库驱动实现；不支持时返回 null。 */
    fun logicalBackupAdapter(): LogicalBackupAdapter? = null

    /**
     * 慢查询分析适配器（可选）。
     * MySQL 返回 [MysqlSlowQueryAnalyzer] 实例，其他数据库先返回 null。
     * 调用方应先检查是否为 null 再决定是否展示功能入口。
     */
    fun slowQueryAnalyzer(): SlowQueryAnalyzer? = null
}

/**
 * 为专用备份连接建立并释放数据库特定的一致读取上下文。
 * 数据库专属事务 SQL 必须封装在驱动实现中，不能泄漏到通用备份服务。
 */
interface LogicalBackupAdapter {
    fun begin(connection: java.sql.Connection): LogicalBackupContext
    fun finish(connection: java.sql.Connection)

    /** 配置驱动的流式结果集读取方式，避免通用服务识别具体数据库产品名。 */
    fun configureStreamingStatement(statement: java.sql.Statement) {
        statement.fetchSize = 1000
    }
}

// ─── 连接适配器 ───────────────────────────────────────────
interface ConnectionAdapter {
    fun testConnection(config: ConnectionConfig): ConnectionTestResult
    fun open(config: ConnectionConfig): DatabaseSession
    fun close(session: DatabaseSession)
}

@Serializable
data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long? = null
)

interface DatabaseSession {
    val connectionId: String
    val config: ConnectionConfig
    fun isValid(): Boolean
    fun close()

    /**
     * 获取底层 JDBC Connection。
     * 替代反射和强制转型，为上层提供统一的连接获取方式。
     */
    fun getJdbcConnection(): java.sql.Connection
}

// ─── 元数据适配器 ─────────────────────────────────────────
interface MetadataAdapter {
    fun listDatabases(session: DatabaseSession): List<DatabaseInfo>
    fun listTables(session: DatabaseSession, database: String): List<TableInfo>
    fun listTriggers(session: DatabaseSession, database: String): List<TriggerInfo> = emptyList()
    fun listRoutines(session: DatabaseSession, database: String): List<RoutineInfo> = emptyList()
    fun getTableDefinition(session: DatabaseSession, database: String, table: String): TableDefinition
    /**
     * Lightweight metadata used by table design/edit views.
     * Implementations must include table info, columns, and indexes without loading DDL.
     */
    fun getTableDesign(session: DatabaseSession, database: String, table: String): TableDefinition
    fun getTableInfo(session: DatabaseSession, database: String, table: String): TableInfo =
        getTableDefinition(session, database, table).table
    fun getColumns(session: DatabaseSession, database: String, table: String): List<ColumnInfo> =
        getTableDefinition(session, database, table).columns
    fun getIndexes(session: DatabaseSession, database: String, table: String): List<IndexInfo>
    fun previewRows(session: DatabaseSession, database: String, table: String, limit: Int = 100, where: String? = null, orderBy: String? = null, offset: Int = 0): List<Map<String, String?>>
    fun getDdl(session: DatabaseSession, database: String, table: String): String
    fun createDatabase(session: DatabaseSession, name: String, charset: String = "utf8mb4", collation: String = "utf8mb4_general_ci")
    fun listCharsets(session: DatabaseSession): List<CharsetInfo> = emptyList()
    fun dropDatabase(session: DatabaseSession, name: String)
    fun renameTable(session: DatabaseSession, database: String, oldName: String, newName: String)

 /**
  * 根据对象类型精确获取 DDL（供结构对比/迁移使用）
  * @param objectType: "table" | "view" | "procedure" | "function" | "trigger"
  */
 fun getObjectDdl(session: DatabaseSession, database: String, name: String, objectType: String): String {
  return getDdl(session, database, name)
 }
}

interface TimeSeriesMetadataAdapter {
    fun listChildTables(
        session: DatabaseSession,
        database: String,
        stable: String,
        offset: Int = 0,
        limit: Int = TimeSeriesMetadataLimits.DEFAULT_CHILD_TABLE_PAGE_SIZE,
        search: String? = null
    ): TimeSeriesChildTablePage

    fun listTagDefinitions(
        session: DatabaseSession,
        database: String,
        stable: String
    ): List<TimeSeriesTagDefinition>

    fun listTagValues(
        session: DatabaseSession,
        database: String,
        table: String
    ): List<TimeSeriesTagValue>
}

// ─── 方言适配器 ───────────────────────────────────────────

/**
 * 分页策略：跨方言公共语法模式（不是某个驱动的私有 SQL）。
 * 标准拼法在此内置，新驱动只需声明即可工作；遇到方言怪癖再 override buildPaginationSql。
 */
enum class PaginationStrategy {
    /** MySQL / PostgreSQL / SQLite / TiDB / OceanBase MySQL / ClickHouse / 人大金仓(PG模式) */
    LIMIT_OFFSET,

    /** SQL Server 2012+ / Oracle 12c+ / 达梦 DM8 / SQL:2008 标准 */
    OFFSET_FETCH,

    /** Oracle 11g / 达梦旧版 / 人大金仓 Oracle 模式 */
    ROWNUM_SUBQUERY,

    /** SQL Server 旧版 / Sybase（前缀型，仅支持取前 N 条，不支持 offset 时退化） */
    TOP_N;

    fun apply(innerSql: String, limit: Int, offset: Int): String = when (this) {
        LIMIT_OFFSET ->
            "SELECT * FROM ($innerSql) _easydb_page LIMIT $limit OFFSET $offset"
        OFFSET_FETCH ->
            "SELECT * FROM ($innerSql) _easydb_page OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY"
        ROWNUM_SUBQUERY ->
            "SELECT * FROM (SELECT _easydb_inner.*, ROWNUM _easydb_rn FROM ($innerSql) _easydb_inner WHERE ROWNUM <= ${limit + offset}) WHERE _easydb_rn > $offset"
        TOP_N ->
            if (offset == 0) "SELECT TOP $limit * FROM ($innerSql) _easydb_page"
            else throw UnsupportedOperationException("TOP_N 策略不支持 offset > 0；该方言应改用 ROWNUM_SUBQUERY 或 OFFSET_FETCH")
    }
}

interface DialectAdapter {
    fun quoteIdentifier(name: String): String
    fun buildCreateTable(table: TableDefinition): String
    fun buildCreateTableStatements(table: TableDefinition): List<String> = listOf(buildCreateTable(table))
    fun buildInsert(tableName: String, columns: List<String>): String

    /** 生成 UPDATE SQL */
    fun buildUpdateSql(tableName: String, setCols: List<String>, whereCols: List<String>): String {
        val setClause = setCols.joinToString(", ") { "${quoteIdentifier(it)} = ?" }
        val whereClause = whereCols.joinToString(" AND ") { "${quoteIdentifier(it)} = ?" }
        return "UPDATE ${quoteIdentifier(tableName)} SET $setClause WHERE $whereClause"
    }

    /** 生成 DELETE SQL */
    fun buildDeleteSql(tableName: String, whereCols: List<String>): String {
        val whereClause = whereCols.joinToString(" AND ") { "${quoteIdentifier(it)} = ?" }
        return "DELETE FROM ${quoteIdentifier(tableName)} WHERE $whereClause"
    }

    /** 生成 INSERT SQL（带值占位符） */
    fun buildInsertSql(tableName: String, columns: List<String>): String {
        val cols = columns.joinToString(", ") { quoteIdentifier(it) }
        val placeholders = columns.joinToString(", ") { "?" }
        return "INSERT INTO ${quoteIdentifier(tableName)} ($cols) VALUES ($placeholders)"
    }

    /** 转义字符串值 */
    fun escapeValue(value: String?): String {
        if (value == null) return "NULL"
        return "'${value.replace("'", "''")}'"
    }

    /** 生成可写入导出 SQL 文件的字符串字面量。 */
    fun formatExportStringLiteral(value: String): String = escapeValue(value)

    /** 生成备份包中描述顶级 namespace 的创建语句。 */
    fun buildCreateNamespaceSql(name: String, charset: String? = null, collation: String? = null): String =
        throw UnsupportedOperationException("当前数据库方言不支持创建逻辑备份 namespace")

    /**
     * 将用户输入的全新 namespace 名称转换成驱动实际创建的名称。
     * 已从 catalog 返回的对象名不得调用此方法。
     */
    fun normalizeNewNamespaceName(name: String): String = name.trim()

    /** 仅替换 DDL 中被方言正确引用的源 namespace，避免裸文本误替换。 */
    fun remapNamespaceInDdl(ddl: String, source: String, target: String): String =
        ddl.replace(quoteIdentifier(source), quoteIdentifier(target))

    /** 恢复前后需要切换的数据库专属约束状态；默认无需处理。 */
    fun beforeLogicalRestore(connection: java.sql.Connection) = Unit
    fun afterLogicalRestore(connection: java.sql.Connection) = Unit

    /**
     * 执行备份包中的一项 DDL。表/视图 DDL 可能包含多个方言专属语句，
     * 具体驱动可在此安全拆分；过程、函数和触发器的内部语句不得由通用层拆分。
     */
    fun executeLogicalRestoreDdl(connection: java.sql.Connection, ddl: String, objectType: String) {
        connection.createStatement().use { it.execute(ddl) }
    }

    /**
     * 生成切换目标数据库/schema 的 SQL。
     *   MySQL → USE `db`
     *   PG    → SET search_path TO "schema"
     *   DM / Oracle-like → ALTER SESSION SET CURRENT_SCHEMA = "schema"
     * 返回 null 表示该数据库不需要切换语句。
     */
    fun buildSwitchDatabaseSql(database: String): String?

    /**
     * 方言分页能力声明：每个方言实现必须显式选择策略。
     * 不提供默认值 → 编译期阻止"新驱动忘选策略导致复制 MySQL 拼法静默失效"。
     */
    val paginationStrategy: PaginationStrategy

    /**
     * 生成分页 SQL：默认走 paginationStrategy.apply()。
     * 仅当方言有怪癖（特殊 alias 引号、子查询包装规则等）时才 override。
     */
    fun buildPaginationSql(sql: String, limit: Int, offset: Int): String =
        paginationStrategy.apply(sql, limit, offset)
}

// ─── 同步适配器 ───────────────────────────────────────────
interface SyncAdapter {
    fun preview(config: SyncConfig, sessions: SessionPair): SyncPreview
    fun execute(config: SyncConfig, sessions: SessionPair, reporter: TaskReporter): TaskResult
}

// ─── 结构对比适配器 ────────────────────────────────────────
interface CompareAdapter {
    fun compare(config: CompareConfig, sessions: SessionPair): CompareResult
}

/**
 * 生成让目标端结构向源端靠齐的 SQL。
 *
 * 比较编排只处理规范化后的元数据；所有目标方言语法必须留在具体数据库驱动中。
 */
interface StructureCompareSqlGenerator {
    fun typesEquivalent(sourceType: String, targetType: String): Boolean

    fun createTableSql(
        sourceDdl: String,
        sourceDatabase: String,
        targetDatabase: String,
        tableName: String
    ): String

    fun dropTableSql(targetDatabase: String, tableName: String): String

    fun alterTableSql(
        targetDatabase: String,
        tableName: String,
        sourceColumns: List<ColumnInfo>,
        columnDiffs: List<ColumnDiff>,
        indexDiffs: List<IndexDiff>,
        options: CompareOptions
    ): String
}

// ─── 迁移适配器 ───────────────────────────────────────────
interface MigrationAdapter {
    fun preview(config: MigrationConfig, sessions: SessionPair): MigrationPreview
    fun execute(config: MigrationConfig, sessions: SessionPair, reporter: TaskReporter): TaskResult
}

// ─── 辅助类型 ─────────────────────────────────────────────
data class SessionPair(
    val source: DatabaseSession,
    val target: DatabaseSession
)

interface TaskReporter {
    fun onProgress(progress: Int, message: String? = null)
    fun onStep(stepName: String, status: TaskStatus, message: String? = null)
    fun onLog(level: String, message: String)
    fun isCancelled(): Boolean
}

@Serializable
data class TaskResult(
    val success: Boolean,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val skippedCount: Int = 0,
    val errorMessage: String? = null,
    val verification: List<TableVerifyResult>? = null,
    val payload: Map<String, String>? = null
)

@Serializable
data class TableVerifyResult(
    val tableName: String,
    val sourceRows: Long,        // 源库行数（information_schema 估算）
    val targetRows: Long,        // 目标库行数（同步过程实际写入数）
    val status: String,          // match | mismatch | failed
    val errorMessage: String? = null
)

@Serializable
data class TaskStartResult(
    val taskId: String
)

// ─── 存储过程 / 函数适配器接口 ──────────────────────────────

/**
 * 存储过程/函数适配器。
 * 每个数据库实现这 4 个方法，执行引擎（ProcedureExecuteService）只做纯 JDBC 标准操作。
 * 扩展方式：新增 PgProcedureAdapter / DmProcedureAdapter 实现此接口即可。
 */
interface ProcedureAdapter {

    /**
     * 查询存储过程或函数的参数元数据。
     * 各数据库系统表不同，必须由各自实现：
     *   MySQL → INFORMATION_SCHEMA.PARAMETERS
     *   PG    → pg_catalog.pg_proc + pg_type
     *   DM    → ALL_ARGUMENTS（兼容 Oracle）
     */
    fun inspect(
        session: DatabaseSession,
        database: String,
        name: String,
        type: String    // "PROCEDURE" | "FUNCTION"
    ): ProcedureInspectResult

    /**
     * 生成 CALL 语句（含 ? 占位符，用于 CallableStatement / PreparedStatement）。
     *   MySQL → CALL `db`.`proc`(?, ?, ?)
     *   PG    → CALL "schema"."proc"($1, $2, $3)
     *   DM    → CALL db.proc(?, ?, ?)
     */
    fun buildCallSql(database: String, name: String, paramCount: Int): String

    /**
     * 生成函数调用 SQL（作为 SELECT 表达式）。
     *   MySQL → SELECT `db`.`func`(?, ?) AS `result`
     *   PG    → SELECT "schema"."func"($1, $2) AS result
     *   DM    → SELECT db.func(?, ?) AS result FROM dual
     */
    fun buildFunctionCallSql(database: String, name: String, paramCount: Int): String

    /**
     * 生成切换目标数据库的 SQL。
     *   MySQL → USE `db`
     *   PG    → SET search_path TO "schema"
     *   DM    → 返回空字符串（连接串中指定）
     */
    fun buildSwitchDatabaseSql(database: String): String
}
