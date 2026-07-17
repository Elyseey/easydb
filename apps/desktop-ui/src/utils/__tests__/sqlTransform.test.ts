import { describe, expect, it } from 'vitest'
import type { DbType } from '@/types'
import {
  beautifySql,
  compactSql,
  SqlTransformError,
  sqlFormatterDialectFor,
} from '../sqlTransform'

describe('sqlFormatterDialectFor', () => {
  it.each<[DbType, string]>([
    ['mysql', 'mysql'],
    ['dameng', 'plsql'],
    ['tdengine', 'mysql'],
    ['postgresql', 'postgresql'],
    ['oracle', 'plsql'],
    ['sqlserver', 'transactsql'],
    ['sqlite', 'sqlite'],
  ])('maps %s to %s', (dbType, dialect) => {
    expect(sqlFormatterDialectFor(dbType)).toBe(dialect)
  })
})

describe('beautifySql', () => {
  it('uses the fixed uppercase, two-space style', async () => {
    const result = await beautifySql(
      'select id,name from users where enabled=1 and name is not null',
      'mysql',
    )

    expect(result).toContain('SELECT')
    expect(result).toContain('\n  id,')
    expect(result).toContain('\nFROM')
    expect(result).toContain('\nWHERE')
  })

  it.each<DbType>(['mysql', 'dameng', 'tdengine', 'postgresql', 'oracle', 'sqlserver', 'sqlite'])(
    'formats a basic query for %s',
    async (dbType) => {
      await expect(beautifySql('select 1 from sample_table', dbType)).resolves.toContain('SELECT')
    },
  )

  it('does not return a partial result when parsing fails', async () => {
    await expect(beautifySql("select 'unterminated", 'mysql')).rejects.toBeInstanceOf(SqlTransformError)
  })

  it.each([
    'CREATE STABLE meters (ts TIMESTAMP, value DOUBLE) TAGS (location VARCHAR(64))',
    'SELECT DISTINCT tbname, location FROM meters',
    'SELECT _wstart, AVG(value) FROM meters PARTITION BY tbname INTERVAL(1m)',
  ])('formats TDengine syntax with the explicit MySQL-compatible fallback', async (sql) => {
    await expect(beautifySql(sql, 'tdengine')).resolves.toMatch(/^(CREATE|SELECT)/)
  })
})

describe('compactSql', () => {
  it('collapses ordinary whitespace into a compact query', () => {
    expect(compactSql('  SELECT  *\n  FROM users\n WHERE id = 1;  ', 'mysql'))
      .toBe('SELECT * FROM users WHERE id = 1;')
  })

  it('preserves whitespace inside strings and quoted identifiers', () => {
    expect(compactSql(`SELECT 'a  b\nc', \`odd  column\` FROM demo`, 'mysql'))
      .toBe(`SELECT 'a  b\nc', \`odd  column\` FROM demo`)
    expect(compactSql('SELECT "full  name", [SQL  Server] FROM demo', 'sqlserver'))
      .toBe('SELECT "full  name", [SQL  Server] FROM demo')
  })

  it('preserves PostgreSQL dollar-quoted text and placeholders', () => {
    const sql = 'SELECT  $$a  b\nc$$, $tag$x  y\nz$tag$, $1\n FROM demo WHERE id = :id'
    expect(compactSql(sql, 'postgresql')).toBe(
      'SELECT $$a  b\nc$$, $tag$x  y\nz$tag$, $1 FROM demo WHERE id = :id',
    )
  })

  it('preserves Oracle q-quoted text', () => {
    const sql = "SELECT  q'[a  b\nc]'\n FROM dual"
    expect(compactSql(sql, 'oracle')).toBe("SELECT q'[a  b\nc]' FROM dual")
  })

  it('preserves block comments and the newline that terminates line comments', () => {
    const sql = 'SELECT  /*+ INDEX(t idx)\n keep */  *\n-- keep  this\n  FROM t'
    expect(compactSql(sql, 'mysql')).toBe(
      'SELECT /*+ INDEX(t idx)\n keep */ * -- keep  this\nFROM t',
    )
  })

  it('supports MySQL hash comments', () => {
    expect(compactSql('SELECT 1  # keep\n FROM dual', 'mysql'))
      .toBe('SELECT 1 # keep\nFROM dual')
  })

  it('does not confuse PostgreSQL JSON operators with MySQL comments', () => {
    expect(compactSql("SELECT  payload  #>>  '{user,name}'\n FROM events", 'postgresql'))
      .toBe("SELECT payload #>> '{user,name}' FROM events")
  })

  it.each<[string, DbType]>([
    ["SELECT 'unterminated", 'mysql'],
    ['SELECT "unterminated', 'mysql'],
    ['SELECT `unterminated', 'mysql'],
    ['SELECT [unterminated', 'sqlserver'],
    ['SELECT /* unterminated', 'mysql'],
    ['SELECT $tag$unterminated', 'postgresql'],
    ["SELECT q'[unterminated' FROM dual", 'oracle'],
  ])('rejects malformed protected regions without returning partial SQL', (sql, dbType) => {
    expect(() => compactSql(sql, dbType)).toThrow(SqlTransformError)
  })
})
