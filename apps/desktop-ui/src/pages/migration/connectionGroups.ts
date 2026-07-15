import type { ConnectionConfig, DbType } from '@/types'

const DATABASE_TYPE_ORDER: DbType[] = [
  'mysql',
  'dameng',
  'postgresql',
  'oracle',
  'sqlserver',
  'sqlite',
]

const DATABASE_TYPE_LABELS: Record<DbType, string> = {
  mysql: 'MySQL',
  dameng: '达梦',
  postgresql: 'PostgreSQL',
  oracle: 'Oracle',
  sqlserver: 'SQL Server',
  sqlite: 'SQLite',
}

export interface DatabaseConnectionGroup {
  dbType: DbType
  label: string
  connections: ConnectionConfig[]
}

export function groupConnectionsByDatabaseType(
  connections: ConnectionConfig[],
): DatabaseConnectionGroup[] {
  const connectionsByType = new Map<DbType, ConnectionConfig[]>()

  connections.forEach((connection) => {
    const groupConnections = connectionsByType.get(connection.dbType) ?? []
    groupConnections.push(connection)
    connectionsByType.set(connection.dbType, groupConnections)
  })

  return DATABASE_TYPE_ORDER.flatMap((dbType) => {
    const groupConnections = connectionsByType.get(dbType)
    if (!groupConnections?.length) return []

    return [{
      dbType,
      label: `${DATABASE_TYPE_LABELS[dbType]} (${groupConnections.length})`,
      connections: groupConnections,
    }]
  })
}
