package com.easydb.drivers.tdengine

import com.easydb.common.DbType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdengineDatabaseAdapterTest {
    private val adapter = TdengineDatabaseAdapter()

    @Test
    fun `declares only verified phase one capabilities`() {
        val capabilities = adapter.capabilities()

        assertEquals(DbType.TDENGINE, adapter.dbType())
        assertFalse(capabilities.supportsTransactions)
        assertTrue(capabilities.supportsSsh)
        assertTrue(capabilities.supportsSsl)
        assertFalse(capabilities.supportsStoredProcedures)
        assertFalse(capabilities.supportsLogicalExport)
        assertFalse(capabilities.supportsSqlFileImport)
        assertFalse(capabilities.supportsLogicalBackup)
        assertFalse(capabilities.supportsLogicalRestore)
        assertTrue(capabilities.supportsPagedMetadata)
        assertTrue(capabilities.supportsTimeSeriesObjectCreate)
        assertTrue(capabilities.supportsTimeSeriesQuery)
        assertTrue(capabilities.supportsTimeSeriesLifecycle)
        assertTrue(capabilities.supportsTimeSeriesObjectDelete)
        assertFalse(capabilities.supportsTableCreate)
        assertFalse(capabilities.supportsTableRename)
        assertFalse(capabilities.supportsTableDrop)
        assertFalse(capabilities.supportsTableTruncate)
        assertFalse(capabilities.supportsRowEdit)
        assertTrue(adapter.timeSeriesObjectAdapter() is TdengineTimeSeriesObjectAdapter)
        assertTrue(adapter.timeSeriesQueryAdapter() is TdengineTimeSeriesQueryAdapter)
        assertTrue(adapter.timeSeriesLifecycleAdapter() is TdengineTimeSeriesLifecycleAdapter)
    }

    @Test
    fun `does not expose unregistered pair or procedure adapters`() {
        assertFailsWith<UnsupportedOperationException> { adapter.migrationAdapter() }
        assertFailsWith<UnsupportedOperationException> { adapter.syncAdapter() }
        assertFailsWith<UnsupportedOperationException> { adapter.procedureAdapter() }
    }
}
