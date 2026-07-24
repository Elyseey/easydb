import { describe, expect, it } from 'vitest'
import {
  defaultTagFilterOperator,
  tagFilterNeedsValue,
  tagFilterOperators,
} from '../tdengineTagFilters'

describe('TDengine Tag filter contracts', () => {
  it('limits operators by Tag type', () => {
    expect(tagFilterOperators('VARCHAR(64)')).toEqual(['EQ', 'NE', 'CONTAINS', 'IS_NULL', 'IS_NOT_NULL'])
    expect(tagFilterOperators('BOOL')).toEqual(['EQ', 'NE', 'IS_NULL', 'IS_NOT_NULL'])
    expect(tagFilterOperators('INT UNSIGNED')).toContain('GTE')
    expect(tagFilterOperators('TIMESTAMP')).toContain('LT')
  })

  it('only null operators omit the value field', () => {
    expect(tagFilterNeedsValue('EQ')).toBe(true)
    expect(tagFilterNeedsValue('CONTAINS')).toBe(true)
    expect(tagFilterNeedsValue('IS_NULL')).toBe(false)
    expect(tagFilterNeedsValue('IS_NOT_NULL')).toBe(false)
  })

  it('uses a stable type-aware default operator', () => {
    expect(defaultTagFilterOperator({ name: 'location', type: 'NCHAR(32)' })).toBe('EQ')
    expect(defaultTagFilterOperator(undefined)).toBe('EQ')
  })
})
