import { describe, expect, it } from 'vitest'
import { getErrorMessage } from '../notification'

describe('getErrorMessage', () => {
  it('preserves messages from Error instances and error-like API rejections', () => {
    expect(getErrorMessage(new Error('network failed'))).toBe('network failed')
    expect(getErrorMessage({ message: 'request rejected' })).toBe('request rejected')
  })

  it('uses the fallback when an unknown rejection has no usable message', () => {
    expect(getErrorMessage({ message: 404 }, '操作失败')).toBe('操作失败')
    expect(getErrorMessage(null, '操作失败')).toBe('操作失败')
  })
})
