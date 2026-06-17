import { describe, expect, it } from 'vitest'
import type { SqlResult } from '@/types'
import { sqlBatchSummary, sqlUpdateResultText } from '../queryPreview'

function sqlResult(overrides: Partial<SqlResult>): SqlResult {
  return {
    type: 'query',
    duration: 12,
    sql: 'SELECT * FROM demo',
    executedAt: '2026-06-06 10:00:00',
    ...overrides,
  }
}

describe('sqlBatchSummary', () => {
  it('uses row counts for a single query result', () => {
    expect(sqlBatchSummary([
      sqlResult({ rows: [{ id: 1 }, { id: 2 }], loadedRows: 2, totalRows: 8 }),
    ], 12)).toBe('已加载 2 行 / 共 8 行 · 耗时 12ms')
  })

  it('uses row counts and result set count for multiple query results', () => {
    expect(sqlBatchSummary([
      sqlResult({ loadedRows: 2, totalRows: 8 }),
      sqlResult({ loadedRows: 3, totalRows: 9 }),
    ], 20)).toBe('已加载 5 行 / 共 17 行 · 2 个结果集 · 耗时 20ms')
  })

  it('keeps statement count wording for non-query batches', () => {
    expect(sqlBatchSummary([
      sqlResult({ type: 'update', affectedRows: 3, sql: 'UPDATE demo SET name = name' }),
    ], 9)).toBe('共 1 条语句 · 耗时 9ms')
  })
})

describe('sqlUpdateResultText', () => {
  it('does not render null affected rows as a row count', () => {
    expect(sqlUpdateResultText(sqlResult({
      type: 'update',
      affectedRows: null,
      sql: "COMMENT ON COLUMN demo.deleted IS '是否删除'",
    }))).toBe('执行成功')
  })
})
