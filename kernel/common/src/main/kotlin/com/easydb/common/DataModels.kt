package com.easydb.common

import kotlinx.serialization.Serializable

// ─── 数据库对象模型 ───────────────────────────────────────
@Serializable
data class DatabaseInfo(
    val name: String,
    val charset: String? = null,
    val collation: String? = null
)

@Serializable
data class CharsetInfo(
    val charset: String,
    val defaultCollation: String,
    val collations: List<String> = emptyList()
)

@Serializable
data class TableInfo(
    val name: String,
    val schema: String? = null,
    val type: String = "table", // table | view | trigger
    val rowCount: Long? = null,
    val comment: String? = null,
    val dataLength: Long? = null,
    val indexLength: Long? = null,
    val updateTime: String? = null,
    val engine: String? = null,
    val tableKind: TableKind? = null
)

object MetadataPageLimits {
    const val DEFAULT_PAGE_SIZE = 100
    const val MAX_PAGE_SIZE = 500
}

@Serializable
data class MetadataPageRequest(
    val search: String? = null,
    val type: String? = null,
    val offset: Int = 0,
    val limit: Int = MetadataPageLimits.DEFAULT_PAGE_SIZE
) {
    init {
        require(offset >= 0) { "offset must be greater than or equal to 0" }
        require(limit in 1..MetadataPageLimits.MAX_PAGE_SIZE) {
            "limit must be between 1 and ${MetadataPageLimits.MAX_PAGE_SIZE}"
        }
    }
}

