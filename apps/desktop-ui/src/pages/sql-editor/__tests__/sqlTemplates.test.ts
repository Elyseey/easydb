import { describe, expect, it } from 'vitest'
import type { DbType } from '@/types'
import {
  BUILTIN_SQL_TEMPLATES,
  getSqlTemplates,
  isSqlTemplateAvailable,
  type SqlTemplate,
} from '../sqlTemplates'

describe('SQL templates', () => {
  it('contains the agreed built-in abbreviations', () => {
    expect(BUILTIN_SQL_TEMPLATES.map((template) => template.prefix))
      .toEqual(['sel', 'selw', 'selc', 'selj', 'ins', 'upd', 'del', 'cte'])
  })

  it.each(['upd', 'del'])('%s always includes an explicit WHERE placeholder', (prefix) => {
    const template = BUILTIN_SQL_TEMPLATES.find((candidate) => candidate.prefix === prefix)
    expect(template?.risk).toBe('write')
    expect(template?.body).toMatch(/\nWHERE\n/)
    expect(template?.body).toMatch(/\$\{\d+:condition\}/)
  })

  it('returns no templates when the setting is disabled', () => {
    expect(getSqlTemplates('mysql', false)).toEqual([])
  })

  it.each<DbType>(['mysql', 'dameng', 'postgresql', 'oracle', 'sqlserver', 'sqlite'])(
    'exposes common templates for %s',
    (dbType) => {
      expect(getSqlTemplates(dbType, true)).toHaveLength(BUILTIN_SQL_TEMPLATES.length)
    },
  )

  it('filters dialect-specific templates by dbType', () => {
    const mysqlOnly: SqlTemplate = {
      id: 'mysql-only',
      prefix: 'mysqlx',
      label: 'MySQL only',
      description: 'MySQL only example',
      body: 'SELECT 1;$0',
      risk: 'read',
      dbTypes: ['mysql'],
    }

    expect(isSqlTemplateAvailable(mysqlOnly, 'mysql')).toBe(true)
    expect(isSqlTemplateAvailable(mysqlOnly, 'postgresql')).toBe(false)
  })

  it('uses ordered tab stops and a final cursor in every template', () => {
    for (const template of BUILTIN_SQL_TEMPLATES) {
      expect(template.body).toContain('${1:')
      expect(template.body).toContain('$0')
    }
  })
})
