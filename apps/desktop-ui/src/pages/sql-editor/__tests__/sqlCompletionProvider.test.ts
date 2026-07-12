import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CancellationToken, editor, languages, Position } from 'monaco-editor'

const metadataMocks = vi.hoisted(() => ({
  objects: vi.fn(),
  columns: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  metadataApi: metadataMocks,
}))

import { createSqlCompletionProvider, clearCompletionCache } from '../sqlCompletionProvider'

const monacoInstance = {
  languages: {
    CompletionItemKind: {
      Field: 3,
      Function: 1,
      Keyword: 17,
      Snippet: 28,
      Struct: 6,
    },
    CompletionItemInsertTextRule: {
      InsertAsSnippet: 4,
    },
  },
} as unknown as typeof import('monaco-editor')

function modelFor(word: string, line = word, startColumn = 1): editor.ITextModel {
  return {
    getWordUntilPosition: () => ({
      word,
      startColumn,
      endColumn: startColumn + word.length,
    }),
    getLineContent: () => line,
    getValue: () => line,
  } as unknown as editor.ITextModel
}

const position = { lineNumber: 1, column: 4 } as Position
const completionContext = {} as languages.CompletionContext
const cancellationToken = {} as CancellationToken

describe('createSqlCompletionProvider SQL templates', () => {
  beforeEach(() => {
    clearCompletionCache()
    metadataMocks.objects.mockReset().mockResolvedValue([])
    metadataMocks.columns.mockReset().mockResolvedValue([])
  })

  it('adds prioritized snippet suggestions when templates are enabled', async () => {
    const provider = createSqlCompletionProvider('conn', 'db', monacoInstance, {
      dbType: 'mysql',
      templatesEnabled: true,
    })

    const result = await provider.provideCompletionItems(
      modelFor('sel'),
      position,
      completionContext,
      cancellationToken,
    )
    const suggestion = result?.suggestions.find((item) => item.label === 'sel')

    expect(suggestion).toMatchObject({
      kind: 28,
      insertTextRules: 4,
      detail: 'SQL 模板 · 基础查询',
      sortText: '0_sel',
      preselect: true,
    })
    expect(suggestion?.insertText).toContain('${1:*}')
    expect(result?.suggestions).toHaveLength(1)
    expect(metadataMocks.objects).not.toHaveBeenCalled()
  })

  it('removes only snippets when templates are disabled', async () => {
    const provider = createSqlCompletionProvider('conn', 'db', monacoInstance, {
      dbType: 'mysql',
      templatesEnabled: false,
    })

    const result = await provider.provideCompletionItems(
      modelFor('sel'),
      position,
      completionContext,
      cancellationToken,
    )

    expect(result?.suggestions.some((item) => item.kind === 28)).toBe(false)
    expect(result?.suggestions.some((item) => item.label === 'SELECT' && item.kind === 17)).toBe(true)
  })

  it('does not suggest templates inside a qualified identifier', async () => {
    const provider = createSqlCompletionProvider('conn', 'db', monacoInstance, {
      dbType: 'mysql',
      templatesEnabled: true,
    })

    const result = await provider.provideCompletionItems(
      modelFor('sel', 'users.sel', 7),
      { lineNumber: 1, column: 10 } as Position,
      completionContext,
      cancellationToken,
    )

    expect(result?.suggestions.some((item) => item.kind === 28)).toBe(false)
  })
})
