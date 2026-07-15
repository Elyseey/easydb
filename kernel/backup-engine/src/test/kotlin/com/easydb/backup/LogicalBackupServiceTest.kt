package com.easydb.backup

import com.easydb.common.DialectAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Clob
import java.sql.ResultSet
import java.sql.Types

class LogicalBackupServiceTest {
    @Test
    fun `exports clob text from result set instead of jdbc locator toString`() {
        val resultSet = mockk<ResultSet>()
        val clob = mockk<Clob>()
        val dialect = mockk<DialectAdapter>()
        every { resultSet.getString(1) } returns "正文包含 '引号'"
        every { dialect.formatExportStringLiteral("正文包含 '引号'") } returns "'正文包含 ''引号'''"

        val value = formatBackupSqlValue(Types.CLOB, clob, resultSet, 1, dialect)

        assertEquals("'正文包含 ''引号'''", value)
        verify(exactly = 1) { resultSet.getString(1) }
    }

    @Test
    fun `exports binary values as hex literals`() {
        val resultSet = mockk<ResultSet>()
        val dialect = mockk<DialectAdapter>(relaxed = true)
        every { resultSet.getBytes(2) } returns byteArrayOf(0x00, 0x7F, 0xFF.toByte())

        assertEquals("X'007FFF'", formatBackupSqlValue(Types.BLOB, Any(), resultSet, 2, dialect))
    }
}
