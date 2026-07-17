package com.easydb.launcher

abstract class DatabasePairRegistry<T>(
    registrations: List<Registration<T>>,
    private val featureLabel: String
) {
    data class Registration<T>(
        val sourceDbType: String,
        val targetDbType: String,
        val adapter: T
    )

    private val registrations = registrations.toList()

    fun resolve(sourceDbType: String, targetDbType: String): T {
        val source = normalize(sourceDbType)
        val target = normalize(targetDbType)
        return registrations.firstOrNull {
            normalize(it.sourceDbType) == source && normalize(it.targetDbType) == target
        }?.adapter ?: throw IllegalArgumentException(
            "当前不支持 ${label(sourceDbType)} → ${label(targetDbType)} $featureLabel"
        )
    }

    fun contains(sourceDbType: String, targetDbType: String): Boolean {
        val source = normalize(sourceDbType)
        val target = normalize(targetDbType)
        return registrations.any {
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
