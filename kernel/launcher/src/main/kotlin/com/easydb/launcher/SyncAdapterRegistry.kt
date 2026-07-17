package com.easydb.launcher

import com.easydb.common.SyncAdapter

data class RegisteredSyncAdapter(
    val sourceDbType: String,
    val targetDbType: String,
    val adapter: SyncAdapter
)

class SyncAdapterRegistry(
    adapters: List<RegisteredSyncAdapter>
) : DatabasePairRegistry<SyncAdapter>(
    adapters.map { Registration(it.sourceDbType, it.targetDbType, it.adapter) },
    "同步"
) {
    fun get(sourceDbType: String, targetDbType: String): SyncAdapter = resolve(sourceDbType, targetDbType)
    fun supports(sourceDbType: String, targetDbType: String): Boolean = contains(sourceDbType, targetDbType)
}
