package com.easydb.drivers.tdengine

import com.easydb.common.ConnectionAdapter
import com.easydb.common.DatabaseAdapter
import com.easydb.common.DatabaseCapabilities
import com.easydb.common.DbType
import com.easydb.common.DialectAdapter
import com.easydb.common.MetadataAdapter
import com.easydb.common.MigrationAdapter
import com.easydb.common.ProcedureAdapter
import com.easydb.common.SyncAdapter
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesObjectAdapter

class TdengineDatabaseAdapter : DatabaseAdapter {
    private val connection = TdengineConnectionAdapter()
    private val metadata = TdengineMetadataAdapter()
    private val dialect = TdengineDialectAdapter()
    private val timeSeriesObjects = TdengineTimeSeriesObjectAdapter(metadata, dialect)

    override fun dbType(): DbType = DbType.TDENGINE

    override fun capabilities(): DatabaseCapabilities = DatabaseCapabilities(
        supportsTransactions = false,
        supportsSsh = true,
        supportsSsl = true,
        supportsAlterDatabaseCharset = false,
        supportsViews = false,
        supportsStoredProcedures = false,
        supportsTriggers = false,
        supportsLogicalExport = false,
        supportsSqlFileImport = false,
        supportsLogicalBackup = false,
        supportsLogicalRestore = false,
        supportsOverwriteRestore = false,
        supportsTimeSeriesObjectCreate = true,
        supportsTableCreate = false,
        supportsTableRename = false,
        supportsTableDrop = false,
        supportsTableTruncate = false,
        supportsRowEdit = false
    )

    override fun connectionAdapter(): ConnectionAdapter = connection
    override fun metadataAdapter(): MetadataAdapter = metadata
    override fun dialectAdapter(): DialectAdapter = dialect
    override fun timeSeriesMetadataAdapter(): TimeSeriesMetadataAdapter = metadata
    override fun timeSeriesObjectAdapter(): TimeSeriesObjectAdapter = timeSeriesObjects
    override fun syncAdapter(): SyncAdapter = unsupported("数据同步")
    override fun migrationAdapter(): MigrationAdapter = unsupported("数据迁移")
    override fun procedureAdapter(): ProcedureAdapter = unsupported("存储过程")

    private fun unsupported(feature: String): Nothing =
        throw UnsupportedOperationException("TDengine 暂不支持$feature")
}
