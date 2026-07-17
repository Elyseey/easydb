package com.easydb.common

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TaskManagerTest {
    @Test
    fun `task endpoint snapshots survive persistence`() {
        val storageDir = Files.createTempDirectory("easydb-task-manager").toFile()
        try {
            val source = TaskEndpointSnapshot(
                connectionId = "source-id",
                connectionName = "生产 MySQL",
                dbType = "mysql",
                host = "10.0.0.8",
                port = 3306,
                database = "energy"
            )
            val target = TaskEndpointSnapshot(
                connectionId = "target-id",
                connectionName = "达梦测试",
                dbType = "dameng",
                host = "10.0.0.9",
                port = 5236,
                database = "ENERGY"
            )

            val created = TaskManager(storageDir).createTask(
                name = "迁移 energy → ENERGY",
                type = "migration",
                sourceEndpoint = source,
                targetEndpoint = target
            )
            val restored = TaskManager(storageDir).get(created.id)

            assertNotNull(restored)
            assertNotNull(restored.createdAt)
            assertEquals(source, restored.sourceEndpoint)
            assertEquals(target, restored.targetEndpoint)
        } finally {
            storageDir.deleteRecursively()
        }
    }

    @Test
    fun `legacy task without endpoint snapshots remains readable`() {
        val storageDir = Files.createTempDirectory("easydb-legacy-task").toFile()
        try {
            storageDir.resolve("tasks.json").writeText(
                """[{"id":"legacy","name":"迁移 old → old","type":"migration","status":"completed","progress":100}]"""
            )

            val restored = TaskManager(storageDir).get("legacy")

            assertNotNull(restored)
            assertNull(restored.sourceEndpoint)
            assertNull(restored.targetEndpoint)
            assertNull(restored.createdAt)
        } finally {
            storageDir.deleteRecursively()
        }
    }
}
