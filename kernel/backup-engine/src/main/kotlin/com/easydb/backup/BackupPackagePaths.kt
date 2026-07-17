package com.easydb.backup

import java.security.MessageDigest

/**
 * 备份包路径只使用内部序号，绝不拼接数据库对象名。
 * 数据库合法标识符可能包含斜杠、反斜杠或 `..`，不能作为文件路径片段。
 */
internal object BackupPackagePaths {
    fun tableDdl(tableIndex: Int): String =
        "schema/010_tables/table_${token(tableIndex)}.sql"

    fun tableData(tableIndex: Int, partIndex: Int): String =
        "data/table_${token(tableIndex)}.part${token(partIndex, 3)}.sql.gz"

    fun routineDdl(index: Int): String = "schema/020_routines/routine_${token(index)}.sql"
    fun viewDdl(index: Int): String = "schema/030_views/view_${token(index)}.sql"
    fun triggerDdl(index: Int): String = "schema/040_triggers/trigger_${token(index)}.sql"

    fun outputFileName(namespace: String, timestamp: String): String {
        val safeNamespace = namespace.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            ?: run {
                val slug = namespace.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    .take(80)
                    .ifBlank { "namespace" }
                "${slug}_${shortHash(namespace)}"
            }
        return "${safeNamespace}_${timestamp}.edbkp"
    }

    private fun token(index: Int, width: Int = 5): String {
        require(index >= 0) { "Backup package index must be non-negative" }
        return index.toString().padStart(width, '0')
    }

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
}
