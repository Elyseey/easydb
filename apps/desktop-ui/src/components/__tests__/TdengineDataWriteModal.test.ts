import React from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TdengineDataWriteModal } from '../TdengineDataWriteModal'
import {
  explicitTimeSeriesNow,
  parseTimeSeriesPaste,
  timeSeriesWriteTargetKind,
  validateExplicitTimeSeriesRows,
} from '../tdengineDataWrite'

const apiMocks = vi.hoisted(() => ({ children: vi.fn(), tags: vi.fn() }))
vi.mock('@/services/api', () => ({ metadataApi: {
  timeSeriesChildren: apiMocks.children,
  timeSeriesTagDefinitions: apiMocks.tags,
} }))
vi.mock('@/utils/notification', () => ({ handleApiError: vi.fn(), toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

describe('TdengineDataWriteModal helpers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.children.mockResolvedValue({ items: [], offset: 0, limit: 100, hasMore: false })
    apiMocks.tags.mockResolvedValue([])
  })

  it('parses Excel TSV rectangles and preserves empty cells', () => {
    expect(parseTimeSeriesPaste('2026-07-22 10:00:00\t1\t\r\n2026-07-22 10:00:01\t2\t上海\n')).toEqual([
      ['2026-07-22 10:00:00', '1', ''],
      ['2026-07-22 10:00:01', '2', '上海'],
    ])
  })

  it('maps all object and stable modes to explicit write targets', () => {
    expect(timeSeriesWriteTargetKind('BASIC_TABLE', 'new')).toBe('BASIC_TABLE')
    expect(timeSeriesWriteTargetKind('CHILD_TABLE', 'new')).toBe('EXISTING_CHILD_TABLE')
    expect(timeSeriesWriteTargetKind('SUPER_TABLE', 'existing')).toBe('EXISTING_CHILD_TABLE')
    expect(timeSeriesWriteTargetKind('SUPER_TABLE', 'new')).toBe('NEW_CHILD_TABLE')
  })

  it('requires an explicit timestamp and current-time helper returns a concrete value', () => {
    expect(() => validateExplicitTimeSeriesRows([{ cells: [{ name: 'ts', value: '', isNull: false }] }], 'ts')).toThrow('时间戳必须显式填写')
    expect(() => validateExplicitTimeSeriesRows([{ cells: [{ name: 'ts', value: null, isNull: true }] }], 'ts')).toThrow('时间戳必须显式填写')
    const now = explicitTimeSeriesNow()
    expect(now).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$/)
    expect(() => validateExplicitTimeSeriesRows([{ cells: [{ name: 'ts', value: now, isNull: false }] }], 'ts')).not.toThrow()
  })

  it('disables browser capitalization and correction for a new child name', async () => {
    render(React.createElement(TdengineDataWriteModal, {
      open: true,
      connectionId: 'c1',
      database: 'power',
      table: 'meters',
      tableKind: 'SUPER_TABLE',
      columns: [{ name: 'ts', type: 'TIMESTAMP', nullable: false, isPrimaryKey: true, isAutoIncrement: false }],
      onClose: vi.fn(),
      onApplied: vi.fn(),
    }))
    await waitFor(() => expect(apiMocks.tags).toHaveBeenCalled())
    fireEvent.click(screen.getByText('创建子表并写入'))
    const input = screen.getByPlaceholderText('新子表名称')
    expect(input).toHaveAttribute('autocapitalize', 'none')
    expect(input).toHaveAttribute('autocorrect', 'off')
    expect(input).toHaveAttribute('spellcheck', 'false')
  })
})
