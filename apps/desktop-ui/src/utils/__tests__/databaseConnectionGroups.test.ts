import { describe, expect, it } from 'vitest'
import type { ConnectionConfig } from '@/types'
import {
  groupConnectionsByDatabaseType,
  toDatabaseConnectionOptionGroups,
} from '../databaseConnectionGroups'

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

describe('database connection groups', () => {
  const connections = [
    connection('dm-1', '达梦生产库', 'dameng'),
    connection('mysql-1', 'MySQL 生产库', 'mysql'),
    connection('mysql-2', 'MySQL 测试库', 'mysql'),
  ]

  it('groups connections by database type with counts', () => {
    const groups = groupConnectionsByDatabaseType(connections)

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

  it('maps each database group into Ant Design-compatible option groups', () => {
    const groups = toDatabaseConnectionOptionGroups(connections, (item) => ({
      value: item.id,
      label: item.name,
    }))

    expect(groups).toEqual([
      {
        label: 'MySQL (2)',
        options: [
          { value: 'mysql-1', label: 'MySQL 生产库' },
          { value: 'mysql-2', label: 'MySQL 测试库' },
        ],
      },
      {
        label: '达梦 (1)',
        options: [{ value: 'dm-1', label: '达梦生产库' }],
      },
    ])
  })
})
