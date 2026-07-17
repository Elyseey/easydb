import { describe, expect, it } from 'vitest'
import { supportsDatabaseTaskPair, supportsDatabaseTaskRole } from '../databaseTaskPairs'

describe('database task pair capabilities', () => {
  it('supports all registered MySQL and Dameng migration pairs', () => {
    expect(supportsDatabaseTaskPair('migration', 'mysql', 'mysql')).toBe(true)
    expect(supportsDatabaseTaskPair('migration', 'mysql', 'dameng')).toBe(true)
    expect(supportsDatabaseTaskPair('migration', 'dameng', 'mysql')).toBe(true)
    expect(supportsDatabaseTaskPair('migration', 'dameng', 'dameng')).toBe(true)
    expect(supportsDatabaseTaskRole('migration', 'dameng', 'source')).toBe(true)
    expect(supportsDatabaseTaskRole('migration', 'dameng', 'target')).toBe(true)
  })

  it.each(['sync', 'structureCompare'] as const)('supports same-database MySQL and Dameng pairs only for %s', (feature) => {
    expect(supportsDatabaseTaskPair(feature, 'mysql', 'mysql')).toBe(true)
    expect(supportsDatabaseTaskPair(feature, 'dameng', 'dameng')).toBe(true)
    expect(supportsDatabaseTaskPair(feature, 'mysql', 'dameng')).toBe(false)
    expect(supportsDatabaseTaskPair(feature, 'dameng', 'mysql')).toBe(false)
    expect(supportsDatabaseTaskRole(feature, 'dameng', 'source')).toBe(true)
    expect(supportsDatabaseTaskRole(feature, 'dameng', 'target')).toBe(true)
  })
})
