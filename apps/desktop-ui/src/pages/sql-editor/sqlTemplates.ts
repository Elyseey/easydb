import type { DbType } from '@/types'
import type { editor } from 'monaco-editor'

export type SqlTemplateRisk = 'read' | 'write'

export interface SqlTemplate {
  id: string
  prefix: string
  label: string
  description: string
  body: string
  risk: SqlTemplateRisk
  dbTypes?: readonly DbType[]
}

export const BUILTIN_SQL_TEMPLATES: readonly SqlTemplate[] = [
  {
    id: 'select-basic',
    prefix: 'sel',
    label: '基础查询',
    description: 'SELECT 字段 FROM 表',
    body: [
      'SELECT',
      '  ${1:*}',
      'FROM',
      '  ${2:table_name};$0',
    ].join('\n'),
    risk: 'read',
  },
  {
    id: 'select-where',
    prefix: 'selw',
    label: '条件查询',
    description: 'SELECT 字段 FROM 表 WHERE 条件',
    body: [
      'SELECT',
      '  ${1:*}',
      'FROM',
      '  ${2:table_name}',
      'WHERE',
      '  ${3:condition};$0',
    ].join('\n'),
    risk: 'read',
  },
  {
    id: 'select-count',
    prefix: 'selc',
    label: '统计查询',
    description: 'SELECT COUNT(*) FROM 表 WHERE 条件',
    body: [
      'SELECT',
      '  COUNT(${1:*})',
      'FROM',
      '  ${2:table_name}',
      'WHERE',
      '  ${3:condition};$0',
    ].join('\n'),
    risk: 'read',
  },
  {
    id: 'select-join',
    prefix: 'selj',
    label: '关联查询',
    description: 'SELECT + JOIN + ON + WHERE',
    body: [
      'SELECT',
      '  ${1:a.*},',
      '  ${2:b.*}',
      'FROM',
      '  ${3:table_a} AS a',
      '${4:INNER} JOIN',
      '  ${5:table_b} AS b',
      'ON',
      '  ${6:a.id = b.foreign_id}',
      'WHERE',
      '  ${7:condition};$0',
    ].join('\n'),
    risk: 'read',
  },
  {
    id: 'insert-row',
    prefix: 'ins',
    label: '插入数据',
    description: 'INSERT INTO 表 (字段) VALUES (值)',
    body: [
      'INSERT INTO ${1:table_name} (',
      '  ${2:column_name}',
      ')',
      'VALUES (',
      '  ${3:value}',
      ');$0',
    ].join('\n'),
    risk: 'write',
  },
  {
    id: 'update-safe',
    prefix: 'upd',
    label: '条件更新',
    description: 'UPDATE 表 SET 字段 = 值 WHERE 条件',
    body: [
      'UPDATE',
      '  ${1:table_name}',
      'SET',
      '  ${2:column_name} = ${3:value}',
      'WHERE',
      '  ${4:condition};$0',
    ].join('\n'),
    risk: 'write',
  },
  {
    id: 'delete-safe',
    prefix: 'del',
    label: '条件删除',
    description: 'DELETE FROM 表 WHERE 条件',
    body: [
      'DELETE FROM',
      '  ${1:table_name}',
      'WHERE',
      '  ${2:condition};$0',
    ].join('\n'),
    risk: 'write',
  },
  {
    id: 'common-table-expression',
    prefix: 'cte',
    label: 'CTE 查询',
    description: 'WITH 临时结果 AS (...) SELECT ...',
    body: [
      'WITH ${1:cte_name} AS (',
      '  SELECT',
      '    ${2:*}',
      '  FROM',
      '    ${3:table_name}',
      '  WHERE',
      '    ${4:condition}',
      ')',
      'SELECT',
      '  ${5:*}',
      'FROM',
      '  ${1:cte_name};$0',
    ].join('\n'),
    risk: 'read',
  },
]

export function isSqlTemplateAvailable(template: SqlTemplate, dbType: DbType): boolean {
  return !template.dbTypes || template.dbTypes.includes(dbType)
}

export function getSqlTemplates(dbType: DbType, enabled: boolean): readonly SqlTemplate[] {
  if (!enabled) return []
  return BUILTIN_SQL_TEMPLATES.filter((template) => isSqlTemplateAvailable(template, dbType))
}

interface SqlSnippetController extends editor.IEditorContribution {
  insert: (
    template: string,
    options?: { undoStopBefore?: boolean; undoStopAfter?: boolean },
  ) => void
}

export function insertSqlTemplate(
  editorInstance: editor.IStandaloneCodeEditor,
  template: SqlTemplate,
): boolean {
  const snippetController = editorInstance.getContribution<SqlSnippetController>('snippetController2')
  if (!snippetController) return false

  editorInstance.focus()
  snippetController.insert(template.body, {
    undoStopBefore: true,
    undoStopAfter: true,
  })
  return true
}
