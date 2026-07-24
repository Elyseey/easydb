package com.easydb.launcher

import com.easydb.common.*
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser

class TimeSeriesCsvImportException(val code: String, override val message: String) : RuntimeException(message)

class TimeSeriesCsvImportService(
    private val registry: DatabaseAdapterRegistry,
    private val tasks: TaskManager
) {
    fun preview(session: DatabaseSession, database: String, config: TimeSeriesCsvImportConfig): TimeSeriesCsvPreview {
        val adapter = adapter(session)
        val file = inspectFile(config.filePath)
        val resolvedEncoding = resolveEncoding(file, config.encoding)
        val resolvedDelimiter = resolveDelimiter(file, resolvedEncoding, config.delimiter)
        val snapshot = adapter.inspectImportTarget(session, database, config)
        openRecords(file, resolvedEncoding, resolvedDelimiter).use { records ->
            val headers = records.next() ?: invalid("CSV 必须包含表头")
            val mappings = effectiveMappings(headers, snapshot, config.mappings)
            val blocking = mappingErrors(headers, snapshot, mappings).toMutableList()
            if (config.nullMarker.isEmpty()) blocking += "NULL 标记不能为空"
            val effective = config.copy(encoding = resolvedEncoding, delimiter = resolvedDelimiter, mappings = mappings)
            val rows = mutableListOf<TimeSeriesCsvPreviewRow>()
            while (rows.size < TimeSeriesCsvImportLimits.PREVIEW_RECORDS) {
                val values = records.next() ?: break
                rows += previewRow(records.recordNumber - 1, headers, values, effective, snapshot, adapter, database)
            }
            return TimeSeriesCsvPreview(effective, file, resolvedEncoding, resolvedDelimiter, headers, mappings, rows, snapshot, blocking)
        }
    }

    fun createTask(session: DatabaseSession, database: String, request: TimeSeriesCsvImportStartRequest): TaskInfo {
        validateStart(session, database, request)
        return tasks.createTask("CSV 导入 · ${request.config.table}", "timeseries_csv_import")
    }

    fun execute(taskId: String, sourceSession: DatabaseSession, database: String, request: TimeSeriesCsvImportStartRequest) {
        val started = System.currentTimeMillis()
        val reporter = tasks.createReporter(taskId)
        val databaseAdapter = registry.get(sourceSession.config.dbType)
        var dedicated: DatabaseSession? = null
        var success = 0L
        var failure = 0L
        var unprocessed = 0L
        var receipt: File? = null
        var receiptCandidate: File? = null
        var createdChild = false
        try {
            val taskSession = databaseAdapter.connectionAdapter().open(sourceSession.config)
            dedicated = taskSession
            val adapter = requireNotNull(databaseAdapter.timeSeriesCsvImportAdapter())
            val currentFile = inspectFile(request.config.filePath)
            requireSameFile(request.expectedFile, currentFile)
            val encoding = resolveEncoding(currentFile, request.config.encoding)
            val delimiter = resolveDelimiter(currentFile, encoding, request.config.delimiter)
            val config = request.config.copy(encoding = encoding, delimiter = delimiter)
            val snapshot = adapter.inspectImportTarget(taskSession, database, config)
            if (snapshot.fingerprint != request.expectedTargetFingerprint) changed("目标结构已变化，请重新预览")

            openRecords(currentFile, encoding, delimiter).use { records ->
                val headers = records.next() ?: invalid("CSV 必须包含表头")
                val mappings = effectiveMappings(headers, snapshot, config.mappings)
                val errors = mappingErrors(headers, snapshot, mappings).toMutableList()
                if (config.nullMarker.isEmpty()) errors += "NULL 标记不能为空"
                if (errors.isNotEmpty()) invalid(errors.joinToString("；"))
                val effective = config.copy(mappings = mappings)
                // Must happen only after file identity, target fingerprint, header, and mappings are revalidated.
                adapter.prepareImportTarget(taskSession, database, snapshot, effective)
                createdChild = effective.targetKind == TimeSeriesWriteTargetKind.NEW_CHILD_TABLE
                val columns = mappings.mapNotNull { it.targetColumn }
                val receiptFile = tasks.managedExportFile("${taskId}-errors.csv")
                receiptCandidate = receiptFile
                val writer = ErrorReceipt(receiptFile, headers)
                writer.use {
                    val batch = mutableListOf<ImportRecord>()
                    var averageWidth = 128L
                    var limitReached = false
                    while (true) {
                        if (reporter.isCancelled()) break
                        val values = records.next() ?: break
                        val recordNumber = records.recordNumber - 1
                        if (recordNumber > TimeSeriesCsvImportLimits.MAX_RECORDS) {
                            unprocessed++
                            limitReached = true
                            continue
                        }
                        val converted = convert(headers, values, effective, snapshot, adapter, database)
                        if (converted.error != null) {
                            failure++
                            writer.write(recordNumber, converted.error, values)
                        } else {
                            val row = requireNotNull(converted.row)
                            batch += ImportRecord(recordNumber, values, row)
                            averageWidth = (averageWidth * 7 + values.sumOf { value -> value.length.toLong() }) / 8
                        }
                        val batchSize = adaptiveBatchSize(columns.size, averageWidth)
                        if (batch.size >= batchSize) {
                            val result = submitBatch(adapter, taskSession, database, snapshot, effective, columns, batch, writer, reporter)
                            success += result.first
                            failure += result.second
                            batch.clear()
                        }
                        reporter.onProgress(progress(currentFile.size, records.bytesRead), "成功 $success，失败 $failure")
                    }
                    if (!reporter.isCancelled() && batch.isNotEmpty()) {
                        val result = submitBatch(adapter, taskSession, database, snapshot, effective, columns, batch, writer, reporter)
                        success += result.first
                        failure += result.second
                    } else if (reporter.isCancelled()) {
                        unprocessed += batch.size
                    }
                    if (limitReached) reporter.onLog("WARN", "超过 ${TimeSeriesCsvImportLimits.MAX_RECORDS} 条记录的内容未导入")
                    if (writer.failedRows > 0) receipt = writer.file
                }
            }
            val result = TaskResult(
                success = !reporter.isCancelled(),
                successCount = success.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                failureCount = failure.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                skippedCount = unprocessed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                payload = buildMap {
                    put("processedRows", (success + failure).toString())
                    put("unprocessedRows", if (reporter.isCancelled()) "unknown_after_cancel" else unprocessed.toString())
                    put("targetTable", request.config.table)
                    request.config.stableName?.let { put("stableName", it) }
                    put("createdChildTable", createdChild.toString())
                    receipt?.let { put("filePath", it.absolutePath) }
                }
            )
            if (reporter.isCancelled()) tasks.markCancelled(taskId, System.currentTimeMillis() - started, result)
            else tasks.markCompleted(taskId, System.currentTimeMillis() - started, result)
        } catch (error: Exception) {
            receipt = receipt ?: receiptCandidate?.takeIf { it.exists() && it.isFile }
            reporter.onLog("ERROR", error.message ?: "CSV 导入失败")
            tasks.markFailed(taskId, error.message ?: "CSV 导入失败", TaskResult(
                success = false,
                successCount = success.toInt(), failureCount = failure.toInt(), skippedCount = unprocessed.toInt(),
                payload = buildMap {
                    put("targetTable", request.config.table)
                    request.config.stableName?.let { put("stableName", it) }
                    put("createdChildTable", createdChild.toString())
                    receipt?.let { put("filePath", it.absolutePath) }
                }
            ))
        } finally {
            dedicated?.close()
        }
    }

    private fun validateStart(session: DatabaseSession, database: String, request: TimeSeriesCsvImportStartRequest) {
        val adapter = adapter(session)
        val file = inspectFile(request.config.filePath)
        requireSameFile(request.expectedFile, file)
        val encoding = resolveEncoding(file, request.config.encoding)
        val delimiter = resolveDelimiter(file, encoding, request.config.delimiter)
        val snapshot = adapter.inspectImportTarget(session, database, request.config)
        if (snapshot.fingerprint != request.expectedTargetFingerprint) changed("目标结构已变化，请重新预览")
        openRecords(file, encoding, delimiter).use { records ->
            val headers = records.next() ?: invalid("CSV 必须包含表头")
            val mappings = effectiveMappings(headers, snapshot, request.config.mappings)
            val errors = mappingErrors(headers, snapshot, mappings).toMutableList()
            if (request.config.nullMarker.isEmpty()) errors += "NULL 标记不能为空"
            if (errors.isNotEmpty()) invalid(errors.joinToString("；"))
        }
    }

    private fun adapter(session: DatabaseSession): TimeSeriesCsvImportAdapter {
        val db = registry.get(session.config.dbType)
        if (!db.capabilities().supportsTimeSeriesCsvImport) unsupported(session)
        return db.timeSeriesCsvImportAdapter() ?: unsupported(session)
    }

    private fun effectiveMappings(headers: List<String>, snapshot: TimeSeriesWriteSnapshot, submitted: List<TimeSeriesCsvColumnMapping>): List<TimeSeriesCsvColumnMapping> {
        if (submitted.isNotEmpty()) return submitted
        val targets = snapshot.columns.map { it.name }.toSet()
        return headers.map { TimeSeriesCsvColumnMapping(it, it.takeIf(targets::contains)) }
    }

    private fun mappingErrors(headers: List<String>, snapshot: TimeSeriesWriteSnapshot, mappings: List<TimeSeriesCsvColumnMapping>): List<String> = buildList {
        if (headers.any { it.isEmpty() }) add("表头不能为空")
        if (headers.distinct().size != headers.size) add("表头不能重复")
        if (mappings.map { it.sourceHeader }.distinct().size != mappings.size || mappings.any { it.sourceHeader !in headers }) add("源字段映射无效")
        val targets = mappings.mapNotNull { it.targetColumn }
        if (targets.distinct().size != targets.size) add("一个目标字段最多映射一次")
        val known = snapshot.columns.map { it.name }.toSet()
        if (targets.any { it !in known }) add("映射包含不存在的目标字段")
        val timestamp = snapshot.columns.firstOrNull { it.primaryTimestamp }?.name
        if (timestamp == null || timestamp !in targets) add("缺少主时间戳映射")
    }

    private data class Converted(val row: TimeSeriesWriteRow?, val error: String?)
    private data class ImportRecord(val number: Long, val raw: List<String>, val row: TimeSeriesWriteRow)

    private fun convert(headers: List<String>, values: List<String>, config: TimeSeriesCsvImportConfig, snapshot: TimeSeriesWriteSnapshot, adapter: TimeSeriesCsvImportAdapter, database: String): Converted {
        if (values.size != headers.size) return Converted(null, "字段数 ${values.size} 与表头 ${headers.size} 不一致")
        val byHeader = headers.zip(values).toMap()
        val cells = config.mappings.mapNotNull { mapping ->
            mapping.targetColumn?.let { target ->
                val raw = requireNotNull(byHeader[mapping.sourceHeader])
                val isNull = raw == config.nullMarker || (config.emptyAsNull && raw.isEmpty())
                TimeSeriesWriteCell(target, if (isNull) null else raw, isNull)
            }
        }
        val row = TimeSeriesWriteRow(cells)
        return try {
            adapter.buildImportPlan(database, snapshot, config, cells.map { it.name }, listOf(row))
            Converted(row, null)
        } catch (error: Exception) {
            Converted(null, error.message ?: "类型校验失败")
        }
    }

    private fun previewRow(number: Long, headers: List<String>, values: List<String>, config: TimeSeriesCsvImportConfig, snapshot: TimeSeriesWriteSnapshot, adapter: TimeSeriesCsvImportAdapter, database: String): TimeSeriesCsvPreviewRow {
        val converted = convert(headers, values, config, snapshot, adapter, database)
        val errorTarget = config.mappings.firstOrNull { mapping ->
            mapping.targetColumn?.let { converted.error?.contains(it) == true } == true
        }?.targetColumn
        val targetByHeader = config.mappings.associate { it.sourceHeader to it.targetColumn }
        val cells = headers.mapIndexed { index, header ->
            val raw = values.getOrElse(index) { "" }
            val isNull = raw == config.nullMarker || (config.emptyAsNull && raw.isEmpty())
            val cellError = converted.error?.takeIf { errorTarget != null && targetByHeader[header] == errorTarget }
            TimeSeriesCsvPreviewCell(header, raw, if (isNull) null else raw, isNull, cellError)
        }
        return TimeSeriesCsvPreviewRow(number, cells, converted.error)
    }

    private fun submitBatch(adapter: TimeSeriesCsvImportAdapter, session: DatabaseSession, database: String, snapshot: TimeSeriesWriteSnapshot, config: TimeSeriesCsvImportConfig, columns: List<String>, batch: List<ImportRecord>, receipt: ErrorReceipt, reporter: TaskReporter): Pair<Long, Long> = try {
        val plan = adapter.buildImportPlan(database, snapshot, config, columns, batch.map { it.row })
        adapter.executeImportPlan(session, plan)
        batch.size.toLong() to 0L
    } catch (error: Exception) {
        val reason = "数据库批次失败（未重试以避免原子性不明确时重复写入）：${error.message ?: "未知错误"}"
        reporter.onLog("ERROR", reason)
        batch.forEach { receipt.write(it.number, reason, it.raw) }
        0L to batch.size.toLong()
    }

    private fun adaptiveBatchSize(columns: Int, averageWidth: Long): Int {
        val estimated = max(64L, averageWidth + columns * 16L)
        return (2_000_000L / estimated).toInt().coerceIn(TimeSeriesCsvImportLimits.MIN_BATCH_ROWS, TimeSeriesCsvImportLimits.MAX_BATCH_ROWS)
    }

    private fun inspectFile(path: String): CsvFileIdentity {
        val file = File(path)
        if (!file.isAbsolute || !file.exists() || !file.isFile) invalid("CSV 文件不存在或不是普通文件")
        val canonical = file.canonicalFile
        if (canonical.length() > TimeSeriesCsvImportLimits.MAX_FILE_BYTES) invalid("CSV 文件不能超过 1 GB")
        return CsvFileIdentity(canonical.path, canonical.name, canonical.length(), canonical.lastModified())
    }

    private fun requireSameFile(expected: CsvFileIdentity, actual: CsvFileIdentity) {
        if (expected != actual) changed("CSV 文件已变化，请重新预览")
    }

    private fun resolveEncoding(file: CsvFileIdentity, requested: CsvEncoding): CsvEncoding = when (requested) {
        CsvEncoding.GB18030 -> CsvEncoding.GB18030
        CsvEncoding.UTF8, CsvEncoding.AUTO -> {
            // AUTO intentionally means UTF-8/UTF-8 BOM. GB18030 requires explicit user selection.
            validateDecoding(file, StandardCharsets.UTF_8)
            CsvEncoding.UTF8
        }
    }

    private fun validateDecoding(file: CsvFileIdentity, charset: java.nio.charset.Charset) {
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        try { InputStreamReader(File(file.canonicalPath).inputStream().buffered(), decoder).use { reader -> val buffer = CharArray(8192); reader.read(buffer) } }
        catch (_: Exception) { invalid("文件不是有效 UTF-8；如来自 Windows/Excel，请手动选择 GB18030") }
    }

    private fun resolveDelimiter(file: CsvFileIdentity, encoding: CsvEncoding, requested: CsvDelimiter): CsvDelimiter {
        if (requested != CsvDelimiter.AUTO) return requested
        val charset = if (encoding == CsvEncoding.GB18030) charset("GB18030") else StandardCharsets.UTF_8
        val text = File(file.canonicalPath).inputStream().buffered().reader(charset).use { reader ->
            val buffer = CharArray(65_536)
            val count = reader.read(buffer).coerceAtLeast(0)
            String(buffer, 0, count)
        }
        var quoted = false
        val counts = mutableMapOf(',' to 0, '\t' to 0, ';' to 0)
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"') {
                if (quoted && i + 1 < text.length && text[i + 1] == '"') i++ else quoted = !quoted
            } else if (!quoted && (ch == '\n' || ch == '\r')) break
            else if (!quoted && ch in counts) counts[ch] = requireNotNull(counts[ch]) + 1
            i++
        }
        return when (counts.maxByOrNull { it.value }?.key ?: ',') { '\t' -> CsvDelimiter.TAB; ';' -> CsvDelimiter.SEMICOLON; else -> CsvDelimiter.COMMA }
    }

    private fun openRecords(file: CsvFileIdentity, encoding: CsvEncoding, delimiter: CsvDelimiter): CsvRecordStream {
        val charset = if (encoding == CsvEncoding.GB18030) charset("GB18030") else StandardCharsets.UTF_8
        return CsvRecordStream(File(file.canonicalPath).inputStream(), charset, delimiter.character())
    }

    private fun CsvDelimiter.character() = when (this) { CsvDelimiter.TAB -> '\t'; CsvDelimiter.SEMICOLON -> ';'; else -> ',' }
    private fun progress(size: Long, bytes: Long) = if (size <= 0L) 0 else ((bytes.coerceAtMost(size) * 99) / size).toInt()
    private fun invalid(message: String): Nothing = throw TimeSeriesCsvImportException("INVALID_CSV_IMPORT", message)
    private fun changed(message: String): Nothing = throw TimeSeriesCsvImportException("CSV_IMPORT_CHANGED", message)
    private fun unsupported(session: DatabaseSession): Nothing = throw TimeSeriesCsvImportException("UNSUPPORTED_DB_FEATURE", "当前数据库类型（${session.config.dbType}）不支持 CSV 批量导入")
}

