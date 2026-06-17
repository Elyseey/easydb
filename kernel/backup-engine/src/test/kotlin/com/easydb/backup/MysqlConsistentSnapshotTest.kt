package com.easydb.backup

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

class MysqlConsistentSnapshotTest {

    @Test
    fun `begin returns snapshot level when successful`() {
        val conn = mockk<Connection>()
        val stmt = mockk<Statement>()

        every { conn.createStatement() } returns stmt
        every { stmt.execute(any()) } returns true
        every { stmt.close() } returns Unit
        every { stmt.executeQuery("SHOW MASTER STATUS") } throws SQLException("No privilege")

        val snapshot = MysqlConsistentSnapshot.begin(conn)

        assertEquals("snapshot", snapshot.level)
        assertNull(snapshot.binlogFile)
        assertNull(snapshot.binlogPos)
        assertEquals(conn, snapshot.connection)

        verify { conn.createStatement() }
        verify { stmt.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
        verify { stmt.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT") }
    }

    @Test
    fun `begin captures binlog info when available`() {
        val conn = mockk<Connection>()
        val stmt = mockk<Statement>()
        val rs = mockk<ResultSet>()

        every { conn.createStatement() } returns stmt
        every { stmt.execute(any()) } returns true
        every { stmt.close() } returns Unit
        every { stmt.executeQuery("SHOW MASTER STATUS") } returns rs
        every { rs.next() } returns true andThen false
        every { rs.getString("File") } returns "binlog.000123"
        every { rs.getLong("Position") } returns 45678L
        every { rs.close() } returns Unit

        val snapshot = MysqlConsistentSnapshot.begin(conn)

        assertEquals("snapshot", snapshot.level)
        assertEquals("binlog.000123", snapshot.binlogFile)
        assertEquals(45678L, snapshot.binlogPos)
    }

    @Test
    fun `begin returns best_effort when snapshot fails`() {
        val conn = mockk<Connection>()
        val stmt = mockk<Statement>()

        every { conn.createStatement() } returns stmt
        every { stmt.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ") } throws SQLException("MyISAM error")
        every { stmt.close() } returns Unit

        val snapshot = MysqlConsistentSnapshot.begin(conn)

        assertEquals("best_effort", snapshot.level)
        assertNull(snapshot.binlogFile)
        assertNull(snapshot.binlogPos)
    }

    @Test
    fun `release calls ROLLBACK on connection`() {
        val conn = mockk<Connection>()
        val stmt = mockk<Statement>()

        every { conn.createStatement() } returns stmt
        every { stmt.execute("ROLLBACK") } returns true
        every { stmt.close() } returns Unit

        val snapshot = MysqlConsistentSnapshot.SnapshotInfo(
            level = "snapshot",
            binlogFile = "binlog.000123",
            binlogPos = 45678L,
            connection = conn
        )

        MysqlConsistentSnapshot.release(snapshot)

        verify { conn.createStatement() }
        verify { stmt.execute("ROLLBACK") }
    }

    @Test
    fun `release ignores rollback failure`() {
        val conn = mockk<Connection>()
        val stmt = mockk<Statement>()

        every { conn.createStatement() } returns stmt
        every { stmt.execute("ROLLBACK") } throws SQLException("Connection closed")
        every { stmt.close() } returns Unit

        val snapshot = MysqlConsistentSnapshot.SnapshotInfo(
            level = "snapshot",
            binlogFile = null,
            binlogPos = null,
            connection = conn
        )

        // Should not throw exception
        MysqlConsistentSnapshot.release(snapshot)
    }
}