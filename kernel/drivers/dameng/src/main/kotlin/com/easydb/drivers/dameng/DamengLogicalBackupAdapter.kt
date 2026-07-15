package com.easydb.drivers.dameng

import com.easydb.common.LogicalBackupAdapter
import com.easydb.common.LogicalBackupContext
import java.sql.Connection

/** 达梦串行化只读事务快照。 */
class DamengLogicalBackupAdapter : LogicalBackupAdapter {
    override fun begin(connection: Connection): LogicalBackupContext {
        return try {
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
            connection.isReadOnly = true
            connection.createStatement().use { statement ->
                statement.execute("SET TRANSACTION READ ONLY")
            }
            LogicalBackupContext(consistency = "snapshot")
        } catch (error: Exception) {
            rollbackQuietly(connection)
            try {
                connection.isReadOnly = true
            } catch (_: Exception) {
                // best_effort 仍可继续读取，manifest 会明确记录降级。
            }
            LogicalBackupContext(
                consistency = "best_effort",
                warnings = listOf("达梦串行化只读快照建立失败，已降级为 best_effort：${safeMessage(error)}")
            )
        }
    }

    override fun finish(connection: Connection) {
        rollbackQuietly(connection)
    }

    private fun rollbackQuietly(connection: Connection) {
        try {
            connection.rollback()
        } catch (_: Exception) {
            try {
                connection.createStatement().use { it.execute("ROLLBACK") }
            } catch (_: Exception) {
                // 连接即将关闭，回滚失败不掩盖原始任务结果。
            }
        }
    }

    private fun safeMessage(error: Exception): String =
        error.message?.lineSequence()?.firstOrNull()?.take(200) ?: error.javaClass.simpleName
}
