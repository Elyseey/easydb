import { describe, expect, it } from 'vitest'
import type { ColumnInfo, SqlResult } from '@/types'
import { analyzeEditability, resolveInsertTargetTableName } from '../editabilityAnalyzer'

const columns: ColumnInfo[] = [
  { name: 'id', type: 'BIGINT', nullable: false, isPrimaryKey: true, isAutoIncrement: false },
  { name: 'name', type: 'VARCHAR', nullable: true, isPrimaryKey: false, isAutoIncrement: false },
]

function result(selectedColumns: string[]): SqlResult {
  return {
    type: 'query',
    columns: selectedColumns,
    rows: [{ name: 'before' }],
    duration: 1,
    sql: 'SELECT name FROM demo',
    executedAt: '2026-07-02 10:00:00',
    connectionId: 'connection-1',
    database: 'demo_db',
  }
}

const metadataApi = {
  tableDefinition: async () => ({
    table: { name: 'demo', type: 'table' },
    columns,
  }),
}

describe('analyzeEditability', () => {
  it('rejects a query result that does not include the table primary key', async () => {
    await expect(analyzeEditability(result(['name']), metadataApi)).resolves.toMatchObject({
      editable: false,
      reason: 'missing_primary_key_columns',
      missingPrimaryKeys: ['id'],
    })
  })

  it('allows editing when every primary key column is present', async () => {
    await expect(analyzeEditability(result(['id', 'name']), metadataApi)).resolves.toMatchObject({
      editable: true,
      primaryKeys: ['id'],
    })
  })
})

describe('resolveInsertTargetTableName', () => {
  it('returns the target for a single-table detail query', () => {
    expect(resolveInsertTargetTableName('SELECT id, name FROM demo WHERE id > 1')).toBe('demo')
  })

  it('rejects queries whose INSERT target is ambiguous', () => {
    expect(resolveInsertTargetTableName('SELECT * FROM a JOIN b ON a.id = b.id')).toBeNull()
    expect(resolveInsertTargetTableName('SELECT COUNT(*) FROM demo')).toBeNull()
    expect(resolveInsertTargetTableName('SELECT * FROM (SELECT * FROM demo) nested')).toBeNull()
    expect(resolveInsertTargetTableName('SELECT * FROM a UNION SELECT * FROM b')).toBeNull()
  })
})
