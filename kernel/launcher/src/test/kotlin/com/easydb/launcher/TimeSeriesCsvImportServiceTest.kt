package com.easydb.launcher

import com.easydb.common.*
import java.lang.reflect.Proxy
import java.nio.charset.Charset
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TimeSeriesCsvImportServiceTest {
    private val snapshot = TimeSeriesWriteSnapshot(
        "power", TimeSeriesWriteTargetKind.BASIC_TABLE, "events",
        columns = listOf(
            TimeSeriesLifecycleField("ts", "TIMESTAMP", primaryTimestamp = true),
            TimeSeriesLifecycleField("message", "VARCHAR", 128)
        ),
        fingerprint = "target-v1"
    )

    @Test
    fun `preview parses BOM quotes delimiters and embedded newlines as logical records`() {
        val dir = Files.createTempDirectory("easydb-csv-test").toFile()
        val file = dir.resolve("quoted.csv")
        file.writeText("\uFEFFts,message\r\n\"2026-07-22 10:00:00\",\"hello,\nworld\"\r\n", Charsets.UTF_8)
        val preview = service(dir).preview(session(), "power", config(file.path))

        assertEquals(CsvEncoding.UTF8, preview.encoding)
        assertEquals(CsvDelimiter.COMMA, preview.delimiter)
        assertEquals(listOf("ts", "message"), preview.headers)
        assertEquals(1, preview.rows.size)
        assertEquals(1L, preview.rows.single().recordNumber)
        assertEquals("hello,\nworld", preview.rows.single().cells[1].rawValue)
        assertTrue(preview.blockingErrors.isEmpty())
    }

    @Test
    fun `preview supports explicit GB18030 and semicolon while blocking forged mappings`() {
        val dir = Files.createTempDirectory("easydb-csv-gb").toFile()
        val file = dir.resolve("gb.csv")
        file.writeText("ts;message\r\n2026-07-22 10:00:00;上海\r\n", Charset.forName("GB18030"))
        val preview = service(dir).preview(session(), "power", config(file.path).copy(encoding = CsvEncoding.GB18030, delimiter = CsvDelimiter.SEMICOLON))
        assertEquals("上海", preview.rows.single().cells[1].rawValue)

        val invalid = config(file.path).copy(
            encoding = CsvEncoding.GB18030,
            delimiter = CsvDelimiter.SEMICOLON,
            mappings = listOf(TimeSeriesCsvColumnMapping("message", "message"))
        )
        val error = assertFailsWith<TimeSeriesCsvImportException> {
            val first = service(dir).preview(session(), "power", invalid)
            service(dir).createTask(session(), "power", TimeSeriesCsvImportStartRequest(first.config, first.file, first.target.fingerprint))
        }
        assertEquals("INVALID_CSV_IMPORT", error.code)
    }

    @Test
    fun `empty null marker is a preview blocker and a start blocker`() {
        val dir = Files.createTempDirectory("easydb-csv-null").toFile()
        val file = dir.resolve("simple.csv").apply { writeText("ts,message\n2026-07-22 10:00:00,\n") }
        val preview = service(dir).preview(session(), "power", config(file.path).copy(nullMarker = ""))
        assertTrue(preview.blockingErrors.any { it.contains("NULL") })
        assertFailsWith<TimeSeriesCsvImportException> {
            service(dir).createTask(session(), "power", TimeSeriesCsvImportStartRequest(preview.config, preview.file, preview.target.fingerprint))
        }
    }

    @Test
    fun `file identity and structural mappings are revalidated at start`() {
        val dir = Files.createTempDirectory("easydb-csv-identity").toFile()
        val file = dir.resolve("identity.csv").apply { writeText("ts,message\n2026-07-22 10:00:00,ok\n") }
        val service = service(dir)
        val preview = service.preview(session(), "power", config(file.path))
        file.appendText("2026-07-22 10:00:01,later\n")
        val changed = assertFailsWith<TimeSeriesCsvImportException> {
            service.createTask(session(), "power", TimeSeriesCsvImportStartRequest(preview.config, preview.file, preview.target.fingerprint))
        }
        assertEquals("CSV_IMPORT_CHANGED", changed.code)

        val duplicateHeader = dir.resolve("duplicate.csv").apply { writeText("ts,ts\n1,2\n") }
        assertTrue(service.preview(session(), "power", config(duplicateHeader.path)).blockingErrors.any { it.contains("表头不能重复") })
        val blankHeader = dir.resolve("blank.csv").apply { writeText("ts,\n1,x\n") }
        assertTrue(service.preview(session(), "power", config(blankHeader.path)).blockingErrors.any { it.contains("表头不能为空") })
        val duplicateTarget = dir.resolve("mapping.csv").apply { writeText("ts,message\n1,x\n") }
        val mapped = service.preview(session(), "power", config(duplicateTarget.path).copy(mappings = listOf(
            TimeSeriesCsvColumnMapping("ts", "ts"), TimeSeriesCsvColumnMapping("message", "ts")
        )))
        assertTrue(mapped.blockingErrors.any { it.contains("目标字段最多映射一次") })
    }

    @Test
    fun `invalid utf8 beyond initial decoder buffer is rejected by streaming parser`() {
        val dir = Files.createTempDirectory("easydb-csv-utf8").toFile()
        val file = dir.resolve("invalid.csv")
        file.outputStream().use { output ->
            output.write("ts,message\n2026-07-22 10:00:00,\"".toByteArray())
            output.write(ByteArray(12_000) { 'a'.code.toByte() })
            output.write(byteArrayOf(0xC3.toByte(), 0x28))
            output.write("\"\n".toByteArray())
        }
        assertFailsWith<Exception> { service(dir).preview(session(), "power", config(file.path)) }
    }

    @Test
    fun `empty string remains distinct from null and blank timestamp is rejected`() {
        val dir = Files.createTempDirectory("easydb-csv-empty").toFile()
        val file = dir.resolve("empty.csv").apply { writeText("ts,message\n,\n2026-07-22 10:00:00,NULL\n") }
        val preview = service(dir).preview(session(), "power", config(file.path))
        assertTrue(preview.rows.first().error?.contains("ts") == true)
        assertFalse(preview.rows.first().cells[1].isNull)
        assertTrue(preview.rows[1].cells[1].isNull)

        val emptyAsNull = service(dir).preview(session(), "power", config(file.path).copy(emptyAsNull = true))
        assertTrue(emptyAsNull.rows.first().error?.contains("ts") == true)
        assertTrue(emptyAsNull.rows.first().cells.first().isNull)
    }

    @Test
    fun `execution records success and failed database batches in managed receipt`() {
        val dir = Files.createTempDirectory("easydb-csv-execute").toFile()
        val file = dir.resolve("rows.csv").apply {
            writeText(buildString { appendLine("ts,message"); repeat(501) { appendLine("2026-07-22 10:00:${(it % 60).toString().padStart(2, '0')},row-$it") } })
        }
        val successHarness = executionHarness(dir.resolve("success"), failBatch = false)
        val successPreview = successHarness.service.preview(successHarness.session, "power", config(file.path))
        val successTask = successHarness.service.createTask(successHarness.session, "power", TimeSeriesCsvImportStartRequest(successPreview.config, successPreview.file, successPreview.target.fingerprint))
        successHarness.service.execute(successTask.id, successHarness.session, "power", TimeSeriesCsvImportStartRequest(successPreview.config, successPreview.file, successPreview.target.fingerprint))
        assertEquals(501, successHarness.tasks.get(successTask.id)?.successCount)

        val failureHarness = executionHarness(dir.resolve("failure"), failBatch = true)
        val failurePreview = failureHarness.service.preview(failureHarness.session, "power", config(file.path))
        val failureTask = failureHarness.service.createTask(failureHarness.session, "power", TimeSeriesCsvImportStartRequest(failurePreview.config, failurePreview.file, failurePreview.target.fingerprint))
        failureHarness.service.execute(failureTask.id, failureHarness.session, "power", TimeSeriesCsvImportStartRequest(failurePreview.config, failurePreview.file, failurePreview.target.fingerprint))
        val failed = failureHarness.tasks.get(failureTask.id)!!
        assertEquals(501, failed.failureCount)
        assertTrue(failureHarness.tasks.managedTaskArtifact(failureTask.id)?.readText()?.contains("数据库批次失败") == true)
    }

    @Test
    fun `cancellation after a committed new-child batch preserves child and counts`() {
        val dir = Files.createTempDirectory("easydb-csv-cancel").toFile()
        val file = dir.resolve("child.csv").apply {
            writeText(buildString { appendLine("ts,message"); repeat(501) { appendLine("2026-07-22 10:00:${(it % 60).toString().padStart(2, '0')},row-$it") } })
        }
        val tasks = TaskManager(dir.resolve("managed"))
        var prepared = 0
        var taskId = ""
        lateinit var taskSession: DatabaseSession
        val csv = object : TimeSeriesCsvImportAdapter {
            override fun inspectImportTarget(session: DatabaseSession, database: String, config: TimeSeriesCsvImportConfig) = snapshot.copy(
                targetKind = TimeSeriesWriteTargetKind.NEW_CHILD_TABLE,
                table = config.table,
                stableName = config.stableName
            )
            override fun prepareImportTarget(session: DatabaseSession, database: String, snapshot: TimeSeriesWriteSnapshot, config: TimeSeriesCsvImportConfig) { prepared++ }
            override fun buildImportPlan(database: String, snapshot: TimeSeriesWriteSnapshot, config: TimeSeriesCsvImportConfig, columns: List<String>, rows: List<TimeSeriesWriteRow>) = TimeSeriesWritePlan("hidden", "hidden", emptyList(), rows.size, false)
            override fun executeImportPlan(session: DatabaseSession, plan: TimeSeriesWritePlan) { tasks.cancel(taskId) }
        }
        val connection = object : ConnectionAdapter {
            override fun testConnection(config: ConnectionConfig) = ConnectionTestResult(true, "ok")
            override fun open(config: ConnectionConfig) = taskSession
            override fun close(session: DatabaseSession) = Unit
        }
        val database = Proxy.newProxyInstance(DatabaseAdapter::class.java.classLoader, arrayOf(DatabaseAdapter::class.java)) { _, method, _ -> when (method.name) {
            "dbType" -> DbType.TDENGINE
            "capabilities" -> DatabaseCapabilities(supportsTimeSeriesCsvImport = true)
            "timeSeriesCsvImportAdapter" -> csv
            "connectionAdapter" -> connection
            else -> defaultValue(method.returnType)
        } } as DatabaseAdapter
        taskSession = session()
        val service = TimeSeriesCsvImportService(DatabaseAdapterRegistry(listOf(database)), tasks)
        val config = config(file.path).copy(targetKind = TimeSeriesWriteTargetKind.NEW_CHILD_TABLE, table = "d1", stableName = "meters")
        val preview = service.preview(taskSession, "power", config)
        val task = service.createTask(taskSession, "power", TimeSeriesCsvImportStartRequest(preview.config, preview.file, preview.target.fingerprint))
        taskId = task.id
        service.execute(task.id, taskSession, "power", TimeSeriesCsvImportStartRequest(preview.config, preview.file, preview.target.fingerprint))

        val cancelled = tasks.get(task.id)!!
        assertEquals("cancelled", cancelled.status)
        assertEquals(501, cancelled.successCount)
        assertEquals(1, prepared)
        assertEquals("true", cancelled.payload?.get("createdChildTable"))
    }

    private fun config(path: String) = TimeSeriesCsvImportConfig(path, TimeSeriesWriteTargetKind.BASIC_TABLE, "events")

    private fun service(dir: java.io.File): TimeSeriesCsvImportService {
        val csv = object : TimeSeriesCsvImportAdapter {
            override fun inspectImportTarget(session: DatabaseSession, database: String, config: TimeSeriesCsvImportConfig) = snapshot
            override fun prepareImportTarget(session: DatabaseSession, database: String, snapshot: TimeSeriesWriteSnapshot, config: TimeSeriesCsvImportConfig) = Unit
            override fun buildImportPlan(database: String, snapshot: TimeSeriesWriteSnapshot, config: TimeSeriesCsvImportConfig, columns: List<String>, rows: List<TimeSeriesWriteRow>): TimeSeriesWritePlan {
                require("ts" in columns) { "缺少主时间戳" }
                rows.forEach { row -> require(row.cells.first { it.name == "ts" }.value?.isNotBlank() == true) { "字段 ts 不能为空" } }
                return TimeSeriesWritePlan("hidden", "hidden", emptyList(), rows.size, false)
            }
            override fun executeImportPlan(session: DatabaseSession, plan: TimeSeriesWritePlan) = Unit
        }
        val database = Proxy.newProxyInstance(DatabaseAdapter::class.java.classLoader, arrayOf(DatabaseAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "dbType" -> DbType.TDENGINE
                "capabilities" -> DatabaseCapabilities(supportsTimeSeriesCsvImport = true)
                "timeSeriesCsvImportAdapter" -> csv
                else -> defaultValue(method.returnType)
            }
        } as DatabaseAdapter
        return TimeSeriesCsvImportService(DatabaseAdapterRegistry(listOf(database)), TaskManager(dir.resolve("managed")))
    }

    private data class Harness(val service: TimeSeriesCsvImportService, val tasks: TaskManager, val session: DatabaseSession)

    private fun executionHarness(dir: java.io.File, failBatch: Boolean): Harness {
        val tasks = TaskManager(dir.resolve("managed"))
        lateinit var taskSession: DatabaseSession
        val csv = object : TimeSeriesCsvImportAdapter {
            override fun inspectImportTarget(session: DatabaseSession, database: String, config: TimeSeriesCsvImportConfig) = snapshot
            override fun prepareImportTarget(session: DatabaseSession, database: String, snapshot: TimeSeriesWriteSnapshot, config: TimeSeriesCsvImportConfig) = Unit
            override fun buildImportPlan(database: String, snapshot: TimeSeriesWriteSnapshot, config: TimeSeriesCsvImportConfig, columns: List<String>, rows: List<TimeSeriesWriteRow>) =
                TimeSeriesWritePlan("hidden", "hidden", emptyList(), rows.size, false)
            override fun executeImportPlan(session: DatabaseSession, plan: TimeSeriesWritePlan) { if (failBatch) error("rejected") }
        }
        val connection = object : ConnectionAdapter {
            override fun testConnection(config: ConnectionConfig) = ConnectionTestResult(true, "ok")
            override fun open(config: ConnectionConfig) = taskSession
            override fun close(session: DatabaseSession) = Unit
        }
        val database = Proxy.newProxyInstance(DatabaseAdapter::class.java.classLoader, arrayOf(DatabaseAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "dbType" -> DbType.TDENGINE
                "capabilities" -> DatabaseCapabilities(supportsTimeSeriesCsvImport = true)
                "timeSeriesCsvImportAdapter" -> csv
                "connectionAdapter" -> connection
                else -> defaultValue(method.returnType)
            }
        } as DatabaseAdapter
        taskSession = session()
        return Harness(TimeSeriesCsvImportService(DatabaseAdapterRegistry(listOf(database)), tasks), tasks, taskSession)
    }

    private fun session() = object : DatabaseSession {
        override val connectionId = "test"
        override val config = ConnectionConfig(name = "test", dbType = "tdengine")
        override fun isValid() = true
        override fun close() = Unit
        override fun getJdbcConnection(): Connection = error("not used")
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null
    }
}
