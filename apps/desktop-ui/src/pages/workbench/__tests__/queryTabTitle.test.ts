import { describe, expect, it } from 'vitest'
import {
  MAX_QUERY_TAB_TITLE_LENGTH,
  normalizeQueryTabTitle,
  resolveWorkbenchTabKey,
} from '../queryTabTitle'

describe('query tab title', () => {
  it('trims surrounding whitespace and collapses repeated whitespace', () => {
    expect(normalizeQueryTabTitle('  生产库   价格检查  ')).toBe('生产库 价格检查')
  })

  it('rejects an empty title', () => {
    expect(normalizeQueryTabTitle('   ')).toBeNull()
  })

  it('limits excessively long titles', () => {
    const title = normalizeQueryTabTitle('a'.repeat(MAX_QUERY_TAB_TITLE_LENGTH + 10))
    expect(title).toHaveLength(MAX_QUERY_TAB_TITLE_LENGTH)
  })

  it('resolves the tab key from any element inside the tab', () => {
    const tab = document.createElement('div')
    tab.className = 'ant-tabs-tab'
    tab.dataset.nodeKey = 'sql:tab-2'
    const closeIcon = document.createElement('span')
    tab.appendChild(closeIcon)

    expect(resolveWorkbenchTabKey(closeIcon)).toBe('sql:tab-2')
    expect(resolveWorkbenchTabKey(document.body)).toBeNull()
  })
})
