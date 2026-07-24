package com.easydb.common

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeSeriesModelsTest {

    @Test
    fun `csv import enums and structured config are serializable without sql`() {
        val config = TimeSeriesCsvImportConfig(
            filePath = "/tmp/events.csv",
            targetKind = TimeSeriesWriteTargetKind.BASIC_TABLE,
            table = "events",
            encoding = CsvEncoding.GB18030,
            delimiter = CsvDelimiter.SEMICOLON,
            mappings = listOf(TimeSeriesCsvColumnMapping("ts", "ts"))
        )
        val json = Json.encodeToString(config)
        assertEquals(config, Json.decodeFromString<TimeSeriesCsvImportConfig>(json))
        assertTrue(!json.contains("sql", ignoreCase = true))
    }

    @Test
    fun `table kind and child tag page are serializable`() {
        val tableJson = Json.encodeToString(
            TableInfo(name = "meters", tableKind = TableKind.SUPER_TABLE)
        )
        val pageJson = Json.encodeToString(
            TimeSeriesChildTablePage(
                items = listOf(
                    TimeSeriesChildTable(
                        name = "d1001",
                        database = "power",
                        stableName = "meters",
                        tagValues = listOf(
                            TimeSeriesTagValue("location", "VARCHAR(64)", "beijing")
                        )
                    )
                ),
                offset = 0,
                limit = 100,
                hasMore = false
            )
        )

        assertTrue(tableJson.contains("SUPER_TABLE"))
        assertTrue(pageJson.contains("stableName"))
        assertTrue(pageJson.contains("beijing"))
    }

    @Test
    fun `time series create definition keeps enum types null intent and parent identity`() {
        val definition = TimeSeriesCreateDefinition(
            kind = TimeSeriesCreateKind.CHILD_TABLE,
            name = "d1001",
            stableName = "meters",
            tagValues = listOf(
                TimeSeriesTagValueDraft(name = "location", value = "", isNull = false),
                TimeSeriesTagValueDraft(name = "group_id", value = "stale", isNull = true)
            )
        )

        val json = Json.encodeToString(definition)
        val decoded = Json.decodeFromString<TimeSeriesCreateDefinition>(json)

        assertTrue(json.contains("CHILD_TABLE"))
        assertEquals(definition, decoded)
        assertEquals("", decoded.tagValues.first().value)
        assertTrue(decoded.tagValues.last().isNull)
    }

    @Test
    fun `stable type list is serializable and preserves sql spelling`() {
        val field = TimeSeriesFieldDraft(
            name = "group_id",
            type = TimeSeriesDataType.BIGINT_UNSIGNED
        )

        assertEquals("BIGINT UNSIGNED", field.type.sql)
        assertTrue(Json.encodeToString(field).contains("BIGINT_UNSIGNED"))
    }

    @Test
    fun `time series query request and page preserve explicit bounds and null cells`() {
        val request = TimeSeriesQueryRequest(
            startInclusive = "2026-07-17T20:00:00+08:00",
            endExclusive = "2026-07-17T21:00:00+08:00",
            where = "voltage > 220",
            limit = 100,
            offset = 200
        )
        val requestJson = Json.encodeToString(request)
        assertEquals(request, Json.decodeFromString<TimeSeriesQueryRequest>(requestJson))

        val page = TimeSeriesQueryPage(
            rows = listOf(mapOf("ts" to "2026-07-17 20:30:00.000", "value" to null)),
            offset = 200,
            limit = 100,
            hasMore = true,
            startInclusive = request.startInclusive,
            endExclusive = request.endExclusive
        )
        assertEquals(page, Json.decodeFromString<TimeSeriesQueryPage>(Json.encodeToString(page)))
    }

    @Test
    fun `lifecycle command preview and apply proof are serializable without client ddl`() {
        val command = TimeSeriesLifecycleCommand(
            operation = TimeSeriesLifecycleOperation.MODIFY_TAG,
            name = "location",
            type = TimeSeriesDataType.NCHAR,
            length = 128
        )
        val snapshot = TimeSeriesLifecycleSnapshot(
            database = "power",
            stable = "meters",
            columns = listOf(
                TimeSeriesLifecycleField("ts", "TIMESTAMP", primaryTimestamp = true),
                TimeSeriesLifecycleField("value", "DOUBLE")
            ),
            tags = listOf(TimeSeriesLifecycleField("location", "NCHAR", length = 64)),
            fingerprint = "structure-fingerprint",
            affectedChildTables = 7
        )
        val preview = TimeSeriesLifecyclePreview(
            command = command,
            snapshot = snapshot,
            ddl = "ALTER STABLE `power`.`meters` MODIFY TAG `location` NCHAR(128)",
            previewToken = "preview-token",
            destructive = false
        )
        val apply = TimeSeriesLifecycleApplyRequest(
            command = command,
            expectedFingerprint = snapshot.fingerprint,
            previewToken = preview.previewToken
        )

        assertEquals(preview, Json.decodeFromString<TimeSeriesLifecyclePreview>(Json.encodeToString(preview)))
        assertEquals(apply, Json.decodeFromString<TimeSeriesLifecycleApplyRequest>(Json.encodeToString(apply)))
        assertTrue(Json.encodeToString(apply).contains("MODIFY_TAG"))
        assertEquals(7, Json.decodeFromString<TimeSeriesLifecyclePreview>(Json.encodeToString(preview)).snapshot.affectedChildTables)
    }

    @Test
    fun `child property command preserves null intent separately from empty string`() {
        val emptyValue = TimeSeriesChildPropertyCommand(
            operation = TimeSeriesChildPropertyOperation.SET_TAG,
            tagName = "location",
            value = "",
            isNull = false
        )
        val nullValue = TimeSeriesChildPropertyCommand(
            operation = TimeSeriesChildPropertyOperation.SET_TAG,
            tagName = "location",
            value = null,
            isNull = true
        )
        val query = TimeSeriesChildTableQuery(
            filters = listOf(TimeSeriesTagFilter("location", TimeSeriesTagFilterOperator.EQ, ""))
        )

        assertEquals(emptyValue, Json.decodeFromString(Json.encodeToString(emptyValue)))
        assertEquals(nullValue, Json.decodeFromString(Json.encodeToString(nullValue)))
        assertEquals("", Json.decodeFromString<TimeSeriesChildTableQuery>(Json.encodeToString(query)).filters.single().value)
    }

    @Test
    fun `delete preview and apply proof serialize without accepting client ddl`() {
        val snapshot = TimeSeriesDeleteSnapshot(
            database = "power",
            name = "meters",
            kind = TimeSeriesDeleteObjectKind.SUPER_TABLE,
            affectedChildTables = 7,
            fingerprint = "delete-v1"
        )
        val preview = TimeSeriesDeletePreview(
            snapshot = snapshot,
            ddl = "DROP STABLE `power`.`meters`",
            previewToken = "preview-token"
        )
        val apply = TimeSeriesDeleteApplyRequest(
            expectedFingerprint = snapshot.fingerprint,
            previewToken = preview.previewToken,
            confirmationName = "meters"
        )

        assertEquals(preview, Json.decodeFromString(Json.encodeToString(preview)))
        assertEquals(apply, Json.decodeFromString(Json.encodeToString(apply)))
        assertTrue(!Json.encodeToString(apply).contains("DROP STABLE"))
    }

    @Test
    fun `phase seven structure and write apply payloads keep structured values without executable sql`() {
        val basicCommand = TimeSeriesBasicTableCommand(TimeSeriesBasicTableOperation.DROP_COLUMN, "payload")
        val basicApply = TimeSeriesBasicTableApplyRequest(basicCommand, "basic-v1", "basic-token", "payload")
        assertEquals(basicApply, Json.decodeFromString(Json.encodeToString(basicApply)))
        assertTrue(!Json.encodeToString(basicApply).contains("ALTER TABLE"))

        val writeRequest = TimeSeriesWriteRequest(
            targetKind = TimeSeriesWriteTargetKind.BASIC_TABLE,
            table = "events",
            columns = listOf("ts", "message"),
            rows = listOf(TimeSeriesWriteRow(listOf(
                TimeSeriesWriteCell("ts", "2026-07-22 10:00:00", isNull = false),
                TimeSeriesWriteCell("message", "", isNull = false)
            )))
        )
        val writeApply = TimeSeriesWriteApplyRequest(writeRequest, "write-v1", "write-token")
        val decoded = Json.decodeFromString<TimeSeriesWriteApplyRequest>(Json.encodeToString(writeApply))
        assertEquals(writeApply, decoded)
        assertEquals("", decoded.request.rows.single().cells.last().value)
        assertTrue(!Json.encodeToString(writeApply).contains("INSERT INTO"))
    }
}
