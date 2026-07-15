import type { DbType } from '@/types'

export interface RestoreStrategyOption {
  label: string
  value: 'restore_to_new' | 'overwrite_existing'
}

export interface RestoreModeOption {
  label: string
  value: 'restore_all' | 'structure_only' | 'data_only'
}

export function isRestoreDatabaseTypeCompatible(manifestDbType: string, targetDbType: DbType): boolean {
  return manifestDbType.trim().toLowerCase() === targetDbType
}

export function restoreStrategyOptions(dbType: DbType): RestoreStrategyOption[] {
  if (dbType === 'dameng') {
    return [{ label: '恢复到新 Schema（目标必须不存在）', value: 'restore_to_new' }]
  }
  return [
    { label: '恢复到新库（安全推荐）', value: 'restore_to_new' },
    { label: '覆盖已有库（先删除再创建）', value: 'overwrite_existing' },
  ]
}

export function suggestedRestoreTarget(
  sourceName: string,
  dbType: DbType,
  selectedTarget?: string,
  strategy: RestoreStrategyOption['value'] = 'restore_to_new',
): string {
  if (strategy === 'overwrite_existing' && dbType !== 'dameng') {
    return selectedTarget?.trim() || sourceName
  }
  return `${sourceName}_restore`
}

export function restoreModeOptions(dbType: DbType): RestoreModeOption[] {
  const modes: RestoreModeOption[] = [
    { label: '完整恢复（结构 + 数据）', value: 'restore_all' },
    { label: '仅恢复结构', value: 'structure_only' },
  ]
  if (dbType !== 'dameng') modes.push({ label: '仅恢复数据', value: 'data_only' })
  return modes
}

export function isManifestModeRestorable(manifestMode: string, targetDbType: DbType): boolean {
  return targetDbType !== 'dameng' || manifestMode !== 'data_only'
}
