package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesCsvImportAdapter
import com.easydb.common.TimeSeriesCsvImportConfig
import com.easydb.common.TimeSeriesWriteRequest
import com.easydb.common.TimeSeriesWriteRow
import com.easydb.common.TimeSeriesWriteSnapshot

class TdengineTimeSeriesCsvImportAdapter(
    private val writes: TdengineTimeSeriesDataWriteAdapter
) : TimeSeriesCsvImportAdapter {
    override fun inspectImportTarget(session: DatabaseSession, database: String, config: TimeSeriesCsvImportConfig) =
        writes.inspectWriteTarget(session, database, config.asWriteRequest(emptyList(), emptyList()))

    override fun prepareImportTarget(
        session: DatabaseSession,
        database: String,
        snapshot: TimeSeriesWriteSnapshot,
        config: TimeSeriesCsvImportConfig
    ) = writes.prepareCsvTarget(session, database, snapshot, config.asWriteRequest(emptyList(), emptyList()))

    override fun buildImportPlan(
        database: String,
        snapshot: TimeSeriesWriteSnapshot,
        config: TimeSeriesCsvImportConfig,
        columns: List<String>,
        rows: List<TimeSeriesWriteRow>
    ) = writes.buildCsvWritePlan(database, snapshot, config.asWriteRequest(columns, rows))

    override fun executeImportPlan(session: DatabaseSession, plan: com.easydb.common.TimeSeriesWritePlan) =
        writes.executeWritePlan(session, plan)

    private fun TimeSeriesCsvImportConfig.asWriteRequest(columns: List<String>, rows: List<TimeSeriesWriteRow>) =
        TimeSeriesWriteRequest(targetKind, table, stableName, columns, rows, tagValues)
}
