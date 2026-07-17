package com.easydb.launcher

import com.easydb.common.CompareAdapter
import com.easydb.common.CompareConfig
import com.easydb.common.CompareResult
import com.easydb.common.SessionPair
import com.easydb.common.SyncAdapter
import com.easydb.common.SyncConfig
import com.easydb.common.SyncPreview
import com.easydb.common.TaskReporter
import com.easydb.common.TaskResult
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PairFeatureAdapterRegistryTest {
    @Test
    fun `sync registry resolves only registered same-database pairs`() {
        val mysql = FakeSyncAdapter()
        val dameng = FakeSyncAdapter()
        val registry = SyncAdapterRegistry(
            listOf(
                RegisteredSyncAdapter("mysql", "mysql", mysql),
                RegisteredSyncAdapter("dameng", "dameng", dameng)
            )
        )

        assertSame(mysql, registry.get("MYSQL", "mysql"))
        assertSame(dameng, registry.get("dm", "DAMENG"))
        assertTrue(registry.supports("dameng", "dameng"))
        assertFailsWith<IllegalArgumentException> { registry.get("mysql", "dameng") }
        assertFailsWith<IllegalArgumentException> { registry.get("dameng", "mysql") }
    }

    @Test
    fun `compare registry resolves only registered same-database pairs`() {
        val mysql = FakeCompareAdapter()
        val dameng = FakeCompareAdapter()
        val registry = CompareAdapterRegistry(
            listOf(
                RegisteredCompareAdapter("mysql", "mysql", mysql),
                RegisteredCompareAdapter("dameng", "dameng", dameng)
            )
        )

        assertSame(mysql, registry.get("mysql", "MYSQL"))
        assertSame(dameng, registry.get("DAMENG", "dm"))
        assertFailsWith<IllegalArgumentException> { registry.get("mysql", "dameng") }
    }

    private class FakeSyncAdapter : SyncAdapter {
        override fun preview(config: SyncConfig, sessions: SessionPair): SyncPreview = error("not used")
        override fun execute(config: SyncConfig, sessions: SessionPair, reporter: TaskReporter): TaskResult = error("not used")
    }

    private class FakeCompareAdapter : CompareAdapter {
        override fun compare(config: CompareConfig, sessions: SessionPair): CompareResult = error("not used")
    }
}
