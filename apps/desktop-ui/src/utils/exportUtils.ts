import { invoke } from '@tauri-apps/api/core'
import type { DbType } from '@/types'

/** 数据导出工具 — 前端生成内容，Tauri 原生对话框选择保存位置 */

export type ExportFormat = 'csv' | 'json' | 'sql'

export interface ExportFile {
  content: string
  suggestedName: string
  extension: ExportFormat
}

/** 将数据行转为 CSV 字符串 */
export function rowsToCsv(columns: string[], rows: Record<string, unknown>[]): string {
  const escapeCsvCell = (value: unknown): string => {
    const str = value === null || value === undefined ? '' : String(value)
    // 包含逗号、换行、双引号时需用双引号包裹并转义
    if (str.includes(',') || str.includes('\n') || str.includes('"')) {
      return `"${str.replace(/"/g, '""')}"`
    }
    return str
  }

  const header = columns.map(escapeCsvCell).join(',')
  const body = rows.map(row =>
    columns.map(col => escapeCsvCell(row[col])).join(',')
  ).join('\n')

  return `${header}\n${body}`
}

/** 将数据行转为格式化 JSON 字符串 */
export function rowsToJson(columns: string[], rows: Record<string, unknown>[]): string {
  // 只保留 columns 中的字段，过滤掉前端添加的 _key 等临时字段
  const cleanRows = rows.map(row => {
    const clean: Record<string, unknown> = {}
    for (const col of columns) {
      clean[col] = row[col] ?? null
    }
    return clean
  })
  return JSON.stringify(cleanRows, null, 2)
}

/** 按数据库方言引用标识符。 */
export function quoteSqlIdentifier(identifier: string, dbType: DbType): string {
  if (dbType === 'mysql') return `\`${identifier.replace(/`/g, '``')}\``
  if (dbType === 'sqlserver') return `[${identifier.replace(/]/g, ']]')}]`
  return `"${identifier.replace(/"/g, '""')}"`
}

function sqlBooleanLiteral(value: boolean, dbType: DbType): string {
  return dbType === 'mysql' || dbType === 'sqlserver' || dbType === 'sqlite'
    ? (value ? '1' : '0')
    : (value ? 'TRUE' : 'FALSE')
}

function sqlStringValue(value: unknown): string {
  if (value instanceof Date) return value.toISOString()
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value) ?? String(value)
    } catch {
      return String(value)
    }
  }
  return String(value)
}

/** 将数据行转为当前数据库方言的 SQL INSERT 语句。 */
export function rowsToSqlInsert(
  tableName: string,
  columns: string[],
  rows: Record<string, unknown>[],
  dbType: DbType,
): string {
  if (rows.length === 0) return `-- 表 ${tableName} 无数据\n`

  const escapeSqlValue = (value: unknown): string => {
    if (value === null || value === undefined) return 'NULL'
    if (typeof value === 'number' && Number.isFinite(value)) return String(value)
    if (typeof value === 'bigint') return String(value)
    if (typeof value === 'boolean') return sqlBooleanLiteral(value, dbType)
    const str = sqlStringValue(value)
    return `'${str.replace(/'/g, "''")}'`
  }

  const quotedTableName = quoteSqlIdentifier(tableName, dbType)
  const colList = columns.map(column => quoteSqlIdentifier(column, dbType)).join(', ')
  const statements = rows.map(row => {
    const values = columns.map(col => escapeSqlValue(row[col])).join(', ')
    return `INSERT INTO ${quotedTableName} (${colList}) VALUES (${values});`
  })

  return statements.join('\n')
}

function timestampedName(filenameBase: string, extension: ExportFormat): string {
  const timestamp = new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-')
  return `${filenameBase}_${timestamp}.${extension}`
}

export function createResultExportFile(
  columns: string[],
  rows: Record<string, unknown>[],
  format: 'csv' | 'json',
  filenameBase = 'query_result',
): ExportFile {
  const rawContent = format === 'csv' ? rowsToCsv(columns, rows) : rowsToJson(columns, rows)
  return {
    content: format === 'csv' ? `\uFEFF${rawContent}` : rawContent,
    suggestedName: timestampedName(filenameBase, format),
    extension: format,
  }
}

export function createTableExportFile(
  tableName: string,
  columns: string[],
  rows: Record<string, unknown>[],
  format: ExportFormat,
  dbType?: DbType,
): ExportFile {
  if (format === 'sql') {
    if (!dbType) throw new Error('导出 SQL INSERT 时缺少数据库类型')
    return {
      content: rowsToSqlInsert(tableName, columns, rows, dbType),
      suggestedName: timestampedName(tableName, format),
      extension: format,
    }
  }
  return createResultExportFile(columns, rows, format, tableName)
}

/** 打开系统“另存为”对话框。用户取消时返回 null。 */
export function saveExportFile(file: ExportFile): Promise<string | null> {
  return invoke<string | null>('save_export_file', {
    suggestedName: file.suggestedName,
    content: file.content,
    extension: file.extension,
  })
}
