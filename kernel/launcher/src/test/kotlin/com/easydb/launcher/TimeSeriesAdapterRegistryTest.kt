package com.easydb.launcher

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimeSeriesAdapterRegistryTest {

    @Test
    fun `only tdengine exposes the optional time series metadata adapter`() {
        assertNull(ServiceRegistry.mysqlAdapter.timeSeriesMetadataAdapter())
        assertNull(ServiceRegistry.damengAdapter.timeSeriesMetadataAdapter())
        assertNotNull(ServiceRegistry.tdengineAdapter.timeSeriesMetadataAdapter())
    }
}
