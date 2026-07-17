import { describe, expect, it, vi } from 'vitest'
import type { DbType } from '@/types'
import type { editor } from 'monaco-editor'
import {
  BUILTIN_SQL_TEMPLATES,
  getSqlTemplates,
  insertSqlTemplate,
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

  it('inserts a selected template through Monaco snippet mode', () => {
    const insert = vi.fn()
    const focus = vi.fn()
    const editorInstance = {
      focus,
      getContribution: vi.fn().mockReturnValue({ insert }),
    } as unknown as editor.IStandaloneCodeEditor

    expect(insertSqlTemplate(editorInstance, BUILTIN_SQL_TEMPLATES[0])).toBe(true)
    expect(editorInstance.getContribution).toHaveBeenCalledWith('snippetController2')
    expect(focus).toHaveBeenCalledOnce()
    expect(insert).toHaveBeenCalledWith(BUILTIN_SQL_TEMPLATES[0].body, {
      undoStopBefore: true,
      undoStopAfter: true,
    })
  })

  it('does not alter the editor when Monaco snippet mode is unavailable', () => {
    const editorInstance = {
      focus: vi.fn(),
      getContribution: vi.fn().mockReturnValue(null),
    } as unknown as editor.IStandaloneCodeEditor

    expect(insertSqlTemplate(editorInstance, BUILTIN_SQL_TEMPLATES[0])).toBe(false)
    expect(editorInstance.focus).not.toHaveBeenCalled()
  })
})
