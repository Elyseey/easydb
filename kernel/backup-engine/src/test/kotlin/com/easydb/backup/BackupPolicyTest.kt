package com.easydb.backup

import com.easydb.common.DatabaseAdapter
import com.easydb.common.DatabaseCapabilities
import com.easydb.common.DbType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BackupPolicyTest {
    private val adapter = mockk<DatabaseAdapter>().also {
        every { it.dbType() } returns DbType.DAMENG
        every { it.capabilities() } returns DatabaseCapabilities(supportsLogicalBackup = true)
    }

    @Test
    fun `rejects unknown backup mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupPolicy.validateOrThrow(config(mode = "anything"), adapter)
        }
    }

    @Test
    fun `rejects blank namespace`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupPolicy.validateOrThrow(config(database = " "), adapter)
        }
    }

    @Test
    fun `rejects compression modes that the package writer does not implement`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupPolicy.validateOrThrow(config().copy(compression = "none"), adapter)
        }
    }

    private fun config(database: String = "SOURCE", mode: String = "full") = BackupConfig(
        connectionId = "dm",
        database = database,
        mode = mode
    )
}
