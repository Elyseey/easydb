import { describe, expect, it } from 'vitest'
import { timeSeriesRefreshTarget } from '../timeSeriesDesigner'

describe('timeSeriesRefreshTarget', () => {
  it('refreshes the object catalog for supertables and basic tables', () => {
    expect(timeSeriesRefreshTarget({ success: true, ddl: '', kind: 'SUPER_TABLE', name: 'meters' }))
      .toEqual({ kind: 'objects' })
    expect(timeSeriesRefreshTarget({ success: true, ddl: '', kind: 'BASIC_TABLE', name: 'events' }))
      .toEqual({ kind: 'objects' })
  })

  it('refreshes only the selected parent children for child tables', () => {
    expect(timeSeriesRefreshTarget({
      success: true,
      ddl: '',
      kind: 'CHILD_TABLE',
      name: 'd1001',
      stableName: 'meters',
    })).toEqual({ kind: 'children', stableName: 'meters' })
  })
})
