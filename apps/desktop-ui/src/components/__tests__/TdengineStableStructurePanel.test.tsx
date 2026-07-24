import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { TdengineStableStructurePanel } from '../TdengineStableStructurePanel'
import type {
  TimeSeriesLifecycleCommand,
  TimeSeriesLifecyclePreview,
  TimeSeriesLifecycleSnapshot,
} from '@/types'

const apiMocks = vi.hoisted(() => ({
  inspect: vi.fn(),
  preview: vi.fn(),
  apply: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  metadataApi: {
    timeSeriesLifecycle: apiMocks.inspect,
    previewTimeSeriesLifecycle: apiMocks.preview,
    applyTimeSeriesLifecycle: apiMocks.apply,
  },
}))

vi.mock('@/utils/notification', () => ({
  handleApiError: vi.fn(),
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))

const snapshot: TimeSeriesLifecycleSnapshot = {
  database: 'power',
  stable: 'Meters',
  columns: [
    { name: 'ts', type: 'TIMESTAMP', primaryTimestamp: true },
    { name: 'voltage', type: 'DOUBLE', primaryTimestamp: false },
    { name: 'payload', type: 'BINARY', length: 16, primaryTimestamp: false },
  ],
  tags: [
    { name: 'Location', type: 'NCHAR', length: 8, primaryTimestamp: false },
    { name: 'group_id', type: 'INT', primaryTimestamp: false },
  ],
  fingerprint: 'stable-v1',
  affectedChildTables: 12,
}

function lifecyclePreview(command: TimeSeriesLifecycleCommand): TimeSeriesLifecyclePreview {
  const destructive = command.operation === 'DROP_COLUMN' || command.operation === 'DROP_TAG'
  return {
    command,
    snapshot,
    ddl: destructive
      ? `ALTER STABLE \`power\`.\`Meters\` DROP ${command.operation === 'DROP_TAG' ? 'TAG' : 'COLUMN'} \`${command.name}\``
      : `ALTER STABLE \`power\`.\`Meters\` ADD COLUMN \`${command.name}\` DOUBLE`,
    previewToken: `token-${command.operation}-${command.name}`,
    destructive,
    warnings: destructive ? ['该操作不可逆'] : [],
  }
}

describe('TdengineStableStructurePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.inspect.mockResolvedValue(snapshot)
    apiMocks.preview.mockImplementation(async (_connectionId, _database, _stable, command) => (
      lifecyclePreview(command)
    ))
    apiMocks.apply.mockResolvedValue({
      success: true,
      command: { operation: 'DROP_TAG', name: 'Location' },
      ddl: 'server ddl',
      previousFingerprint: 'stable-v1',
    })
  })

  it('renders the lifecycle snapshot and applies a destructive command only after exact confirmation', async () => {
    const onApplied = vi.fn().mockResolvedValue(undefined)
    render(
      <TdengineStableStructurePanel
        connectionId="connection-1"
        database="power"
        stable="Meters"
        onApplied={onApplied}
      />,
    )

    expect(await screen.findByText('影响子表：12')).toBeInTheDocument()
    const timestampRow = screen.getByText('ts').closest('tr')
    expect(timestampRow).not.toBeNull()
    expect(within(timestampRow!).getByText('主时间戳')).toBeInTheDocument()
    expect(within(timestampRow!).queryByRole('button')).not.toBeInTheDocument()

    const tagRow = screen.getByText('Location').closest('tr')
    expect(tagRow).not.toBeNull()
    fireEvent.click(within(tagRow!).getByRole('button', { name: /删除$/ }))
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))

    await waitFor(() => expect(apiMocks.preview).toHaveBeenCalledWith(
      'connection-1',
      'power',
      'Meters',
      { operation: 'DROP_TAG', name: 'Location' },
    ))
    expect(await screen.findByText('ALTER STABLE `power`.`Meters` DROP TAG `Location`')).toBeInTheDocument()

    const confirmation = screen.getByRole('textbox', { name: '确认删除名称' })
    const applyButton = await screen.findByRole('button', { name: /确认执行/ })
    expect(applyButton).toBeDisabled()
    fireEvent.change(confirmation, { target: { value: 'location' } })
    expect(applyButton).toBeDisabled()
    fireEvent.change(confirmation, { target: { value: 'Location' } })
    expect(applyButton).toBeEnabled()
    fireEvent.click(applyButton)

    await waitFor(() => expect(apiMocks.apply).toHaveBeenCalledWith(
      'connection-1',
      'power',
      'Meters',
      {
        command: { operation: 'DROP_TAG', name: 'Location' },
        expectedFingerprint: 'stable-v1',
        previewToken: 'token-DROP_TAG-Location',
        confirmationName: 'Location',
      },
    ))
    expect(apiMocks.apply.mock.calls[0][3]).not.toHaveProperty('ddl')
    await waitFor(() => expect(apiMocks.inspect).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(
      expect.objectContaining({ success: true }),
      snapshot,
    ))
  })

  it('invalidates an old preview when the user returns to edit the structured command', async () => {
    render(
      <TdengineStableStructurePanel
        connectionId="connection-1"
        database="power"
        stable="Meters"
        onApplied={vi.fn()}
      />,
    )

    await screen.findByText('影响子表：12')
    fireEvent.click(screen.getByRole('button', { name: /新增字段$/ }))
    fireEvent.change(screen.getByRole('textbox', { name: '字段名称' }), { target: { value: 'current' } })
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))

    expect(await screen.findByText('ALTER STABLE `power`.`Meters` ADD COLUMN `current` DOUBLE')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '返回修改' }))
    fireEvent.change(screen.getByRole('textbox', { name: '字段名称' }), { target: { value: 'next' } })
    expect(screen.queryByText('ALTER STABLE `power`.`Meters` ADD COLUMN `current` DOUBLE')).not.toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: /生成 DDL 预览/ }))

    await waitFor(() => expect(apiMocks.preview).toHaveBeenCalledTimes(2))
    expect(apiMocks.preview.mock.calls[1][3]).toEqual({
      operation: 'ADD_COLUMN',
      name: 'next',
      type: 'DOUBLE',
    })
    expect(apiMocks.apply).not.toHaveBeenCalled()
  })

  it('never applies a preview when the destructive dialog is cancelled', async () => {
    render(
      <TdengineStableStructurePanel
        connectionId="connection-1"
        database="power"
        stable="Meters"
        onApplied={vi.fn()}
      />,
    )

    await screen.findByText('影响子表：12')
    const tagRow = screen.getByText('Location').closest('tr')
    fireEvent.click(within(tagRow!).getByRole('button', { name: /删除$/ }))
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))
    await screen.findByText('ALTER STABLE `power`.`Meters` DROP TAG `Location`')
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))

    expect(apiMocks.apply).not.toHaveBeenCalled()
  })

  it('preserves the structured editor and invalidates the preview when apply fails', async () => {
    const onApplied = vi.fn()
    apiMocks.apply.mockRejectedValueOnce(new Error('OBJECT_CHANGED'))
    render(
      <TdengineStableStructurePanel
        connectionId="connection-1"
        database="power"
        stable="Meters"
        onApplied={onApplied}
      />,
    )

    await screen.findByText('影响子表：12')
    fireEvent.click(screen.getByRole('button', { name: /新增字段$/ }))
    fireEvent.change(screen.getByRole('textbox', { name: '字段名称' }), { target: { value: 'current' } })
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))
    expect(await screen.findByText('ALTER STABLE `power`.`Meters` ADD COLUMN `current` DOUBLE')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /确认执行/ }))

    await waitFor(() => expect(apiMocks.apply).toHaveBeenCalledTimes(1))
    expect(await screen.findByRole('textbox', { name: '字段名称' })).toHaveValue('current')
    expect(screen.queryByText('ALTER STABLE `power`.`Meters` ADD COLUMN `current` DOUBLE')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /生成 DDL 预览/ })).toBeEnabled()
    expect(onApplied).not.toHaveBeenCalled()
  })
})
