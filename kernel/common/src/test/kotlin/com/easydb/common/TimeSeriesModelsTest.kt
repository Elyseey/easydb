package com.easydb.common

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeSeriesModelsTest {

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
}
