package com.easydb.launcher

import com.easydb.common.MigrationAdapter

data class RegisteredMigrationAdapter(
    val sourceDbType: String,
    val targetDbType: String,
    val adapter: MigrationAdapter
)

class MigrationAdapterRegistry(
    adapters: List<RegisteredMigrationAdapter>
) : DatabasePairRegistry<MigrationAdapter>(
    adapters.map { Registration(it.sourceDbType, it.targetDbType, it.adapter) },
    "迁移"
) {
    fun get(sourceDbType: String, targetDbType: String): MigrationAdapter = resolve(sourceDbType, targetDbType)
    fun supports(sourceDbType: String, targetDbType: String): Boolean = contains(sourceDbType, targetDbType)
}
