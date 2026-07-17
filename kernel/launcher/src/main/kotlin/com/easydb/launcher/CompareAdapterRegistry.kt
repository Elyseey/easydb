package com.easydb.launcher

import com.easydb.common.CompareAdapter

data class RegisteredCompareAdapter(
    val sourceDbType: String,
    val targetDbType: String,
    val adapter: CompareAdapter
)

class CompareAdapterRegistry(
    adapters: List<RegisteredCompareAdapter>
) : DatabasePairRegistry<CompareAdapter>(
    adapters.map { Registration(it.sourceDbType, it.targetDbType, it.adapter) },
    "结构对比"
) {
    fun get(sourceDbType: String, targetDbType: String): CompareAdapter = resolve(sourceDbType, targetDbType)
    fun supports(sourceDbType: String, targetDbType: String): Boolean = contains(sourceDbType, targetDbType)
}
