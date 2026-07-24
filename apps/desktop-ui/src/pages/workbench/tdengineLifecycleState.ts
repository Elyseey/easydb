import type { WorkbenchTab } from '@/stores/workbenchStore'
import type { TableDetailLoadTarget } from './tableDetailLoadPlan'

export interface LifecycleInvalidationResult {
  tabs: Record<string, WorkbenchTab>
  invalidatedTabKeys: string[]
  activeReload: { tabKey: string; target: TableDetailLoadTarget } | null
}

const INVALIDATED_RESOURCES = new Set(['columns', 'data', 'tags', 'ddl'])

export function invalidateTdengineLifecycleTabs(
  tabs: Record<string, WorkbenchTab>,
  activeTabKey: string | null,
  connectionId: string,
  database: string,
  stable: string,
): LifecycleInvalidationResult {
  const nextTabs = { ...tabs }
  const invalidatedTabKeys: string[] = []
  let activeReload: LifecycleInvalidationResult['activeReload'] = null

  Object.entries(tabs).forEach(([tabKey, tab]) => {
    if (tab.type !== 'table' || tab.connectionId !== connectionId || tab.database !== database) return
    const related = (tab.tableKind === 'SUPER_TABLE' && tab.tableName === stable) ||
      (tab.tableKind === 'CHILD_TABLE' && tab.stableName === stable)
    if (!related) return

    invalidatedTabKeys.push(tabKey)
    nextTabs[tabKey] = {
      ...tab,
      columns: [],
      tagDefinitions: [],
      tagValues: [],
      childProperties: undefined,
      ddl: '',
      previewRows: [],
      hasMoreRows: false,
      loadingMoreRows: false,
      loadedTabs: tab.loadedTabs.filter((resource) => !INVALIDATED_RESOURCES.has(resource)),
      loadingTabs: tab.loadingTabs.filter((resource) => !INVALIDATED_RESOURCES.has(resource)),
    }

    if (tabKey === activeTabKey && tab.detailTab !== 'structure' && tab.detailTab !== 'design') {
      activeReload = { tabKey, target: tab.detailTab }
    }
  })

  return { tabs: nextTabs, invalidatedTabKeys, activeReload }
}