private class CountingInputStream(private val source: InputStream) : InputStream() {
    var count = 0L; private set
    override fun read(): Int = source.read().also { if (it >= 0) count++ }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length).also { if (it > 0) count += it }
    override fun close() = source.close()
}

private class CsvRecordStream(source: InputStream, charset: java.nio.charset.Charset, delimiter: Char) : AutoCloseable {
    private val counting = CountingInputStream(BufferedInputStream(source, 64 * 1024))
    private val reader = InputStreamReader(
        counting,
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    )
    private val parser: CSVParser = CSVFormat.DEFAULT.builder()
        .setDelimiter(delimiter)
        .setQuote('"')
        .setIgnoreEmptyLines(false)
        .build()
        .parse(reader)
    private val iterator = parser.iterator()
    var recordNumber = 0L; private set
    val bytesRead get() = counting.count

    fun next(): List<String>? {
        if (!iterator.hasNext()) return null
        val record = iterator.next()
        recordNumber = record.recordNumber
        val values = record.toList().toMutableList()
        if (recordNumber == 1L && values.isNotEmpty()) values[0] = values[0].removePrefix("\uFEFF")
        return values
    }
    override fun close() = parser.close()
}

private class ErrorReceipt(val file: File, headers: List<String>) : AutoCloseable {
    private val writer: BufferedWriter = file.bufferedWriter(StandardCharsets.UTF_8)
    var failedRows = 0L; private set
    init { writeCsv(listOf("record_number", "error_reason") + headers) }
    fun write(recordNumber: Long, reason: String, fields: List<String>) { writeCsv(listOf(recordNumber.toString(), reason) + fields); failedRows++ }
    private fun writeCsv(values: List<String>) { writer.appendLine(values.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }) }
    override fun close() { writer.close(); if (failedRows == 0L) file.delete() }
}
