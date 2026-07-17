package com.easydb.drivers.dameng

import com.easydb.common.*

class DamengDatabaseAdapter : DatabaseAdapter {

    private val connectionAdapter = DamengConnectionAdapter()
    private val metadataAdapter = DamengMetadataAdapter()
    private val dialectAdapter = DamengDialectAdapter()
    private val syncAdapter = DamengSyncAdapter()
    private val procedureAdapter = DamengProcedureAdapter()
    private val logicalBackupAdapter = DamengLogicalBackupAdapter()

    override fun dbType(): DbType = DbType.DAMENG

    override fun capabilities(): DatabaseCapabilities = DatabaseCapabilities(
        supportsTransactions = true,
        supportsSsh = true,
        supportsSsl = false,
        supportsAlterDatabaseCharset = false,
        supportsViews = true,
        supportsStoredProcedures = true,
        supportsTriggers = true,
        supportsLogicalExport = true,
        supportsSqlFileImport = true,
        supportsLogicalBackup = true,
        supportsLogicalRestore = true,
        supportsOverwriteRestore = false
    )

    override fun connectionAdapter(): ConnectionAdapter = connectionAdapter

    override fun dialectAdapter(): DialectAdapter = dialectAdapter

    override fun metadataAdapter(): MetadataAdapter = metadataAdapter

    override fun syncAdapter(): SyncAdapter = syncAdapter

    override fun migrationAdapter(): MigrationAdapter = unsupported("数据迁移")

    override fun procedureAdapter(): ProcedureAdapter = procedureAdapter

    override fun logicalBackupAdapter(): LogicalBackupAdapter = logicalBackupAdapter

    private fun unsupported(feature: String): Nothing {
        throw UnsupportedOperationException("达梦暂不支持 $feature")
    }
}
