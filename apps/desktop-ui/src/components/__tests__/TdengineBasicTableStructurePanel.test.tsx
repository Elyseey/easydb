import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { TdengineBasicTableStructurePanel } from '../TdengineBasicTableStructurePanel'
import type { TimeSeriesBasicTableSnapshot } from '@/types'

const apiMocks = vi.hoisted(() => ({ inspect: vi.fn(), preview: vi.fn(), apply: vi.fn() }))
vi.mock('@/services/api', () => ({ metadataApi: {
  timeSeriesBasicTableLifecycle: apiMocks.inspect,
  previewTimeSeriesBasicTableLifecycle: apiMocks.preview,
  applyTimeSeriesBasicTableLifecycle: apiMocks.apply,
} }))
vi.mock('@/utils/notification', () => ({ handleApiError: vi.fn(), toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))

const snapshot: TimeSeriesBasicTableSnapshot = {
  database: 'power', table: 'events', fingerprint: 'basic-v1', columns: [
    { name: 'ts', type: 'TIMESTAMP', primaryTimestamp: true },
    { name: 'value', type: 'DOUBLE', primaryTimestamp: false },
    { name: 'note', type: 'VARCHAR', length: 16, primaryTimestamp: false },
  ],
}

describe('TdengineBasicTableStructurePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.inspect.mockResolvedValue(snapshot)
    apiMocks.preview.mockImplementation(async (_connection: string, _database: string, _table: string, command: { operation: string; name: string }) => ({
      command, snapshot, ddl: 'ALTER TABLE `power`.`events` DROP COLUMN `value`', previewToken: 'drop-value-token', destructive: true, warnings: ['不可逆'],
    }))
    apiMocks.apply.mockResolvedValue({ success: true, command: { operation: 'DROP_COLUMN', name: 'value' }, ddl: 'server ddl', previousFingerprint: 'basic-v1' })
  })

  it('disables browser capitalization and correction for new identifiers', async () => {
    const first = render(<TdengineBasicTableStructurePanel connectionId="c1" database="power" table="events" onApplied={vi.fn()} />)
    expect(await screen.findByText('power.events')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /新增字段/ }))
    const addInput = screen.getByRole('textbox')
    expect(addInput).toHaveAttribute('autocapitalize', 'none')
    expect(addInput).toHaveAttribute('autocorrect', 'off')
    expect(addInput).toHaveAttribute('spellcheck', 'false')
    first.unmount()

    render(<TdengineBasicTableStructurePanel connectionId="c1" database="power" table="events" onApplied={vi.fn()} />)
    expect(await screen.findByText('power.events')).toBeInTheDocument()
    const valueRow = screen.getByText('value').closest('tr')!
    fireEvent.click(within(valueRow).getByRole('button', { name: '重命名' }))
    const renameInput = screen.getByRole('textbox')
    expect(renameInput).toHaveAttribute('autocapitalize', 'none')
    expect(renameInput).toHaveAttribute('autocorrect', 'off')
    expect(renameInput).toHaveAttribute('spellcheck', 'false')
  })

  it('requires exact DROP confirmation and apply submits proof without ddl', async () => {
    const onApplied = vi.fn()
    render(<TdengineBasicTableStructurePanel connectionId="c1" database="power" table="events" onApplied={onApplied} />)
    expect(await screen.findByText('power.events')).toBeInTheDocument()
    const timestampRow = screen.getByText('ts').closest('tr')!
    expect(within(timestampRow).getByText('主时间戳（不可修改）')).toBeInTheDocument()
    const valueRow = screen.getByText('value').closest('tr')!
    fireEvent.click(within(valueRow).getByRole('button', { name: /删除/ }))
    fireEvent.click(screen.getByRole('button', { name: /生成 DDL 预览/ }))
    expect(await screen.findByText('ALTER TABLE `power`.`events` DROP COLUMN `value`')).toBeInTheDocument()

    const applyButton = screen.getByRole('button', { name: /确认执行/ })
    expect(applyButton).toBeDisabled()
    const confirmation = screen.getByRole('textbox')
    fireEvent.change(confirmation, { target: { value: 'VALUE' } })
    expect(applyButton).toBeDisabled()
    fireEvent.change(confirmation, { target: { value: 'value' } })
    expect(applyButton).toBeEnabled()
    fireEvent.click(applyButton)

    await waitFor(() => expect(apiMocks.apply).toHaveBeenCalledWith('c1', 'power', 'events', {
      command: { operation: 'DROP_COLUMN', name: 'value' }, expectedFingerprint: 'basic-v1', previewToken: 'drop-value-token', confirmationName: 'value',
    }))
    expect(apiMocks.apply.mock.calls[0][3]).not.toHaveProperty('ddl')
    await waitFor(() => expect(onApplied).toHaveBeenCalled())
  })
})
