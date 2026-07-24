import { describe, expect, it } from 'vitest'
import type { TableTabState, WorkbenchTab } from '@/stores/workbenchStore'
import { invalidateTdengineLifecycleTabs } from '../tdengineLifecycleState'

function table(
  tableName: string,
  tableKind: TableTabState['tableKind'],
  stableName?: string,
  detailTab: TableTabState['detailTab'] = 'data',
): TableTabState {
  return {
    type: 'table', connectionId: 'c1', connectionName: 'TD', database: 'power', tableName,
    objectType: 'table', tableKind, stableName, tagDefinitions: [], tagValues: [], columns: [],
    indexes: [], ddl: 'cached ddl', previewRows: [{ value: 1 }], dataQuery: {}, hasMoreRows: true,
    loadingMoreRows: false, detailTab, loadedTabs: ['columns', 'data', 'tags', 'ddl'], loadingTabs: [],
  }
}

describe('invalidateTdengineLifecycleTabs', () => {
  it('invalidates only the stable and its child tabs and reloads the active resource', () => {
    const tabs: Record<string, WorkbenchTab> = {
      stable: table('Meters', 'SUPER_TABLE', undefined, 'tags'),
      child: table('d1', 'CHILD_TABLE', 'Meters'),
      other: table('d2', 'CHILD_TABLE', 'Other'),
    }

    const result = invalidateTdengineLifecycleTabs(tabs, 'stable', 'c1', 'power', 'Meters')

    expect(result.invalidatedTabKeys).toEqual(['stable', 'child'])
    expect(result.tabs.stable.type === 'table' && result.tabs.stable.loadedTabs).toEqual([])
    expect(result.tabs.child.type === 'table' && result.tabs.child.previewRows).toEqual([])
    expect(result.tabs.other).toBe(tabs.other)
    expect(result.activeReload).toEqual({ tabKey: 'stable', target: 'tags' })
  })

  it('does not reload the structure panel because it refreshes its own lifecycle snapshot', () => {
    const tabs = { stable: table('Meters', 'SUPER_TABLE', undefined, 'structure') }
    const result = invalidateTdengineLifecycleTabs(tabs, 'stable', 'c1', 'power', 'Meters')

    expect(result.activeReload).toBeNull()
  })

  it('preserves shell-owned structure/design markers while clearing stale metadata caches', () => {
    const stable = table('Meters', 'SUPER_TABLE', undefined, 'design')
    stable.loadedTabs = ['columns', 'data', 'design', 'structure', 'tags', 'ddl']
    stable.loadingTabs = ['columns', 'structure', 'ddl']
    stable.childProperties = {
      database: 'power', table: 'd1', stableName: 'Meters', tagValues: [], ttl: 0,
      fingerprint: 'child-v1',
    }
    const tabs: Record<string, WorkbenchTab> = { stable }

    const result = invalidateTdengineLifecycleTabs(tabs, 'stable', 'c1', 'power', 'Meters')
    const invalidated = result.tabs.stable

    expect(invalidated.type === 'table' && invalidated.loadedTabs).toEqual(['design', 'structure'])
    expect(invalidated.type === 'table' && invalidated.loadingTabs).toEqual(['structure'])
    expect(invalidated.type === 'table' && invalidated.childProperties).toBeUndefined()
    expect(result.activeReload).toBeNull()
    expect(tabs.stable).toBe(stable)
    expect(stable.loadedTabs).toEqual(['columns', 'data', 'design', 'structure', 'tags', 'ddl'])
  })

  it('matches connection, database and catalog identity exactly', () => {
    const tabs: Record<string, WorkbenchTab> = {
      exact: table('Meters', 'SUPER_TABLE'),
      caseVariant: table('meters', 'SUPER_TABLE'),
      childCaseVariant: table('d1', 'CHILD_TABLE', 'meters'),
      otherDatabase: { ...table('Meters', 'SUPER_TABLE'), database: 'archive' },
      otherConnection: { ...table('Meters', 'SUPER_TABLE'), connectionId: 'c2' },
    }

    const result = invalidateTdengineLifecycleTabs(tabs, 'exact', 'c1', 'power', 'Meters')

    expect(result.invalidatedTabKeys).toEqual(['exact'])
    expect(result.activeReload).toEqual({ tabKey: 'exact', target: 'data' })
    expect(result.tabs.caseVariant).toBe(tabs.caseVariant)
    expect(result.tabs.childCaseVariant).toBe(tabs.childCaseVariant)
    expect(result.tabs.otherDatabase).toBe(tabs.otherDatabase)
    expect(result.tabs.otherConnection).toBe(tabs.otherConnection)
  })
})
