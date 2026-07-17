package com.easydb.common

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
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
}
