/*
 * Copyright (c) 2024-2026 EasyDB Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.easydb.common

import kotlinx.serialization.Serializable

// ─── 数据库类型枚举 ────────────────────────────────────────
enum class DbType(val displayName: String) {
    MYSQL("MySQL"),
    DAMENG("达梦"),
    TDENGINE("TDengine"),
    POSTGRESQL("PostgreSQL"),
    ORACLE("Oracle"),
    SQLSERVER("SQL Server"),
    SQLITE("SQLite");
}

// ─── 连接配置 ──────────────────────────────────────────────
@Serializable
data class ConnectionConfig(
    val id: String = "",
    val name: String,
    val dbType: String = "mysql",
    val host: String = "127.0.0.1",
    val port: Int = 3306,
    val username: String = "",
    val password: String = "",
    val database: String? = null,
    val status: String = "disconnected",
    val lastUsedAt: String? = null,
    val ssh: SshConfig? = null,
    val ssl: SslConfig? = null,
    val groupId: String? = null,
    /** Credential vault reference for the database password. */
    val passwordRef: String? = null
)

// ─── 连接分组 ──────────────────────────────────────────────
@Serializable
data class ConnectionGroup(
    val id: String = "",
    val name: String,
    val sortOrder: Int = 0
)

// ─── SSH 隧道配置 ──────────────────────────────────────────
@Serializable
data class SshConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: String = "password", // password | privateKey
    val password: String? = null,
    val privateKeyPath: String? = null,
    /** Credential vault reference for the SSH password. */
    val passwordRef: String? = null
)

// ─── SSL 配置 ──────────────────────────────────────────────
@Serializable
data class SslConfig(
    val enabled: Boolean = false,
    val caPath: String? = null,
    val certPath: String? = null,
    val keyPath: String? = null,
    val rejectUnauthorized: Boolean = true
)

// ─── 任务状态枚举 ──────────────────────────────────────────
enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

// ─── 任务类型枚举 ──────────────────────────────────────────
enum class TaskType {
    MIGRATION,
    SYNC
}

// ─── 数据库能力声明 ────────────────────────────────────────
data class DatabaseCapabilities(
    val supportsTransactions: Boolean = true,
    val supportsSsh: Boolean = true,
    val supportsSsl: Boolean = true,
    val supportsAlterDatabaseCharset: Boolean = false,
    val supportsViews: Boolean = true,
    val supportsStoredProcedures: Boolean = false,
    val supportsTriggers: Boolean = false,
    val supportsLogicalExport: Boolean = false,
    val supportsSqlFileImport: Boolean = false,
    val supportsLogicalBackup: Boolean = false,
    val supportsLogicalRestore: Boolean = false,
    val supportsOverwriteRestore: Boolean = false,
    /** 专属时序对象设计器；不得用于开启通用关系型 TableDesigner。 */
    val supportsTimeSeriesObjectCreate: Boolean = false,
    /** 带结构化时间范围与查询态分页的专属时序预览。 */
    val supportsTimeSeriesQuery: Boolean = false,
    val supportsTableCreate: Boolean = true,
    val supportsTableRename: Boolean = true,
    val supportsTableDrop: Boolean = true,
    val supportsTableTruncate: Boolean = true,
    val supportsRowEdit: Boolean = true
)

/**
 * 数据库驱动为逻辑备份建立的读取上下文。
 *
 * [consistency] 只能是 snapshot 或 best_effort。驱动无法建立一致性快照时必须在
 * [warnings] 中说明原因，调用方会把警告写入任务日志和 manifest。
 */
data class LogicalBackupContext(
    val consistency: String,
    val binlogFile: String? = null,
    val binlogPosition: Long? = null,
    val charset: String? = null,
    val collation: String? = null,
    val warnings: List<String> = emptyList()
)

// ─── 安全辅助 ───────────────────────────────────

/**
 * 返回密码脱敏的副本（用于 API 响应）。
 * 内存中的原始对象保持明文不变。
 *
 * 脱敏规则：
 *   - password 字段清空（API 永不返回明文），passwordRef 保留以指示凭据存在
 *   - SSH password 同规则
 *   - 调用方通过 passwordRef == null 判断是否有已存储凭据
 */
fun ConnectionConfig.masked(): ConnectionConfig = copy(
    password = "",
    passwordRef = passwordRef,
    ssh = ssh?.copy(
        password = null,
        passwordRef = ssh.passwordRef
    )
)
