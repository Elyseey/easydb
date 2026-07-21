import type { WorkbenchTab } from '@/stores/workbenchStore'

/**
 * Object category lists are cheap to reconstruct because their search state lives in the store,
 * but expensive to retain because a large metadata set can keep a table viewport mounted.
 */
export const shouldKeepInactiveWorkbenchPaneMounted = (tab: WorkbenchTab): boolean =>
  tab.type !== 'category-list'
