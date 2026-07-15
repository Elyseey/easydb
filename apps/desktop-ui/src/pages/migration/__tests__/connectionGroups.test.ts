import { describe, expect, it } from 'vitest'
import type { ConnectionConfig } from '@/types'
import { groupConnectionsByDatabaseType } from '../connectionGroups'

const connection = (
  id: string,
  name: string,
  dbType: ConnectionConfig['dbType'],
): ConnectionConfig => ({
  id,
  name,
  dbType,
  host: '127.0.0.1',
  port: dbType === 'dameng' ? 5236 : 3306,
  username: 'tester',
  password: '',
  status: 'disconnected',
})

describe('migration connection groups', () => {
  it('groups connections by database type with counts', () => {
    const groups = groupConnectionsByDatabaseType([
      connection('dm-1', '达梦生产库', 'dameng'),
      connection('mysql-1', 'MySQL 生产库', 'mysql'),
      connection('mysql-2', 'MySQL 测试库', 'mysql'),
    ])

    expect(groups.map((group) => group.label)).toEqual(['MySQL (2)', '达梦 (1)'])
    expect(groups[0].connections.map((item) => item.id)).toEqual(['mysql-1', 'mysql-2'])
    expect(groups[1].connections.map((item) => item.id)).toEqual(['dm-1'])
  })

  it('omits database types with no available connections', () => {
    const groups = groupConnectionsByDatabaseType([
      connection('dm-1', '达梦生产库', 'dameng'),
    ])

    expect(groups).toHaveLength(1)
    expect(groups[0]).toMatchObject({ dbType: 'dameng', label: '达梦 (1)' })
  })
})
