import type { DbType } from '@/types'

export type PairTaskFeature = 'migration' | 'sync' | 'structureCompare'
export type PairTaskRole = 'source' | 'target'

interface DatabaseTaskPair {
  source: DbType
  target: DbType
}

const SUPPORTED_TASK_PAIRS: Record<PairTaskFeature, readonly DatabaseTaskPair[]> = {
  migration: [
    { source: 'mysql', target: 'mysql' },
    { source: 'mysql', target: 'dameng' },
  ],
  sync: [
    { source: 'mysql', target: 'mysql' },
  ],
  structureCompare: [
    { source: 'mysql', target: 'mysql' },
  ],
}

export function supportsDatabaseTaskPair(
  feature: PairTaskFeature,
  source: DbType | undefined,
  target: DbType | undefined,
): boolean {
  if (!source || !target) return false
  return SUPPORTED_TASK_PAIRS[feature].some((pair) => pair.source === source && pair.target === target)
}

export function supportsDatabaseTaskRole(
  feature: PairTaskFeature,
  dbType: DbType,
  role: PairTaskRole,
): boolean {
  return SUPPORTED_TASK_PAIRS[feature].some((pair) => pair[role] === dbType)
}
