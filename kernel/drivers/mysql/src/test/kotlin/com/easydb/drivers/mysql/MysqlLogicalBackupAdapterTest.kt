package com.easydb.drivers.mysql

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

class MysqlLogicalBackupAdapterTest {
    @Test
    fun `starts repeatable read consistent snapshot`() {
        val connection = mockk<Connection>()
        val statement = mockk<Statement>()
        val resultSet = mockk<ResultSet>()
        every { connection.createStatement() } returns statement
        every { statement.execute(any()) } returns true
        every { statement.executeQuery("SHOW MASTER STATUS") } throws SQLException("no privilege")
        every { statement.executeQuery("SELECT @@character_set_database") } returns resultSet
        every { statement.executeQuery("SELECT @@collation_database") } returns resultSet
        every { resultSet.next() } returns false
        every { resultSet.close() } just runs
        every { statement.close() } just runs

        val result = MysqlLogicalBackupAdapter().begin(connection)

        assertEquals("snapshot", result.consistency)
        assertEquals("utf8mb4", result.charset)
        assertTrue(result.warnings.isEmpty())
        verify { statement.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT") }
    }

    @Test
    fun `records downgrade when snapshot start fails`() {
        val connection = mockk<Connection>()
        val statement = mockk<Statement>()
        every { connection.createStatement() } returns statement
        every { statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ") } throws SQLException("unsupported")
        every { statement.execute("ROLLBACK") } returns true
        every { statement.close() } just runs

        val result = MysqlLogicalBackupAdapter().begin(connection)

        assertEquals("best_effort", result.consistency)
        assertTrue(result.warnings.single().contains("unsupported"))
    }
}
