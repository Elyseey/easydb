package com.easydb.launcher

import com.easydb.common.DatabaseAdapter

class DatabaseAdapterRegistry(
    private val adapters: List<DatabaseAdapter>
) {
    fun get(dbType: String): DatabaseAdapter {
        val normalized = when (dbType.lowercase()) {
            "dm" -> "dameng"
            else -> dbType.lowercase()
        }
        return adapters.firstOrNull { it.dbType().name.lowercase() == normalized }
            ?: throw IllegalArgumentException("不支持的数据库类型：$dbType")
    }
}
