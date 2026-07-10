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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File

private val backupTaskScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

@Serializable
private data class BackupFileDeleteResult(
    val deleted: Boolean,
    val fileName: String,
    val alreadyMissing: Boolean = false
)

private fun defaultBackupsDir(): File =
    File(System.getProperty("user.home"), ".easydb/backups")

private fun isFileUnderDir(file: File, dir: File): Boolean =
    file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())

fun Route.backupRoutes() {
    val backupService = LogicalBackupService()
    val adapterRegistry = ServiceRegistry.adapterRegistry

    post("/estimate") {
        val config = call.receive<BackupConfig>()
        val session = ServiceRegistry.connectionManager.getPrimarySession(config.connectionId)
            ?: return@post call.fail("NOT_CONNECTED", "连接未激活，请先打开连接")

        val dbAdapter = adapterRegistry.get(session.config.dbType)
        val metadataAdapter = dbAdapter.metadataAdapter()

        // 在 IO 线程执行阻塞 JDBC 查询，并设置 15s 超时防止无限阻塞
        val tables = withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                try {
                    metadataAdapter.listTables(session, config.database)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()
        }
        
        val selected = tables.filter { config.tables.isEmpty() || config.tables.contains(it.name) }
        val estimatedRows = selected.sumOf { it.rowCount ?: 0L }
        val estimatedBytes = selected.sumOf { (it.dataLength ?: 0L) + (it.indexLength ?: 0L) }
        
        call.ok(BackupEstimateResult(
            database = config.database,
            selectedTables = selected.size,
            estimatedRows = estimatedRows,
            estimatedBytes = estimatedBytes,
            largeTableCount = selected.count { (it.rowCount ?: 0L) > 1_000_000L },
            warnings = if (tables.isEmpty()) listOf("元数据查询超时或无可用表") else emptyList()
        ))
    }

    post("/start") {
        val config = call.receive<BackupConfig>()
        val connConfig = ServiceRegistry.connectionStore.getById(config.connectionId)
            ?: return@post call.fail("NOT_FOUND", "连接配置不存在")

        val dbAdapter = adapterRegistry.get(connConfig.dbType)

        val taskName = "Backup ${config.database}"
        val taskInfo = ServiceRegistry.taskManager.createTask(taskName, "backup")

        backupTaskScope.launch {
            val reporter = ServiceRegistry.taskManager.createReporter(taskInfo.id)
            val startTime = System.currentTimeMillis()
            reporter.onStep("Init", TaskStatus.RUNNING, "Starting logical backup...")

            try {
                val res = backupService.execute(config, connConfig, reporter, dbAdapter)
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

    get("/download/{taskId}") {
        val taskId = call.parameters["taskId"] ?: return@get call.fail("INVALID_ID", "缺少任务 ID")
        val task = ServiceRegistry.taskManager.get(taskId) ?: return@get call.fail("NOT_FOUND", "任务不存在")
        if (task.type != "backup") return@get call.fail("INVALID_TASK", "任务不是备份任务")
        if (task.status != "completed") return@get call.fail("INVALID_STATUS", "任务未完成，无法下载")

        val filePath = task.payload?.get("filePath") ?: return@get call.fail("NO_FILE", "该任务未关联任何可下载文件")
        val fileName = task.payload?.get("fileName") ?: File(filePath).name
        val file = File(filePath)

        if (!file.exists() || !file.isFile) return@get call.fail("FILE_NOT_FOUND", "备份文件已丢失或被清理")
        if (!file.name.endsWith(".edbkp")) return@get call.fail("FORBIDDEN", "只能下载 .edbkp 备份文件")

        call.response.header("Content-Disposition", "attachment; filename=\"${fileName}\"")
        call.respondFile(file)
    }

    get("/download-file/{fileName}") {
        val fileName = call.parameters["fileName"] ?: return@get call.fail("MISSING_PARAM", "缺少 fileName 参数")
        if (fileName.contains('/') || fileName.contains('\\') || !fileName.endsWith(".edbkp")) {
            return@get call.fail("FORBIDDEN", "非法备份文件名")
        }

        val backupsDir = defaultBackupsDir()
        val file = File(backupsDir, fileName)
        if (!isFileUnderDir(file, backupsDir)) return@get call.fail("FORBIDDEN", "只能下载默认备份目录下的文件")
        if (!file.exists() || !file.isFile) return@get call.fail("FILE_NOT_FOUND", "备份文件不存在：$fileName")

        call.response.header("Content-Disposition", "attachment; filename=\"${file.name}\"")
        call.respondFile(file)
    }

    get("/download") {
        call.fail("FORBIDDEN", "不再支持通过任意 path 下载备份文件")
    }

    get("/list") {
        val backupsDir = defaultBackupsDir()
        val files = if (backupsDir.exists()) {
            backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".edbkp") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { f ->
                    mapOf(
                        "fileName" to f.name,
                        "filePath" to f.absolutePath,
                        "fileSizeBytes" to f.length().toString(),
                        "lastModified" to f.lastModified().toString()
                    )
                } ?: emptyList()
        } else emptyList()
        call.ok(files)
    }

    delete("/file") {
        val data = call.receive<Map<String, String>>()
        val path = data["filePath"]
            ?: return@delete call.fail("MISSING_PARAM", "缺少 filePath 参数")

        val file = File(path)
        val backupsDir = defaultBackupsDir()

        // 安全校验：只允许删除备份目录下的 .edbkp 文件
        if (!isFileUnderDir(file, backupsDir)) {
            return@delete call.fail("FORBIDDEN", "只能删除备份目录下的文件")
        }
        if (!file.name.endsWith(".edbkp")) {
            return@delete call.fail("FORBIDDEN", "只能删除 .edbkp 备份文件")
        }
        if (!file.exists()) {
            return@delete call.ok(BackupFileDeleteResult(deleted = false, fileName = file.name, alreadyMissing = true))
        }

        val deleted = file.delete()
        if (deleted) {
            call.ok(BackupFileDeleteResult(deleted = true, fileName = file.name))
        } else {
            call.fail("DELETE_FAILED", "删除失败，请检查文件权限")
        }
    }
}
