import { describe, expect, it } from 'vitest'
import { supportsDatabaseTaskPair, supportsDatabaseTaskRole } from '../databaseTaskPairs'

describe('database task pair capabilities', () => {
  it('supports MySQL to MySQL and MySQL to Dameng migration only', () => {
    expect(supportsDatabaseTaskPair('migration', 'mysql', 'mysql')).toBe(true)
    expect(supportsDatabaseTaskPair('migration', 'mysql', 'dameng')).toBe(true)
    expect(supportsDatabaseTaskPair('migration', 'dameng', 'mysql')).toBe(false)
    expect(supportsDatabaseTaskRole('migration', 'dameng', 'source')).toBe(false)
    expect(supportsDatabaseTaskRole('migration', 'dameng', 'target')).toBe(true)
  })

  it.each(['sync', 'structureCompare'] as const)('keeps %s restricted to MySQL pairs', (feature) => {
    expect(supportsDatabaseTaskPair(feature, 'mysql', 'mysql')).toBe(true)
    expect(supportsDatabaseTaskPair(feature, 'mysql', 'dameng')).toBe(false)
    expect(supportsDatabaseTaskPair(feature, 'dameng', 'dameng')).toBe(false)
    expect(supportsDatabaseTaskRole(feature, 'dameng', 'source')).toBe(false)
    expect(supportsDatabaseTaskRole(feature, 'dameng', 'target')).toBe(false)
  })
})
