package com.easydb.launcher

import com.easydb.common.MigrationAdapter

data class RegisteredMigrationAdapter(
    val sourceDbType: String,
    val targetDbType: String,
    val adapter: MigrationAdapter
)

class MigrationAdapterRegistry(
    private val adapters: List<RegisteredMigrationAdapter>
) {
    fun get(sourceDbType: String, targetDbType: String): MigrationAdapter {
        val source = normalize(sourceDbType)
        val target = normalize(targetDbType)
        return adapters.firstOrNull {
            normalize(it.sourceDbType) == source && normalize(it.targetDbType) == target
        }?.adapter ?: throw IllegalArgumentException("当前不支持 ${label(sourceDbType)} → ${label(targetDbType)} 迁移")
    }

    fun supports(sourceDbType: String, targetDbType: String): Boolean {
        val source = normalize(sourceDbType)
        val target = normalize(targetDbType)
        return adapters.any {
            normalize(it.sourceDbType) == source && normalize(it.targetDbType) == target
        }
    }

    private fun normalize(dbType: String): String = when (dbType.trim().lowercase()) {
        "dm" -> "dameng"
        else -> dbType.trim().lowercase()
    }

    private fun label(dbType: String): String = when (normalize(dbType)) {
        "mysql" -> "MySQL"
        "dameng" -> "达梦"
        else -> dbType
    }
}
