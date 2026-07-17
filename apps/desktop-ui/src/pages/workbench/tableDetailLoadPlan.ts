export type TableDetailLoadTarget = 'data' | 'design' | 'ddl' | 'columns'

export interface TableDetailLoadPlan {
  loadColumns: boolean
  loadTab: boolean
}

/**
 * Data editing needs column metadata. Design and DDL own their metadata requests
 * and must not trigger an unrelated columns request from the workbench shell.
 */
export const tableDetailLoadPlan = (
  target: TableDetailLoadTarget,
  loadedTabs: readonly string[],
): TableDetailLoadPlan => ({
  loadColumns: (target === 'data' || target === 'columns') && !loadedTabs.includes('columns'),
  loadTab: target !== 'columns' && !loadedTabs.includes(target),
})
