package com.easydb.drivers.mysql

import com.easydb.common.LogicalBackupAdapter
import com.easydb.common.LogicalBackupContext
import java.sql.Connection

/** MySQL REPEATABLE READ 一致性快照。 */
class MysqlLogicalBackupAdapter : LogicalBackupAdapter {
    override fun configureStreamingStatement(statement: java.sql.Statement) {
        statement.fetchSize = Integer.MIN_VALUE
    }

    override fun begin(connection: Connection): LogicalBackupContext {
        return try {
            connection.createStatement().use {
                it.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            }
            connection.createStatement().use {
                it.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
            }

            var binlogFile: String? = null
            var binlogPosition: Long? = null
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SHOW MASTER STATUS").use { result ->
                        if (result.next()) {
                            binlogFile = result.getString("File")
                            binlogPosition = result.getLong("Position")
                        }
                    }
                }
            } catch (_: Exception) {
                // REPLICATION CLIENT 权限不是逻辑备份的一致性前提。
            }

            val charset = queryScalar(connection, "SELECT @@character_set_database")
            val collation = queryScalar(connection, "SELECT @@collation_database")
            LogicalBackupContext(
                consistency = "snapshot",
                binlogFile = binlogFile,
                binlogPosition = binlogPosition,
                charset = charset ?: "utf8mb4",
                collation = collation ?: "utf8mb4_general_ci"
            )
        } catch (error: Exception) {
            tryRollback(connection)
            LogicalBackupContext(
                consistency = "best_effort",
                charset = "utf8mb4",
                collation = "utf8mb4_general_ci",
                warnings = listOf("MySQL 一致性快照建立失败，已降级为 best_effort：${safeMessage(error)}")
            )
        }
    }

    override fun finish(connection: Connection) {
        tryRollback(connection)
    }

    private fun queryScalar(connection: Connection, sql: String): String? =
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    if (result.next()) result.getString(1) else null
                }
            }
        } catch (_: Exception) {
            null
        }

    private fun tryRollback(connection: Connection) {
        try {
            connection.createStatement().use { it.execute("ROLLBACK") }
        } catch (_: Exception) {
            // 连接即将关闭，回滚失败不掩盖原始任务结果。
        }
    }

    private fun safeMessage(error: Exception): String =
        error.message?.lineSequence()?.firstOrNull()?.take(200) ?: error.javaClass.simpleName
}
