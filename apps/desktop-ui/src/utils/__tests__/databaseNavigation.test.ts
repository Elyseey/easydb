import { describe, expect, it } from 'vitest'
import { supportsDatabaseNavigation } from '../databaseNavigation'

describe('database navigation capabilities', () => {
  it.each(['migration', 'sync', 'structureCompare', 'dataTracker', 'slowQuery'] as const)(
    'keeps MySQL %s navigation available',
    (capability) => {
      expect(supportsDatabaseNavigation('mysql', capability)).toBe(true)
    },
  )

  it('keeps supported Dameng task navigation available', () => {
    expect(supportsDatabaseNavigation('dameng', 'migration')).toBe(true)
    expect(supportsDatabaseNavigation('dameng', 'sync')).toBe(true)
    expect(supportsDatabaseNavigation('dameng', 'structureCompare')).toBe(true)
    expect(supportsDatabaseNavigation('dameng', 'dataTracker')).toBe(false)
    expect(supportsDatabaseNavigation('dameng', 'slowQuery')).toBe(false)
  })
})