@Serializable
data class MetadataPage<T>(
    val items: List<T>,
    val total: Long,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

@Serializable
enum class TableKind {
    SUPER_TABLE,
    BASIC_TABLE,
    CHILD_TABLE
}

@Serializable
data class TimeSeriesTagDefinition(
    val name: String,
    val type: String
)

@Serializable
data class TimeSeriesTagValue(
    val name: String,
    val type: String,
    val value: String? = null
)

/** TDengine 等时序数据库的专属对象创建类型。 */
@Serializable
enum class TimeSeriesCreateKind {
    SUPER_TABLE,
    BASIC_TABLE,
    CHILD_TABLE
}

/** 首版可视化建模支持的稳定类型子集。 */
@Serializable
enum class TimeSeriesDataType(val sql: String, val requiresLength: Boolean = false) {
    TIMESTAMP("TIMESTAMP"),
    BOOL("BOOL"),
    TINYINT("TINYINT"),
    TINYINT_UNSIGNED("TINYINT UNSIGNED"),
    SMALLINT("SMALLINT"),
    SMALLINT_UNSIGNED("SMALLINT UNSIGNED"),
    INT("INT"),
    INT_UNSIGNED("INT UNSIGNED"),
    BIGINT("BIGINT"),
    BIGINT_UNSIGNED("BIGINT UNSIGNED"),
    FLOAT("FLOAT"),
    DOUBLE("DOUBLE"),
    BINARY("BINARY", requiresLength = true),
    VARCHAR("VARCHAR", requiresLength = true),
    NCHAR("NCHAR", requiresLength = true)
}

@Serializable
data class TimeSeriesFieldDraft(
    val name: String,
    val type: TimeSeriesDataType,
    val length: Int? = null
)

@Serializable
data class TimeSeriesTagValueDraft(
    val name: String,
    val value: String? = null,
    val isNull: Boolean = false
)

/**
 * 时序对象创建请求。
 *
 * SUPER_TABLE 使用 [columns] + [tags]；BASIC_TABLE 使用 [columns]；
 * CHILD_TABLE 使用 [stableName] + [tagValues]。其他字段必须为空。
 */
@Serializable
data class TimeSeriesCreateDefinition(
    val kind: TimeSeriesCreateKind,
    val name: String,
    val columns: List<TimeSeriesFieldDraft> = emptyList(),
    val tags: List<TimeSeriesFieldDraft> = emptyList(),
    val stableName: String? = null,
    val tagValues: List<TimeSeriesTagValueDraft> = emptyList(),
    val comment: String? = null
)

@Serializable
data class TimeSeriesCreatePreview(
    val ddl: String
)

@Serializable
data class TimeSeriesCreateResult(
    val success: Boolean,
    val ddl: String,
    val kind: TimeSeriesCreateKind,
    val name: String,
    val stableName: String? = null
)

/** 超级表结构每次只允许执行一个原子变更。 */
@Serializable
enum class TimeSeriesLifecycleOperation {
    ADD_COLUMN,
    DROP_COLUMN,
    MODIFY_COLUMN,
    ADD_TAG,
    DROP_TAG,
    MODIFY_TAG,
    RENAME_TAG
}

/**
 * 超级表结构变更命令。
 *
 * ADD/MODIFY 使用 [type] 和可选 [length]；DROP 只使用 [name]；
 * RENAME_TAG 使用 [name] 和 [newName]。服务端会拒绝任何多余字段。
 */
@Serializable
data class TimeSeriesLifecycleCommand(
    val operation: TimeSeriesLifecycleOperation,
    val name: String,
    val type: TimeSeriesDataType? = null,
    val length: Int? = null,
    val newName: String? = null
)

@Serializable
data class TimeSeriesLifecycleField(
    val name: String,
    val type: String,
    val length: Int? = null,
    val primaryTimestamp: Boolean = false
)

/** 服务端读取的超级表结构快照；fingerprint 用于 apply 前的乐观并发校验。 */
@Serializable
data class TimeSeriesLifecycleSnapshot(
    val database: String,
    val stable: String,
    val columns: List<TimeSeriesLifecycleField>,
    val tags: List<TimeSeriesLifecycleField>,
    val fingerprint: String,
    val affectedChildTables: Long = 0
)

@Serializable
data class TimeSeriesLifecyclePreview(
    val command: TimeSeriesLifecycleCommand,
    val snapshot: TimeSeriesLifecycleSnapshot,
    val ddl: String,
    val previewToken: String,
    val destructive: Boolean,
    val warnings: List<String> = emptyList()
)

/** apply 只接受结构化命令和 preview 产生的校验信息，不接受客户端 DDL。 */
@Serializable
data class TimeSeriesLifecycleApplyRequest(
    val command: TimeSeriesLifecycleCommand,
    val expectedFingerprint: String,
    val previewToken: String,
    val confirmationName: String? = null
)

@Serializable
data class TimeSeriesLifecycleResult(
    val success: Boolean,
    val command: TimeSeriesLifecycleCommand,
    val ddl: String,
    val previousFingerprint: String
)

@Serializable
data class TimeSeriesChildTable(
    val name: String,
    val database: String,
    val stableName: String,
    val tagValues: List<TimeSeriesTagValue> = emptyList(),
    val createdAt: String? = null,
    val comment: String? = null,
    val ttl: Int = 0
)

@Serializable
data class TimeSeriesChildTablePage(
    val items: List<TimeSeriesChildTable>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

object TimeSeriesMetadataLimits {
    const val DEFAULT_CHILD_TABLE_PAGE_SIZE = 100
    const val MAX_CHILD_TABLE_PAGE_SIZE = 200
    const val MAX_TAG_FILTERS = 8
}

@Serializable
enum class TimeSeriesTagFilterOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
    CONTAINS,
    IS_NULL,
    IS_NOT_NULL
}

@Serializable
data class TimeSeriesTagFilter(
    val name: String,
    val operator: TimeSeriesTagFilterOperator,
    val value: String? = null
)

@Serializable
data class TimeSeriesChildTableQuery(
    val offset: Int = 0,
    val limit: Int = TimeSeriesMetadataLimits.DEFAULT_CHILD_TABLE_PAGE_SIZE,
    val search: String? = null,
    val filters: List<TimeSeriesTagFilter> = emptyList()
) {
    init {
        require(offset >= 0) { "offset must be greater than or equal to 0" }
        require(limit in 1..TimeSeriesMetadataLimits.MAX_CHILD_TABLE_PAGE_SIZE) {
            "limit must be between 1 and ${TimeSeriesMetadataLimits.MAX_CHILD_TABLE_PAGE_SIZE}"
        }
        require(filters.size <= TimeSeriesMetadataLimits.MAX_TAG_FILTERS) {
            "filters must not contain more than ${TimeSeriesMetadataLimits.MAX_TAG_FILTERS} conditions"
        }
    }
}

@Serializable
enum class TimeSeriesChildPropertyOperation {
    SET_TAG,
    SET_TTL,
    SET_COMMENT
}

@Serializable
data class TimeSeriesChildPropertyCommand(
    val operation: TimeSeriesChildPropertyOperation,
    val tagName: String? = null,
    val value: String? = null,
    val isNull: Boolean = false,
    val ttl: Int? = null,
    val comment: String? = null
)

@Serializable
data class TimeSeriesChildPropertySnapshot(
    val database: String,
    val table: String,
    val stableName: String,
    val tagValues: List<TimeSeriesTagValue>,
    val ttl: Int,
    val comment: String? = null,
    val fingerprint: String
)

@Serializable
data class TimeSeriesChildPropertyPreview(
    val command: TimeSeriesChildPropertyCommand,
    val snapshot: TimeSeriesChildPropertySnapshot,
    val ddl: String,
    val previewToken: String
)

@Serializable
data class TimeSeriesChildPropertyApplyRequest(
    val command: TimeSeriesChildPropertyCommand,
    val expectedFingerprint: String,
    val previewToken: String
)

@Serializable
data class TimeSeriesChildPropertyResult(
    val success: Boolean,
    val command: TimeSeriesChildPropertyCommand,
    val ddl: String,
    val previousFingerprint: String
)

@Serializable
enum class TimeSeriesDeleteObjectKind {
    BASIC_TABLE,
    CHILD_TABLE,
    SUPER_TABLE
}

/** 删除预览时由服务端实时读取的对象身份；fingerprint 用于阻止陈旧确认。 */
@Serializable
data class TimeSeriesDeleteSnapshot(
    val database: String,
    val name: String,
    val kind: TimeSeriesDeleteObjectKind,
    val stableName: String? = null,
    val createdAt: String? = null,
    val affectedChildTables: Long = 0,
    val fingerprint: String
)

@Serializable
data class TimeSeriesDeletePreview(
    val snapshot: TimeSeriesDeleteSnapshot,
    val ddl: String,
    val previewToken: String,
    val warnings: List<String> = emptyList()
)

/** apply 只接受预览凭证和精确对象名，不接受客户端 DDL。 */
@Serializable
data class TimeSeriesDeleteApplyRequest(
    val expectedFingerprint: String,
    val previewToken: String,
    val confirmationName: String
)

@Serializable
data class TimeSeriesDeleteResult(
    val success: Boolean,
    val snapshot: TimeSeriesDeleteSnapshot,
    val ddl: String
)

@Serializable
enum class TimeSeriesBasicTableOperation {
    ADD_COLUMN,
    DROP_COLUMN,
    MODIFY_COLUMN,
    RENAME_COLUMN
}

@Serializable
data class TimeSeriesBasicTableCommand(
    val operation: TimeSeriesBasicTableOperation,
    val name: String,
    val type: TimeSeriesDataType? = null,
    val length: Int? = null,
    val newName: String? = null
)

@Serializable
data class TimeSeriesBasicTableSnapshot(
    val database: String,
    val table: String,
    val columns: List<TimeSeriesLifecycleField>,
    val fingerprint: String
)

@Serializable
data class TimeSeriesBasicTablePreview(
    val command: TimeSeriesBasicTableCommand,
    val snapshot: TimeSeriesBasicTableSnapshot,
    val ddl: String,
    val previewToken: String,
    val destructive: Boolean,
    val warnings: List<String> = emptyList()
)

@Serializable
data class TimeSeriesBasicTableApplyRequest(
    val command: TimeSeriesBasicTableCommand,
    val expectedFingerprint: String,
    val previewToken: String,
    val confirmationName: String? = null
)

@Serializable
data class TimeSeriesBasicTableResult(
    val success: Boolean,
    val command: TimeSeriesBasicTableCommand,
    val ddl: String,
    val previousFingerprint: String
)

@Serializable
enum class TimeSeriesWriteTargetKind {
    BASIC_TABLE,
    EXISTING_CHILD_TABLE,
    NEW_CHILD_TABLE
}

/** null intent is separate so empty strings survive the complete API round trip. */
@Serializable
data class TimeSeriesWriteCell(
    val name: String,
    val value: String? = null,
    val isNull: Boolean = false
)

@Serializable
data class TimeSeriesWriteRow(
    val cells: List<TimeSeriesWriteCell>
)

@Serializable
data class TimeSeriesWriteRequest(
    val targetKind: TimeSeriesWriteTargetKind,
    val table: String,
    val stableName: String? = null,
    val columns: List<String>,
    val rows: List<TimeSeriesWriteRow>,
    val tagValues: List<TimeSeriesTagValueDraft> = emptyList()
)

@Serializable
data class TimeSeriesWriteSnapshot(
    val database: String,
    val targetKind: TimeSeriesWriteTargetKind,
    val table: String,
    val stableName: String? = null,
    val columns: List<TimeSeriesLifecycleField>,
    val tags: List<TimeSeriesLifecycleField> = emptyList(),
    val fingerprint: String
)

@Serializable
data class TimeSeriesWritePreview(
    val request: TimeSeriesWriteRequest,
    val snapshot: TimeSeriesWriteSnapshot,
    val sql: String,
    val previewToken: String,
    val rowCount: Int,
    val createsChildTable: Boolean
)

@Serializable
data class TimeSeriesWriteApplyRequest(
    val request: TimeSeriesWriteRequest,
    val expectedFingerprint: String,
    val previewToken: String
)

@Serializable
data class TimeSeriesWriteResult(
    val success: Boolean,
    val targetTable: String,
    val stableName: String? = null,
    val insertedRows: Int,
    val createdChildTable: Boolean
)

object TimeSeriesWriteLimits {
    const val MAX_ROWS = 100
    const val MAX_COLUMNS = 4096
    const val MAX_CELL_CHARS = 65_536
}

@Serializable
enum class CsvEncoding { AUTO, UTF8, GB18030 }

@Serializable
enum class CsvDelimiter { AUTO, COMMA, TAB, SEMICOLON }

@Serializable
data class CsvFileIdentity(
    val canonicalPath: String,
    val name: String,
    val size: Long,
    val lastModified: Long
)

@Serializable
data class TimeSeriesCsvColumnMapping(
    val sourceHeader: String,
    val targetColumn: String? = null
)

@Serializable
data class TimeSeriesCsvImportConfig(
    val filePath: String,
    val targetKind: TimeSeriesWriteTargetKind,
    val table: String,
    val stableName: String? = null,
    val tagValues: List<TimeSeriesTagValueDraft> = emptyList(),
    val encoding: CsvEncoding = CsvEncoding.AUTO,
    val delimiter: CsvDelimiter = CsvDelimiter.AUTO,
    val nullMarker: String = "NULL",
    val emptyAsNull: Boolean = false,
    val mappings: List<TimeSeriesCsvColumnMapping> = emptyList()
)

@Serializable
data class TimeSeriesCsvPreviewCell(
    val header: String,
    val rawValue: String,
    val value: String? = null,
    val isNull: Boolean = false,
    val error: String? = null
)

@Serializable
data class TimeSeriesCsvPreviewRow(
    val recordNumber: Long,
    val cells: List<TimeSeriesCsvPreviewCell>,
    val error: String? = null
)

@Serializable
data class TimeSeriesCsvPreview(
    val config: TimeSeriesCsvImportConfig,
    val file: CsvFileIdentity,
    val encoding: CsvEncoding,
    val delimiter: CsvDelimiter,
    val headers: List<String>,
    val suggestedMappings: List<TimeSeriesCsvColumnMapping>,
    val rows: List<TimeSeriesCsvPreviewRow>,
    val target: TimeSeriesWriteSnapshot,
    val blockingErrors: List<String> = emptyList()
)

@Serializable
data class TimeSeriesCsvImportStartRequest(
    val config: TimeSeriesCsvImportConfig,
    val expectedFile: CsvFileIdentity,
    val expectedTargetFingerprint: String
)

@Serializable
data class TimeSeriesCsvImportStartResult(val taskId: String)

object TimeSeriesCsvImportLimits {
    const val MAX_FILE_BYTES = 1_073_741_824L
    const val MAX_RECORDS = 10_000_000L
    const val PREVIEW_RECORDS = 100
    const val MIN_BATCH_ROWS = 500
    const val MAX_BATCH_ROWS = 5_000
}

@Serializable
data class TimeSeriesQueryRequest(
    val startInclusive: String? = null,
    val endExclusive: String? = null,
    val where: String? = null,
    val orderBy: String? = null,
    val limit: Int = TimeSeriesQueryLimits.DEFAULT_PAGE_SIZE,
    val offset: Int = 0
)

@Serializable
data class TimeSeriesQueryPage(
    val rows: List<Map<String, String?>>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val startInclusive: String? = null,
    val endExclusive: String? = null
)

object TimeSeriesQueryLimits {
    const val DEFAULT_PAGE_SIZE = 1_000
    const val MAX_PAGE_SIZE = 1_000
}

@Serializable
data class TriggerInfo(
    val name: String,
    val table: String? = null,
    val event: String? = null,     // INSERT | UPDATE | DELETE
    val timing: String? = null,    // BEFORE | AFTER
    val statement: String? = null,
    val comment: String? = null
)

@Serializable
data class RoutineInfo(
    val name: String,
    val type: String,              // PROCEDURE | FUNCTION
    val definer: String? = null,
    val created: String? = null,
    val modified: String? = null,
    val comment: String? = null
)

@Serializable
data class ColumnInfo(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val defaultValue: String? = null,
    val isPrimaryKey: Boolean = false,
    val isAutoIncrement: Boolean = false,
    val comment: String? = null
)

@Serializable
data class IndexInfo(
    val name: String,
    val columns: List<String>,
    val isUnique: Boolean = false,
    val isPrimary: Boolean = false,
    val type: String = "BTREE"
)

@Serializable
data class TableDefinition(
    val table: TableInfo,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>,
    val ddl: String? = null,
    /** "native" = 数据库原生 DDL；"synthesized" = EasyDB 拼装的降级 DDL；null = 未提供 */
    val ddlSource: String? = null
)

// ─── SQL 执行模型 ────────────────────────────────────────
@Serializable
data class SqlExecuteRequest(
    val connectionId: String,
    val database: String,
    val sql: String
)

@Serializable
data class SqlQueryPreviewRequest(
    val connectionId: String,
    val database: String,
    val sql: String,
    val offset: Int = 0,
    val pageSize: Int = 200,
    val maxCellChars: Int = 4096
)

@Serializable
data class SqlQuerySessionStartRequest(
    val connectionId: String,
    val database: String,
    val sql: String,
    val pageSize: Int = 200,
    val maxCellChars: Int = 4096
)

@Serializable
data class SqlQuerySessionFetchRequest(
    val querySessionId: String,
    val pageSize: Int = 200,
    val maxCellChars: Int = 4096
)

@Serializable
data class SqlQuerySessionCloseRequest(
    val querySessionId: String
)

@Serializable
data class SqlQuerySessionStatusRequest(
    val querySessionId: String
)

@Serializable
data class SqlQuerySessionStatus(
    val querySessionId: String,
    val totalRows: Long? = null,
    val counting: Boolean = false,
    val exists: Boolean = true
)

@Serializable
data class SqlImportFileRequest(
    val connectionId: String,
    val database: String,
    val filePath: String,
    val fileName: String? = null
)

@Serializable
data class SqlResult(
    val type: String, // query | update | error
    val columns: List<String>? = null,
    val rows: List<Map<String, String?>>? = null,
    val affectedRows: Int? = null,
    val preview: Boolean = false,
    val hasMore: Boolean? = null,
    val querySessionId: String? = null,
    val totalRows: Long? = null,
    val offset: Int? = null,
    val pageSize: Int? = null,
    val loadedRows: Int? = null,
    val truncatedCellCount: Int? = null,
    val duration: Long,
    val sql: String,
    val executedAt: String,
    val error: String? = null,
    val warning: String? = null
)

// ─── 迁移模型 ─────────────────────────────────────────────
@Serializable
data class MigrationConfig(
    val sourceConnectionId: String,
    val targetConnectionId: String,
    val sourceDatabase: String,
    val targetDatabase: String,
    val tables: List<String>,
    val mode: String = "structure_and_data" // structure_only | data_only | structure_and_data
)

@Serializable
data class MigrationPreview(
    val totalTables: Int,
    val totalRows: Long? = null,
    val tables: List<MigrationTablePreview>,
    val warnings: List<String> = emptyList()
)

@Serializable
data class MigrationTablePreview(
    val tableName: String,
    val rowCount: Long? = null,
    val hasStructure: Boolean = true,
    val hasData: Boolean = true,
    val risk: String? = null
)

// ─── 同步模型 ─────────────────────────────────────────────
@Serializable
data class SyncConfig(
    val sourceConnectionId: String,
    val targetConnectionId: String,
    val sourceDatabase: String,
    val targetDatabase: String,
    val tables: List<String> = emptyList()
)

@Serializable
data class SyncPreview(
    val totalTables: Int,
    val tables: List<SyncTablePreview>,
    val warnings: List<String> = emptyList()
)

@Serializable
data class SyncTablePreview(
    val tableName: String,
    val insertCount: Int = 0,
    val updateCount: Int = 0,
    val skipCount: Int = 0,
    val canSync: Boolean = true,
    val reason: String? = null
)

// ─── 结构对比模型 ──────────────────────────────────────────
@Serializable
data class CompareConfig(
    val sourceConnectionId: String,
    val targetConnectionId: String,
    val sourceDatabase: String,
    val targetDatabase: String,
    val tables: List<String> = emptyList(),
    val options: CompareOptions = CompareOptions()
)

@Serializable
data class CompareOptions(
    val ignoreComment: Boolean = true,
    val ignoreAutoIncrement: Boolean = true,
    val ignoreCharset: Boolean = false,
    val ignoreCollation: Boolean = false,
    val includeDropStatements: Boolean = false,
 val compareViews: Boolean = true,
 val compareProcedures: Boolean = true,
 val compareFunctions: Boolean = true,
 val compareTriggers: Boolean = true
)

@Serializable
data class CompareResult(
    val sourceDatabase: String,
    val targetDatabase: String,
    val totalTables: Int,
    val diffCount: Int,
    val tables: List<TableCompareResult>,
    // 扫展对象（默认空，旧客户端向后兼容）
    val views:      List<ObjectCompareResult> = emptyList(),
    val procedures: List<ObjectCompareResult> = emptyList(),
    val functions:  List<ObjectCompareResult> = emptyList(),
    val triggers:   List<ObjectCompareResult> = emptyList()
)

@Serializable
data class TableCompareResult(
    val tableName: String,
    val status: String,        // only_in_source | only_in_target | different | identical
    val risk: String = "low",  // low | medium | high
    val columnDiffs: List<ColumnDiff> = emptyList(),
    val indexDiffs: List<IndexDiff> = emptyList(),
    val sql: String = "",
    val summary: String = ""
)

/**
 * 非表对象（视图、存储过程、函数、触发器）的对比结果
 * status: only_in_source | only_in_target | different | identical
 */
@Serializable
data class ObjectCompareResult(
    val name: String,
    val objectType: String,    // view | procedure | function | trigger
    val status: String,
    val sourceDdl: String = "",
    val targetDdl: String = "",
    val summary: String = ""
)

@Serializable
data class ColumnDiff(
    val columnName: String,
    val status: String,        // added | removed | modified | identical
    val sourceType: String? = null,
    val targetType: String? = null,
    val sourceNullable: Boolean? = null,
    val targetNullable: Boolean? = null,
    val sourceDefault: String? = null,
    val targetDefault: String? = null,
    val sourceComment: String? = null,
    val targetComment: String? = null,
    val details: String = ""
)

@Serializable
data class IndexDiff(
    val indexName: String,
    val status: String,        // added | removed | modified | identical
    val sourceColumns: List<String>? = null,
    val targetColumns: List<String>? = null,
    val sourceUnique: Boolean? = null,
    val targetUnique: Boolean? = null,
    val sourcePrimary: Boolean? = null,
    val targetPrimary: Boolean? = null,
    val details: String = ""
)

// ─── 数据编辑模型 ──────────────────────────────────────────
@Serializable
data class DataEditRequest(
    val connectionId: String,
    val database: String,
    val table: String,
    val changes: List<RowChange>,
    val dryRun: Boolean = false   // true 只生成 SQL 不执行
)

@Serializable
data class RowChange(
    val type: String,          // insert | update | delete
    val primaryKeys: Map<String, String?> = emptyMap(),  // 主键值（update/delete 用）
    val values: Map<String, String?> = emptyMap(),        // 新值（insert/update 用）
    val oldValues: Map<String, String?> = emptyMap()      // 旧值（update 用于冲突检测）
)

@Serializable
data class DataEditResult(
    val success: Boolean,
    val sqlStatements: List<String>,
    val affectedRows: Int = 0,
    val errors: List<String> = emptyList()
)

// ─── 存储过程 / 函数执行模型 ────────────────────────────────

/** 单个参数元数据（来自 INFORMATION_SCHEMA.PARAMETERS 或数据库本地等价物）*/
@Serializable
data class ProcedureParam(
    val name: String,
    val ordinalPosition: Int,
    val mode: String,                       // "IN" | "OUT" | "INOUT" | "RETURNS"
    val dataType: String,                   // "INT" | "VARCHAR" | "DECIMAL" | "DATE" | ...
    val characterMaxLength: Long? = null,
    val numericPrecision: Int? = null,
    val numericScale: Int? = null,
    val dtdIdentifier: String? = null       // 完整类型描述，如 "varchar(255)"
)

/** inspect 接口响应：参数列表 + DDL + comment */
@Serializable
data class ProcedureInspectResult(
    val name: String,
    val type: String,                       // "PROCEDURE" | "FUNCTION"
    val database: String,
    val definer: String? = null,
    val comment: String? = null,
    val params: List<ProcedureParam>,
    val ddl: String? = null
)

/** execute 接口中单个参数的传值 */
@Serializable
data class ProcedureParamValue(
    val name: String,
    val value: String?,                     // null 表示传 NULL
    val mode: String = "IN"                 // "IN" | "INOUT" | "OUT"
)

/** execute 接口请求体 */
@Serializable
data class ProcedureExecuteRequest(
    val connectionId: String,
    val database: String,
    val name: String,
    val type: String = "PROCEDURE",         // "PROCEDURE" | "FUNCTION"
    val params: List<ProcedureParamValue> = emptyList()
)

/** 单个结果集（一次 CALL 可能返回多个） */
@Serializable
data class ProcedureResultSet(
    val index: Int,                         // 第几个结果集，从 0 开始
    val columns: List<String>,
    val rows: List<Map<String, String?>>,
    val rowCount: Int
)

/** execute 接口响应 */
@Serializable
data class ProcedureExecuteResult(
    val success: Boolean,
    val duration: Long,
    val outParams: Map<String, String?> = emptyMap(),   // OUT / INOUT 参数回显
    val resultSets: List<ProcedureResultSet> = emptyList(),
    val warningCount: Int = 0,
    val error: String? = null
)
