import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { TdengineChildPropertiesPanel } from '../TdengineChildPropertiesPanel'
import type { TimeSeriesChildPropertySnapshot } from '@/types'

const apiMocks = vi.hoisted(() => ({
  preview: vi.fn(),
  apply: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  metadataApi: {
    previewTimeSeriesChildProperty: apiMocks.preview,
    applyTimeSeriesChildProperty: apiMocks.apply,
  },
}))

vi.mock('@/utils/notification', () => ({
  handleApiError: vi.fn(),
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))

const snapshot: TimeSeriesChildPropertySnapshot = {
  database: 'power',
  table: 'd1',
  stableName: 'meters',
  tagValues: [
    { name: 'location', type: 'VARCHAR(16)', value: null },
    { name: 'group_id', type: 'INT', value: '7' },
  ],
  ttl: 30,
  comment: '',
  fingerprint: 'child-v1',
}

describe('TdengineChildPropertiesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.preview.mockImplementation(async (_connectionId, _database, _table, command) => ({
      command,
      snapshot,
      ddl: "ALTER TABLE `power`.`d1` SET TAG `location` = ''",
      previewToken: 'preview-token',
    }))
    apiMocks.apply.mockResolvedValue({ success: true })
  })

  it('keeps NULL distinct from an empty string and applies only the previewed command', async () => {
    const onApplied = vi.fn().mockResolvedValue(undefined)
    const onOpenStableStructure = vi.fn()
    render(
      <TdengineChildPropertiesPanel
        connectionId="connection-1"
        database="power"
        table="d1"
        snapshot={snapshot}
        loading={false}
        onApplied={onApplied}
        onOpenStableStructure={onOpenStableStructure}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '打开超级表结构管理' }))
    expect(onOpenStableStructure).toHaveBeenCalledWith('meters')
    expect(screen.getByText('NULL')).toBeInTheDocument()
    expect(screen.getByText('（空字符串）')).toBeInTheDocument()
    const locationRow = screen.getByText('location').closest('tr')
    expect(locationRow).not.toBeNull()
    fireEvent.click(within(locationRow!).getByRole('button', { name: '编辑' }))

    const nullCheckbox = screen.getByRole('checkbox', { name: '写入 NULL' })
    expect(nullCheckbox).toBeChecked()
    fireEvent.click(nullCheckbox)
    const valueInput = screen.getByPlaceholderText('空内容表示空字符串')
    expect(valueInput).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))

    await waitFor(() => expect(apiMocks.preview).toHaveBeenCalledTimes(1))
    expect(apiMocks.preview.mock.calls[0][3]).toEqual({
      operation: 'SET_TAG',
      tagName: 'location',
      value: '',
      isNull: false,
    })
    expect(await screen.findByText("ALTER TABLE `power`.`d1` SET TAG `location` = ''")).toBeInTheDocument()
    const applyButton = await screen.findByRole('button', { name: /确认执行/ })
    await waitFor(() => expect(applyButton).toBeEnabled())
    fireEvent.click(applyButton)

    await waitFor(() => expect(apiMocks.apply).toHaveBeenCalledTimes(1))
    expect(apiMocks.apply.mock.calls[0][3]).toEqual({
      command: { operation: 'SET_TAG', tagName: 'location', value: '', isNull: false },
      expectedFingerprint: 'child-v1',
      previewToken: 'preview-token',
    })
    await waitFor(() => expect(onApplied).toHaveBeenCalledTimes(1))
  })
})
