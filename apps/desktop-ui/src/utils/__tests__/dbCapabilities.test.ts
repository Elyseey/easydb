import { describe, expect, it } from 'vitest'
import type { ConnectionConfig } from '@/types'
import {
  filterConnectionsByDiagnosticCapability,
  getDbCapabilities,
  supportsDatabaseDiagnostic,
} from '../dbCapabilities'

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

  it('uses the safe unsupported capability set when no database is active', () => {
    expect(getDbCapabilities(null).tasks.migration).toBe(false)
    expect(getDbCapabilities(null).diagnostics.dataTracker).toBe(false)
  })
})

describe('database diagnostic capabilities', () => {
  const connection = (id: string, dbType: ConnectionConfig['dbType']): ConnectionConfig => ({
    id,
    name: id,
    dbType,
    host: '127.0.0.1',
    port: dbType === 'dameng' ? 5236 : 3306,
    username: 'tester',
    password: '',
    status: 'disconnected',
  })

  it('keeps diagnostic support in the capability model instead of page-level dbType checks', () => {
    expect(supportsDatabaseDiagnostic('mysql', 'dataTracker')).toBe(true)
    expect(supportsDatabaseDiagnostic('mysql', 'slowQuery')).toBe(true)
    expect(supportsDatabaseDiagnostic('dameng', 'dataTracker')).toBe(false)
    expect(supportsDatabaseDiagnostic('dameng', 'slowQuery')).toBe(false)
  })

  it.each(['dataTracker', 'slowQuery'] as const)(
    'filters %s connections through diagnostic capabilities',
    (feature) => {
      const connections = [
        connection('mysql-1', 'mysql'),
        connection('dameng-1', 'dameng'),
        connection('postgresql-1', 'postgresql'),
      ]

      expect(filterConnectionsByDiagnosticCapability(connections, feature).map((item) => item.id))
        .toEqual(['mysql-1'])
    },
  )
})
