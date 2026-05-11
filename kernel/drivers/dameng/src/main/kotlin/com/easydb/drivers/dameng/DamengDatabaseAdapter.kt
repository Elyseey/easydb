package com.easydb.drivers.dameng

import com.easydb.common.*

class DamengDatabaseAdapter : DatabaseAdapter {

    private val connectionAdapter = DamengConnectionAdapter()
    private val metadataAdapter = DamengMetadataAdapter()
    private val dialectAdapter = DamengDialectAdapter()

    override fun dbType(): DbType = DbType.DAMENG

    override fun capabilities(): DatabaseCapabilities = DatabaseCapabilities(
        supportsTransactions = true,
        supportsSsh = true,
        supportsSsl = false,
        supportsViews = true,
        supportsStoredProcedures = false,
        supportsTriggers = false
    )

    override fun connectionAdapter(): ConnectionAdapter = connectionAdapter

    override fun dialectAdapter(): DialectAdapter = dialectAdapter

    override fun metadataAdapter(): MetadataAdapter = metadataAdapter

    override fun syncAdapter(): SyncAdapter = unsupported("数据同步")

    override fun migrationAdapter(): MigrationAdapter = unsupported("数据迁移")

    override fun procedureAdapter(): ProcedureAdapter = unsupported("存储过程")

    private fun unsupported(feature: String): Nothing {
        throw UnsupportedOperationException("达梦暂不支持 $feature")
    }
}
