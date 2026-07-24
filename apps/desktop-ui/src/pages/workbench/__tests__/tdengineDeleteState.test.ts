import { describe, expect, it } from 'vitest'
import type { WorkbenchTab } from '@/stores/workbenchStore'
import { deletedTdengineTabKeys } from '../tdengineDeleteState'

function table(
  tableName: string,
  tableKind: 'BASIC_TABLE' | 'CHILD_TABLE' | 'SUPER_TABLE',
  stableName?: string,
): WorkbenchTab {
  return {
    type: 'table',
    connectionId: 'c1',
    connectionName: 'TD',
    database: 'power',
    tableName,
    objectType: 'table',
    tableKind,
    stableName,
    tagDefinitions: [],
    tagValues: [],
    columns: [],
    indexes: [],
    ddl: '',
    previewRows: [],
    dataQuery: {},
    hasMoreRows: false,
    loadingMoreRows: false,
    detailTab: 'data',
    loadedTabs: [],
    loadingTabs: [],
  }
}

describe('deletedTdengineTabKeys', () => {
  it('closes a stable and only its loaded child tabs', () => {
    const tabs = {
      stable: table('Meters', 'SUPER_TABLE'),
      child: table('d1', 'CHILD_TABLE', 'Meters'),
      otherChild: table('d2', 'CHILD_TABLE', 'Other'),
      basic: table('basic', 'BASIC_TABLE'),
    }

    expect(deletedTdengineTabKeys(tabs, {
      connectionId: 'c1',
      database: 'power',
      name: 'Meters',
      kind: 'SUPER_TABLE',
      affectedChildTables: 1,
      fingerprint: 'v1',
    })).toEqual(['stable', 'child'])
  })

  it('closes only the exact basic or child table tab', () => {
    const tabs = {
      child: table('d1', 'CHILD_TABLE', 'Meters'),
      stable: table('Meters', 'SUPER_TABLE'),
    }
    expect(deletedTdengineTabKeys(tabs, {
      connectionId: 'c1',
      database: 'power',
      name: 'd1',
      kind: 'CHILD_TABLE',
      stableName: 'Meters',
      affectedChildTables: 0,
      fingerprint: 'v1',
    })).toEqual(['child'])
  })
})
