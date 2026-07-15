import type { DbType } from '@/types'

export function databaseNamespaceLabel(dbType: DbType | null | undefined): 'Schema' | '数据库' {
  return dbType === 'dameng' ? 'Schema' : '数据库'
}
