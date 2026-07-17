import { describe, expect, it } from 'vitest'
import globalStyles from './index.css?raw'

function ruleBody(selector: string): string {
  const selectorStart = globalStyles.indexOf(selector)
  expect(selectorStart).toBeGreaterThanOrEqual(0)

  const bodyStart = globalStyles.indexOf('{', selectorStart)
  const bodyEnd = globalStyles.indexOf('}', bodyStart)
  return globalStyles.slice(bodyStart + 1, bodyEnd)
}

describe('global overlay layering', () => {
  it('leaves Ant Design portal z-index management to the component context', () => {
    expect(ruleBody('.ant-select-dropdown,')).not.toContain('z-index')
    expect(ruleBody('.ant-picker-dropdown')).not.toContain('z-index')
    expect(globalStyles).not.toContain('--ant-z-index-popup-base')
    expect(globalStyles).not.toMatch(/\.ant-(popover|tooltip|drawer|message|notification)\s*{[^}]*z-index/s)
  })
})
