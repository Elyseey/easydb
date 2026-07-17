import { describe, expect, it } from 'vitest'
import { createResultExportFile, createTableExportFile, rowsToCsv, rowsToSqlInsert } from '../exportUtils'

describe('exportUtils', () => {
  it('keeps query result column order and escapes CSV cells', () => {
    expect(rowsToCsv(['name', 'note'], [{ note: 'a,b', name: '设备' }]))
      .toBe('name,note\n设备,"a,b"')
  })

  it('prepares UTF-8 CSV content for the native save dialog', () => {
    const file = createResultExportFile(['id'], [{ id: 1 }], 'csv', 'result')

    expect(file.content).toBe('\uFEFFid\n1')
    expect(file.suggestedName).toMatch(/^result_\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}\.csv$/)
    expect(file.extension).toBe('csv')
  })

  it('prepares SQL INSERT content for table exports', () => {
    const file = createTableExportFile('demo', ['id'], [{ id: 1 }], 'sql', 'mysql')

    expect(file.content).toBe('INSERT INTO `demo` (`id`) VALUES (1);')
    expect(file.extension).toBe('sql')
  })

  it('uses Dameng identifier quoting and preserves SQL values', () => {
    expect(rowsToSqlInsert(
      '设备表',
      ['编号', '启用', '备注', '空值'],
      [{ 编号: 7, 启用: true, 备注: "第一行\nO'Reilly", 空值: null }],
      'dameng',
    )).toBe(
      'INSERT INTO "设备表" ("编号", "启用", "备注", "空值") VALUES (7, TRUE, \'第一行\nO\'\'Reilly\', NULL);'
    )
  })

  it('escapes dialect-specific identifier delimiters', () => {
    expect(rowsToSqlInsert('odd`table', ['a`b'], [{ 'a`b': 1 }], 'mysql'))
      .toBe('INSERT INTO `odd``table` (`a``b`) VALUES (1);')
    expect(rowsToSqlInsert('odd]table', ['a]b'], [{ 'a]b': false }], 'sqlserver'))
      .toBe('INSERT INTO [odd]]table] ([a]]b]) VALUES (0);')
  })
})
