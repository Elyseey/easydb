import { describe, expect, it } from 'vitest'
import type { WorkbenchTab } from '@/stores/workbenchStore'
import { shouldKeepInactiveWorkbenchPaneMounted } from '../paneMountPolicy'

describe('shouldKeepInactiveWorkbenchPaneMounted', () => {
  it('unmounts inactive object category lists to release their large metadata viewport', () => {
    const tab: WorkbenchTab = {
      type: 'category-list',
      connectionId: 'connection-1',
      connectionName: 'TDengine',
      database: 'meters',
      category: 'tables',
      categorySearch: 'device',
    }

    expect(shouldKeepInactiveWorkbenchPaneMounted(tab)).toBe(false)
  })

  it('keeps stateful editor and table panes mounted', () => {
    const tab: WorkbenchTab = {
      type: 'sql-query',
      connectionId: 'connection-1',
      connectionName: 'TDengine',
      database: 'meters',
      queryId: 'query-1',
    }

    expect(shouldKeepInactiveWorkbenchPaneMounted(tab)).toBe(true)
  })
})
