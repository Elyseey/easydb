import type { SqlResult } from '@/types'

export const DEFAULT_SQL_PREVIEW_PAGE_SIZE = 200
export const MAX_SQL_PREVIEW_CELL_CHARS = 4096
export const TRUNCATED_SQL_CELL_SUFFIX = ' …[truncated]'
const DEFAULT_SQL_CELL_DISPLAY_CHARS = 160

export interface SqlPreviewRequest {
  connectionId: string
  database: string
  sql: string
  offset?: number
  pageSize?: number
  maxCellChars?: number
}

export async function refreshSqlPreviewResult(
  target: SqlResult,
  queryPreview: (request: SqlPreviewRequest) => Promise<SqlResult>,
): Promise<SqlResult> {
  if (target.type !== 'query' || !target.connectionId || !target.database || !target.sql) {
    throw new Error('无法确定需要刷新的原始查询')
  }

  const refreshed = await queryPreview({
    connectionId: target.connectionId,
    database: target.database,
    sql: target.sql,
    offset: 0,
    pageSize: target.pageSize ?? DEFAULT_SQL_PREVIEW_PAGE_SIZE,
    maxCellChars: MAX_SQL_PREVIEW_CELL_CHARS,
  })
  if (refreshed.type === 'error') {
    throw new Error(refreshed.error ?? '刷新查询结果失败')
  }

  return {
    ...refreshed,
    connectionId: target.connectionId,
    database: target.database,
  }
}

export function normalizeExecutableSql(sql: string): string {
  return sql.trim().replace(/;+\s*$/, '').trim()
}

export function isPreviewableSql(sql: string): boolean {
  const normalized = normalizeExecutableSql(sql)
  if (!normalized) return false
  if (normalized.includes(';')) return false

  // 必须以 SELECT/WITH 开头（SHOW/DESC/DESCRIBE/EXPLAIN 不支持 LIMIT/OFFSET 包装）
  if (!/^(select|with)\b/i.test(normalized)) return false

  // 聚合函数查询（COUNT/SUM/AVG/MAX/MIN）通常只返回一行，不需要分页
  // 例如：SELECT COUNT(*) FROM table, SELECT MAX(id) FROM table
  if(/\b(COUNT|SUM|AVG|MAX|MIN)\s*\([^)]*\)/i.test(normalized)) {
    // 但如果有 GROUP BY，可能返回多行，需要分页
    if (!/\bGROUP\s+BY\b/i.test(normalized)) {
      return false
    }
  }

  // 没有 FROM 子句的 SELECT（如 SELECT 1, SELECT NOW()）只返回一行
  if (/^select\b/i.test(normalized) && !/\bFROM\b/i.test(normalized)) {
    return false
  }

  // 已经包含 LIMIT 的查询，用户已自行控制行数，不需要再包装
  if(/\bLIMIT\b/i.test(normalized)) {
    return false
  }

  return true
}

export function formatSqlCell(value: unknown): string {
  if (value === null || value === undefined) return 'NULL'
  return String(value)
}

export function isSqlCellTruncated(value: string): boolean {
  return value.endsWith(TRUNCATED_SQL_CELL_SUFFIX)
}

export function stripSqlCellTruncationMarker(value: string): string {
  return isSqlCellTruncated(value)
    ? value.slice(0, -TRUNCATED_SQL_CELL_SUFFIX.length)
    : value
}

export function previewSqlCellText(value: unknown, maxChars = DEFAULT_SQL_CELL_DISPLAY_CHARS): string {
  const text = stripSqlCellTruncationMarker(formatSqlCell(value))
  if (text.length <= maxChars) return text
  return `${text.slice(0, maxChars)}…`
}

export function mergeSqlPreviewResult(current: SqlResult, next: SqlResult): SqlResult {
  const mergedRows = [...(current.rows ?? []), ...(next.rows ?? [])]

  return {
    ...current,
    ...next,
    rows: mergedRows,
    loadedRows: mergedRows.length,
    truncatedCellCount: (current.truncatedCellCount ?? 0) + (next.truncatedCellCount ?? 0),
    duration: (current.duration ?? 0) + (next.duration ?? 0),
  }
}

export function hasKnownAffectedRows(result: SqlResult): result is SqlResult & { affectedRows: number } {
  return typeof result.affectedRows === 'number'
}

export function sqlAffectedRowsSummary(results: SqlResult[]): string | null {
  const updateResults = results.filter((result) => result.type === 'update')
  const knownResults = updateResults.filter(hasKnownAffectedRows)
  if (knownResults.length === 0) return null
  const totalAffected = knownResults.reduce((sum, result) => sum + result.affectedRows, 0)
  return knownResults.length === updateResults.length
    ? `共影响 ${totalAffected} 行`
    : `已知影响 ${totalAffected} 行`
}

export function sqlQueryRowsSummary(results: SqlResult[]): string | null {
  const queryResults = results.filter((result) => result.type === 'query')
  if (queryResults.length === 0) return null

  const loadedRows = queryResults.reduce(
    (sum, result) => sum + (result.loadedRows ?? result.rows?.length ?? 0),
    0
  )
  const knownTotalRows = queryResults
    .map((result) => result.totalRows)
    .filter((totalRows): totalRows is number => typeof totalRows === 'number')

  if (queryResults.length === 1) {
    const [result] = queryResults
    const loaded = result.loadedRows ?? result.rows?.length ?? 0
    const total = result.totalRows
    return typeof total === 'number'
      ? `已加载 ${loaded} 行 / 共 ${total} 行`
      : `已加载 ${loaded} 行${result.hasMore ? '（还有更多）' : ''}`
  }

  const totalText = knownTotalRows.length === queryResults.length
    ? ` / 共 ${knownTotalRows.reduce((sum, totalRows) => sum + totalRows, 0)} 行`
    : ''
  return `已加载 ${loadedRows} 行${totalText} · ${queryResults.length} 个结果集`
}

export function sqlBatchSummary(results: SqlResult[], durationMs: number): string | null {
  if (results.length === 0 || results.some((result) => result.type === 'error')) return null

  const querySummary = sqlQueryRowsSummary(results)
  if (querySummary) return `${querySummary} · 耗时 ${durationMs}ms`

  return `共 ${results.length} 条语句 · 耗时 ${durationMs}ms`
}

export function sqlSuccessToastMessage(results: SqlResult[]): string {
  const summary = sqlAffectedRowsSummary(results)
  return summary ? `执行成功，${summary}` : '执行成功'
}

export function sqlUpdateResultText(result: SqlResult): string {
  return hasKnownAffectedRows(result) ? `影响 ${result.affectedRows} 行` : '执行成功'
}
