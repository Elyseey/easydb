import type { TimeSeriesDeleteSnapshot } from '@/types'
import type { WorkbenchTab } from '@/stores/workbenchStore'

export interface TdengineDeleteTarget extends TimeSeriesDeleteSnapshot {
  connectionId: string
}

export function deletedTdengineTabKeys(
  tabs: Record<string, WorkbenchTab>,
  target: TdengineDeleteTarget,
): string[] {
  return Object.entries(tabs)
    .filter(([, tab]) => {
      if (
        tab.type !== 'table' ||
        tab.connectionId !== target.connectionId ||
        tab.database !== target.database
      ) return false

      if (target.kind !== 'SUPER_TABLE') return tab.tableName === target.name
      return tab.tableName === target.name || (
        tab.tableKind === 'CHILD_TABLE' && tab.stableName === target.name
      )
    })
    .map(([key]) => key)
}

export function tdengineChildPageKey(
  connectionId: string,
  database: string,
  stableName: string,
): string {
  return `${connectionId}::${database}::${stableName}`
}
