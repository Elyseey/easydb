import { describe, expect, it } from 'vitest'
import { getDbCapabilities } from '../dbCapabilities'

describe('database metadata capabilities', () => {
  it('keeps MySQL database charset editing enabled', () => {
    expect(getDbCapabilities('mysql').metadata.schemaAlterCharset).toBe(true)
  })

  it('allows Dameng schema creation and deletion without offering charset editing', () => {
    const metadata = getDbCapabilities('dameng').metadata

    expect(metadata.schemaCreation).toBe(true)
    expect(metadata.schemaManagement).toBe(true)
    expect(metadata.schemaAlterCharset).toBe(false)
  })

  it('offers Dameng logical backup, restore, export and SQL file import', () => {
    const workbench = getDbCapabilities('dameng').workbench

    expect(workbench.exportData).toBe(true)
    expect(workbench.importSql).toBe(true)
    expect(workbench.backup).toBe(true)
    expect(workbench.restore).toBe(true)
  })
})
