package com.easydb.launcher

import com.easydb.common.MigrationAdapter
import com.easydb.common.MigrationConfig
import com.easydb.common.MigrationPreview
import com.easydb.common.SessionPair
import com.easydb.common.TaskReporter
import com.easydb.common.TaskResult
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MigrationAdapterRegistryTest {

    @Test
    fun `returns adapter by normalized source and target db types`() {
        val adapter = FakeMigrationAdapter()
        val registry = MigrationAdapterRegistry(
            listOf(RegisteredMigrationAdapter("mysql", "dameng", adapter))
        )

        assertSame(adapter, registry.get("MYSQL", "dm"))
        assertTrue(registry.supports("mysql", "DAMENG"))
    }

    @Test
    fun `throws clear error for unsupported pair`() {
        val registry = MigrationAdapterRegistry(emptyList())

        assertFailsWith<IllegalArgumentException> {
            registry.get("dameng", "mysql")
        }
    }

    private class FakeMigrationAdapter : MigrationAdapter {
        override fun preview(config: MigrationConfig, sessions: SessionPair): MigrationPreview =
            error("not used")

        override fun execute(config: MigrationConfig, sessions: SessionPair, reporter: TaskReporter): TaskResult =
            error("not used")
    }
}
