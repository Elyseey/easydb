package com.easydb.drivers.tdengine

import com.easydb.common.DatabaseSession
import com.easydb.common.TimeSeriesCreateDefinition
import com.easydb.common.TimeSeriesCreateKind
import com.easydb.common.TimeSeriesDataType
import com.easydb.common.TimeSeriesFieldDraft
import com.easydb.common.TimeSeriesMetadataAdapter
import com.easydb.common.TimeSeriesParentNotFoundException
import com.easydb.common.TimeSeriesTagDefinition
import com.easydb.common.TimeSeriesTagValueDraft
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TdengineTimeSeriesObjectAdapterTest {
    private val metadata = mockk<TimeSeriesMetadataAdapter>()
    private val session = mockk<DatabaseSession>()
    private val adapter = TdengineTimeSeriesObjectAdapter(metadata)

    @Test
    fun `builds supertable ddl with distinct metrics tags lengths and escaped comment`() {
        val ddl = adapter.buildCreateSql(
            session,
            "power",
            TimeSeriesCreateDefinition(
                kind = TimeSeriesCreateKind.SUPER_TABLE,
                name = "Meters",
                columns = listOf(
                    field("ts", TimeSeriesDataType.TIMESTAMP),
                    field("reading", TimeSeriesDataType.DOUBLE)
                ),
                tags = listOf(
                    field("location", TimeSeriesDataType.NCHAR, 32),
                    field("group_id", TimeSeriesDataType.INT_UNSIGNED)
                ),
                comment = "owner\\rack's metrics"
            )
        )

        assertEquals(
            "CREATE STABLE `power`.`Meters` (`ts` TIMESTAMP, `reading` DOUBLE) " +
                "TAGS (`location` NCHAR(32), `group_id` INT UNSIGNED) COMMENT 'owner\\\\rack\\'s metrics'",
            ddl
        )
    }

    @Test
    fun `builds basic table ddl and rejects a non timestamp first column`() {
        val valid = TimeSeriesCreateDefinition(
            kind = TimeSeriesCreateKind.BASIC_TABLE,
            name = "events",
            columns = listOf(
                field("event_time", TimeSeriesDataType.TIMESTAMP),
                field("message", TimeSeriesDataType.VARCHAR, 128)
            )
        )

        assertEquals(
            "CREATE TABLE `power`.`events` (`event_time` TIMESTAMP, `message` VARCHAR(128))",
            adapter.buildCreateSql(session, "power", valid)
        )

        val error = assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                valid.copy(columns = listOf(field("value", TimeSeriesDataType.DOUBLE), valid.columns.last()))
            )
        }
        assertTrue(error.message.orEmpty().contains("第一个字段必须是 TIMESTAMP"))
    }

    @Test
    fun `builds child table ddl from server tag definitions and keeps null distinct from empty`() {
        every { metadata.listTagDefinitions(session, "power", "meters") } returns listOf(
            TimeSeriesTagDefinition("location", "VARCHAR(64)"),
            TimeSeriesTagDefinition("description", "NCHAR(32)"),
            TimeSeriesTagDefinition("group_id", "BIGINT UNSIGNED"),
            TimeSeriesTagDefinition("enabled", "BOOL"),
            TimeSeriesTagDefinition("installed_at", "TIMESTAMP"),
            TimeSeriesTagDefinition("nullable_note", "VARCHAR(8)")
        )

        val ddl = adapter.buildCreateSql(
            session,
            "power",
            TimeSeriesCreateDefinition(
                kind = TimeSeriesCreateKind.CHILD_TABLE,
                name = "d1001",
                stableName = "meters",
                tagValues = listOf(
                    TimeSeriesTagValueDraft("enabled", "TRUE"),
                    TimeSeriesTagValueDraft("group_id", "18446744073709551615"),
                    TimeSeriesTagValueDraft("description", "中文\\机房's"),
                    TimeSeriesTagValueDraft("location", ""),
                    TimeSeriesTagValueDraft("installed_at", "2026-07-17 21:30:00.123456"),
                    TimeSeriesTagValueDraft("nullable_note", value = "stale value", isNull = true)
                )
            )
        )

        assertEquals(
            "CREATE TABLE `power`.`d1001` USING `power`.`meters` " +
                "(`location`, `description`, `group_id`, `enabled`, `installed_at`, `nullable_note`) " +
                "TAGS ('', '中文\\\\机房\\'s', 18446744073709551615, TRUE, '2026-07-17 21:30:00.123456', NULL)",
            ddl
        )
    }

    @Test
    fun `rejects missing unknown duplicate and out of range child tag values`() {
        every { metadata.listTagDefinitions(session, "power", "meters") } returns listOf(
            TimeSeriesTagDefinition("group_id", "INT UNSIGNED")
        )
        val base = TimeSeriesCreateDefinition(
            kind = TimeSeriesCreateKind.CHILD_TABLE,
            name = "d1001",
            stableName = "meters"
        )

        assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(session, "power", base)
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                base.copy(
                    tagValues = listOf(
                        TimeSeriesTagValueDraft("group_id", "1"),
                        TimeSeriesTagValueDraft("group_id", "2")
                    )
                )
            )
        }
        val rangeError = assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                base.copy(tagValues = listOf(TimeSeriesTagValueDraft("group_id", "-1")))
            )
        }
        assertTrue(rangeError.message.orEmpty().contains("超出类型范围"))
    }

    @Test
    fun `reports a missing parent stable as a dedicated domain error`() {
        every { metadata.listTagDefinitions(session, "power", "missing") } returns emptyList()

        val error = assertFailsWith<TimeSeriesParentNotFoundException> {
            adapter.buildCreateSql(
                session,
                "power",
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.CHILD_TABLE,
                    name = "d1001",
                    stableName = "missing"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("父超级表不存在"))
    }

    @Test
    fun `rejects unsupported parent tag types and invalid create shapes`() {
        every { metadata.listTagDefinitions(session, "power", "meters") } returns listOf(
            TimeSeriesTagDefinition("payload", "JSON")
        )
        val childError = assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.CHILD_TABLE,
                    name = "d1001",
                    stableName = "meters",
                    tagValues = listOf(TimeSeriesTagValueDraft("payload", "{}"))
                )
            )
        }
        assertTrue(childError.message.orEmpty().contains("暂不支持"))

        val duplicateError = assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.SUPER_TABLE,
                    name = "meters",
                    columns = listOf(
                        field("ts", TimeSeriesDataType.TIMESTAMP),
                        field("location", TimeSeriesDataType.DOUBLE)
                    ),
                    tags = listOf(field("location", TimeSeriesDataType.VARCHAR, 32))
                )
            )
        }
        assertTrue(duplicateError.message.orEmpty().contains("不能重复"))

        assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.BASIC_TABLE,
                    name = "invalid.name",
                    columns = listOf(
                        field("ts", TimeSeriesDataType.TIMESTAMP),
                        field("value", TimeSeriesDataType.BINARY)
                    )
                )
            )
        }
    }

    @Test
    fun `rejects missing type lengths oversized comments and multibyte tag values`() {
        val missingLengthError = assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.BASIC_TABLE,
                    name = "events",
                    columns = listOf(
                        field("ts", TimeSeriesDataType.TIMESTAMP),
                        field("payload", TimeSeriesDataType.BINARY)
                    )
                )
            )
        }
        assertTrue(missingLengthError.message.orEmpty().contains("必须设置类型长度"))

        val commentError = assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.BASIC_TABLE,
                    name = "events",
                    columns = listOf(
                        field("ts", TimeSeriesDataType.TIMESTAMP),
                        field("value", TimeSeriesDataType.DOUBLE)
                    ),
                    comment = "中".repeat(342)
                )
            )
        }
        assertTrue(commentError.message.orEmpty().contains("COMMENT 不能超过"))

        every { metadata.listTagDefinitions(session, "power", "meters") } returns listOf(
            TimeSeriesTagDefinition("code", "VARCHAR(2)")
        )
        val tagValueError = assertFailsWith<IllegalArgumentException> {
            adapter.buildCreateSql(
                session,
                "power",
                TimeSeriesCreateDefinition(
                    kind = TimeSeriesCreateKind.CHILD_TABLE,
                    name = "d1001",
                    stableName = "meters",
                    tagValues = listOf(TimeSeriesTagValueDraft("code", "中文"))
                )
            )
        }
        assertTrue(tagValueError.message.orEmpty().contains("超过声明长度 2 字节"))
    }

    private fun field(
        name: String,
        type: TimeSeriesDataType,
        length: Int? = null
    ) = TimeSeriesFieldDraft(name, type, length)
}
