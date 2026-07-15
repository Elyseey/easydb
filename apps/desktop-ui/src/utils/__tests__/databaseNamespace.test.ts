import { describe, expect, it } from 'vitest'
import { databaseNamespaceLabel } from '../databaseNamespace'

describe('database namespace label', () => {
  it('uses Schema for Dameng and database for MySQL', () => {
    expect(databaseNamespaceLabel('dameng')).toBe('Schema')
    expect(databaseNamespaceLabel('mysql')).toBe('数据库')
  })
})
