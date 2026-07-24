import { describe, expect, it } from 'vitest'
import { canStartTimeSeriesCsvImport, shouldRefreshAfterCsvImport } from '../tdengineCsvImport'
import type { TaskInfo, TimeSeriesCsvPreview } from '@/types'

describe('TDengine CSV import policy', () => {
  it('allows sampled invalid rows while structural blockers prevent start', () => {
    const preview = { blockingErrors: [], rows: [{ recordNumber: 1, cells: [], error: 'invalid INT' }] } as unknown as TimeSeriesCsvPreview
    expect(canStartTimeSeriesCsvImport(preview)).toBe(true)
    expect(canStartTimeSeriesCsvImport({ ...preview, blockingErrors: ['缺少主时间戳映射'] })).toBe(false)
  })

  it('refreshes partial terminal results and created children', () => {
    const task = (patch: Partial<TaskInfo>): TaskInfo => ({ id: '1', name: 'csv', type: 'timeseries_csv_import', status: 'failed', progress: 50, ...patch })
    expect(shouldRefreshAfterCsvImport(task({ successCount: 2 }))).toBe(true)
    expect(shouldRefreshAfterCsvImport(task({ payload: { createdChildTable: 'true' } }))).toBe(true)
    expect(shouldRefreshAfterCsvImport(task({ failureCount: 2 }))).toBe(false)
  })
})
