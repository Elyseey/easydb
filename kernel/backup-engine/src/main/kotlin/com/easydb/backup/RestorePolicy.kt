package com.easydb.backup

import com.easydb.common.DatabaseAdapter
import com.easydb.common.DbType

/** 同时供路由预检和执行服务复用的恢复安全策略。 */
object RestorePolicy {
    private val validModes = setOf("restore_all", "structure_only", "data_only")
    private val validStrategies = setOf("restore_to_new", "overwrite_existing")

    fun validateOrThrow(
        config: RestoreConfig,
        manifest: BackupManifest,
        adapter: DatabaseAdapter,
        targetExists: Boolean
    ) {
        val capabilities = adapter.capabilities()
        require(capabilities.supportsLogicalRestore) {
            "${adapter.dbType().displayName} 暂不支持逻辑恢复"
        }
        require(config.targetDatabase.isNotBlank()) { "目标 namespace 不能为空" }
        require(config.mode in validModes) { "不支持的恢复模式：${config.mode}" }
        require(config.strategy in validStrategies) { "不支持的恢复策略：${config.strategy}" }

        val targetDbType = adapter.dbType().name.lowercase()
        require(manifest.dbType.equals(targetDbType, ignoreCase = true)) {
            "备份包数据库类型 ${manifest.dbType} 与目标连接类型 $targetDbType 不匹配"
        }

        if (config.strategy == "restore_to_new") {
            require(!targetExists) { "目标 namespace 已存在，只能恢复到全新 namespace" }
        }
        if (config.strategy == "overwrite_existing") {
            require(capabilities.supportsOverwriteRestore) {
                "${adapter.dbType().displayName} 不支持覆盖已有 namespace"
            }
        }

        if (adapter.dbType() == DbType.DAMENG) {
            require(config.strategy == "restore_to_new") { "达梦仅支持恢复到全新 Schema" }
            require(config.mode != "data_only" && manifest.mode != "data_only") {
                "达梦恢复到全新 Schema 必须包含表结构，不支持仅恢复数据"
            }
        }

        val availableTables = manifest.tables.mapTo(hashSetOf()) { it.tableName }
        val missingSelection = config.selectedTables.firstOrNull { it !in availableTables }
        require(missingSelection == null) { "备份包中不存在所选表：$missingSelection" }
    }
}
