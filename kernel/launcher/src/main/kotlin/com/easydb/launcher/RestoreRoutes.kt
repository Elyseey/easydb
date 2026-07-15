package com.easydb.launcher

import com.easydb.api.ok
import com.easydb.api.fail
import com.easydb.backup.*
import com.easydb.common.TaskStatus
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private val restoreTaskScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

fun Route.restoreRoutes() {
    val restoreService = RestoreService()
    val adapterRegistry = ServiceRegistry.adapterRegistry

    post("/inspect") {
        val data = call.receive<Map<String, String>>()
        val path = data["filePath"]
            ?: return@post call.fail("MISSING_PARAM", "缺少 filePath 参数")

        val validator = RestoreValidator(File(path))
        val result = validator.inspect()

        call.ok(result)
    }

    post("/start") {
        val config = call.receive<RestoreConfig>()
        val connConfig = ServiceRegistry.connectionStore.getById(config.targetConnectionId)
            ?: return@post call.fail("NOT_FOUND", "目标连接配置不存在")

        // 验证目标连接是否已激活
        val session = ServiceRegistry.connectionManager.getPrimarySession(config.targetConnectionId)
            ?: return@post call.fail("NOT_CONNECTED", "目标连接未激活，请先打开连接")

        val dbAdapter = adapterRegistry.get(connConfig.dbType)
        if (!dbAdapter.capabilities().supportsLogicalRestore) {
            return@post call.fail("UNSUPPORTED_DB_FEATURE", "${dbAdapter.dbType().displayName} 暂不支持逻辑恢复")
        }

        val inspectResult = RestoreValidator(File(config.backupFilePath)).inspect()
        if (!inspectResult.fileValid) {
            return@post call.fail("INVALID_BACKUP_PACKAGE", inspectResult.warnings.joinToString())
        }
        if (!inspectResult.checksumValid) {
            return@post call.fail("BACKUP_CHECKSUM_FAILED", inspectResult.warnings.joinToString())
        }

        val targetNamespace = dbAdapter.dialectAdapter().normalizeNewNamespaceName(config.targetDatabase)
        val targetExists = try {
            dbAdapter.metadataAdapter().listDatabases(session).any { it.name == targetNamespace }
        } catch (error: Exception) {
            return@post call.fail(
                "RESTORE_PREFLIGHT_FAILED",
                "无法确认目标 namespace 是否存在：${error.message ?: error.javaClass.simpleName}"
            )
        }
        try {
            RestorePolicy.validateOrThrow(config, inspectResult.manifest, dbAdapter, targetExists)
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "恢复请求不符合安全策略"
            val code = when {
                message.contains("数据库类型") -> "RESTORE_DB_TYPE_MISMATCH"
                message.contains("仅恢复数据") -> "INVALID_RESTORE_MODE"
                message.contains("已存在") -> "TARGET_NAMESPACE_EXISTS"
                message.contains("覆盖") -> "UNSUPPORTED_RESTORE_STRATEGY"
                else -> "INVALID_RESTORE_REQUEST"
            }
            return@post call.fail(code, message)
        }

        val taskName = "Restore $targetNamespace"
        val taskInfo = ServiceRegistry.taskManager.createTask(taskName, "restore")

        restoreTaskScope.launch {
            val reporter = ServiceRegistry.taskManager.createReporter(taskInfo.id)
            val startTime = System.currentTimeMillis()
            reporter.onStep("Init", TaskStatus.RUNNING, "Starting logical restore...")

            try {
                val res = restoreService.execute(config, connConfig, reporter, dbAdapter)
                ServiceRegistry.taskManager.markCompleted(taskInfo.id, System.currentTimeMillis() - startTime, res)
            } catch (e: Exception) {
                if (reporter.isCancelled()) {
                    ServiceRegistry.taskManager.markCancelled(taskInfo.id, System.currentTimeMillis() - startTime)
                } else {
                    reporter.onLog("ERROR", e.stackTraceToString())
                    ServiceRegistry.taskManager.markFailed(taskInfo.id, e.message ?: "Unknown error")
                }
            }
        }

        call.ok(mapOf("taskId" to taskInfo.id))
    }
}
