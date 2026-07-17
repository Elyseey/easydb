import { describe, expect, it } from 'vitest'
import {
  isRestoreDatabaseTypeCompatible,
  isManifestModeRestorable,
  restoreModeOptions,
  restoreStrategyOptions,
  suggestedRestoreTarget,
} from '../restorePolicy'

describe('restore policy', () => {
  it('only offers restore-to-new for Dameng', () => {
    expect(restoreStrategyOptions('dameng')).toEqual([
      { label: '恢复到新 Schema（目标必须不存在）', value: 'restore_to_new' },
    ])
  })

  it('keeps overwrite restore available for MySQL', () => {
    expect(restoreStrategyOptions('mysql').map(option => option.value)).toEqual([
      'restore_to_new',
      'overwrite_existing',
    ])
  })

  it('requires the backup and target database types to match', () => {
    expect(isRestoreDatabaseTypeCompatible('DAMENG', 'dameng')).toBe(true)
    expect(isRestoreDatabaseTypeCompatible('mysql', 'dameng')).toBe(false)
  })

  it('does not reuse the selected existing Schema as the Dameng restore target', () => {
    expect(suggestedRestoreTarget('ENERGY', 'dameng', 'ENERGY')).toBe('ENERGY_restore')
    expect(suggestedRestoreTarget('shop', 'mysql', 'shop')).toBe('shop_restore')
    expect(suggestedRestoreTarget('shop', 'mysql', 'shop', 'overwrite_existing')).toBe('shop')
  })

  it('does not offer data-only restore into a new Dameng Schema', () => {
    expect(restoreModeOptions('dameng').map(option => option.value)).toEqual([
      'restore_all',
      'structure_only',
    ])
    expect(isManifestModeRestorable('data_only', 'dameng')).toBe(false)
    expect(isManifestModeRestorable('data_only', 'mysql')).toBe(true)
  })
})
