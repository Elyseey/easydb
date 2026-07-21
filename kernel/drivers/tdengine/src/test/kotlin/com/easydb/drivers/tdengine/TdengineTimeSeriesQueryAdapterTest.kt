package com.easydb.drivers.tdengine

import com.easydb.common.TimeSeriesQueryRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TdengineTimeSeriesQueryAdapterTest {
    private val adapter = TdengineTimeSeriesQueryAdapter()

    @Test
    fun `builds default latest-first query with one row lookahead`() {
        val plan = adapter.buildQueryPlan("power", "meters", TimeSeriesQueryRequest())

        assertEquals(
            "SELECT * FROM `power`.`meters` ORDER BY _rowts DESC LIMIT 1001 OFFSET 0",
            plan.sql
        )
        assertEquals(1_000, plan.request.limit)
        assertEquals(0, plan.request.offset)
    }

    @Test
    fun `combines explicit offset bounds and custom where without rewriting order`() {
        val plan = adapter.buildQueryPlan(
            "power",
            "d1001",
            TimeSeriesQueryRequest(
                startInclusive = "2026-07-17T20:00:00+08:00",
                endExclusive = "2026-07-17T21:00:00+08:00",
                where = "voltage > 220",
                orderBy = "`voltage` ASC",
                limit = 100,
                offset = 20
            )
        )

        assertTrue(
            plan.sql.contains(
                "WHERE (_rowts >= '2026-07-17T20:00:00+08:00' " +
                    "AND _rowts < '2026-07-17T21:00:00+08:00') AND (voltage > 220)"
            )
        )
        assertTrue(plan.sql.endsWith("ORDER BY `voltage` ASC LIMIT 101 OFFSET 20"))
    }

    @Test
    fun `normalizes zulu bounds and clamps paging`() {
        val plan = adapter.buildQueryPlan(
            "power",
            "events",
            TimeSeriesQueryRequest(
                startInclusive = "2026-07-17T12:00:00Z",
                endExclusive = "2026-07-17T13:00:00Z",
                limit = 50_000,
                offset = -10
            )
        )

        assertEquals("2026-07-17T12:00:00Z", plan.request.startInclusive)
        assertEquals(1_000, plan.request.limit)
        assertEquals(0, plan.request.offset)
        assertTrue(plan.sql.endsWith("LIMIT 1001 OFFSET 0"))
    }

    @Test
    fun `rejects incomplete reversed ambiguous and unsafe query input`() {
        assertFailsWith<IllegalArgumentException> {
            adapter.buildQueryPlan(
                "power",
                "events",
                TimeSeriesQueryRequest(startInclusive = "2026-07-17T12:00:00Z")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildQueryPlan(
                "power",
                "events",
                TimeSeriesQueryRequest(
                    startInclusive = "2026-07-17T13:00:00Z",
                    endExclusive = "2026-07-17T12:00:00Z"
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildQueryPlan(
                "power",
                "events",
                TimeSeriesQueryRequest(
                    startInclusive = "2026-07-17T12:00:00",
                    endExclusive = "2026-07-17T13:00:00"
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildQueryPlan(
                "power",
                "events",
                TimeSeriesQueryRequest(where = "value > 1; DROP TABLE events")
            )
        }
    }
}
