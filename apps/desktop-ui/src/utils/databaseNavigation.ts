import type { DbType } from '@/types'
import { getDbCapabilities } from './dbCapabilities'

export type DatabaseNavigationCapability =
  | 'migration'
  | 'sync'
  | 'structureCompare'
  | 'dataTracker'
  | 'slowQuery'

export function supportsDatabaseNavigation(
  dbType: DbType | null,
  capability: DatabaseNavigationCapability,
): boolean {
  const capabilities = getDbCapabilities(dbType)
  switch (capability) {
    case 'migration': return capabilities.tasks.migration
    case 'sync': return capabilities.tasks.sync
    case 'structureCompare': return capabilities.tasks.structureCompare
    case 'dataTracker': return capabilities.diagnostics.dataTracker
    case 'slowQuery': return capabilities.diagnostics.slowQuery
  }
}
