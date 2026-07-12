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
})
