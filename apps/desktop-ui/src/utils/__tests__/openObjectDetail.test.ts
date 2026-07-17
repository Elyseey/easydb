import { describe, expect, it } from 'vitest'
import { buildOpenObjectDetailRequest } from '../openObjectDetail'

describe('buildOpenObjectDetailRequest', () => {
  it('keeps the current query connection identity in the object jump request', () => {
    expect(buildOpenObjectDetailRequest(
      'mysql-connection',
      'emission',
      { name: 'emi_access_records', type: 'table' },
    )).toEqual({
      connectionId: 'mysql-connection',
      database: 'emission',
      name: 'emi_access_records',
      objectType: 'table',
    })
  })

  it('rejects unsupported metadata object types', () => {
    expect(buildOpenObjectDetailRequest(
      'dameng-connection',
      'ENERGY',
      { name: 'UNKNOWN_OBJECT', type: 'sequence' },
    )).toBeNull()
  })
})
