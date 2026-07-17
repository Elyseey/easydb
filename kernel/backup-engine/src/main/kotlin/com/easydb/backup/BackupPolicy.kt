package com.easydb.backup

import com.easydb.common.DatabaseAdapter

object BackupPolicy {
    private val validModes = setOf("full", "structure_only", "data_only")
    private val validCompression = setOf("gzip")

    fun validateOrThrow(config: BackupConfig, adapter: DatabaseAdapter) {
        require(adapter.capabilities().supportsLogicalBackup) {
            "${adapter.dbType().displayName} 暂不支持逻辑备份"
        }
        require(config.database.isNotBlank()) { "备份 namespace 不能为空" }
        require(config.mode in validModes) { "不支持的备份模式：${config.mode}" }
        require(config.compression in validCompression) { "不支持的压缩格式：${config.compression}" }
        require(config.tables.none { it.isBlank() }) { "备份表名不能为空" }
        require(config.tables.distinct().size == config.tables.size) { "备份表列表包含重复项" }
    }
}
