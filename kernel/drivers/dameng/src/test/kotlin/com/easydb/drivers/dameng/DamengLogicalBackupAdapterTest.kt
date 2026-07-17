package com.easydb.drivers.dameng

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement

class DamengLogicalBackupAdapterTest {
    @Test
    fun `starts serializable read only snapshot`() {
        val connection = mockk<Connection>()
        val statement = mockk<Statement>()
        every { connection.autoCommit = false } just runs
        every { connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE } just runs
        every { connection.isReadOnly = true } just runs
        every { connection.createStatement() } returns statement
        every { statement.execute("SET TRANSACTION READ ONLY") } returns true
        every { statement.close() } just runs

        val result = DamengLogicalBackupAdapter().begin(connection)

        assertEquals("snapshot", result.consistency)
        assertTrue(result.warnings.isEmpty())
        verify { connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE }
        verify { statement.execute("SET TRANSACTION READ ONLY") }
    }

    @Test
    fun `records explicit downgrade when snapshot cannot start`() {
        val connection = mockk<Connection>()
        val statement = mockk<Statement>()
        every { connection.autoCommit = false } just runs
        every { connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE } just runs
        every { connection.isReadOnly = true } just runs
        every { connection.createStatement() } returns statement
        every { statement.execute("SET TRANSACTION READ ONLY") } throws SQLException("permission denied")
        every { statement.close() } just runs
        every { connection.rollback() } just runs

        val result = DamengLogicalBackupAdapter().begin(connection)

        assertEquals("best_effort", result.consistency)
        assertTrue(result.warnings.single().contains("permission denied"))
        verify { connection.rollback() }
    }
}
