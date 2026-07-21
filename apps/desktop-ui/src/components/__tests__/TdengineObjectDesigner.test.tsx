import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import {
  TdengineObjectDesigner,
} from '../TdengineObjectDesigner'
import {
  buildTimeSeriesCreateDefinition,
  validateTimeSeriesDefinition,
  validateTimeSeriesTagValues,
} from '@/utils/timeSeriesDesigner'

const apiMocks = vi.hoisted(() => ({
  preview: vi.fn(),
  create: vi.fn(),
  tags: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  metadataApi: {
    previewTimeSeriesObject: apiMocks.preview,
    createTimeSeriesObject: apiMocks.create,
    timeSeriesTagDefinitions: apiMocks.tags,
  },
}))

vi.mock('@/utils/notification', () => ({
  handleApiError: vi.fn(),
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}))

const columns = [
  { id: 'ts', name: 'ts', type: 'TIMESTAMP' as const },
  { id: 'value', name: 'value', type: 'DOUBLE' as const },
]
const tags = [{ id: 'location', name: 'location', type: 'VARCHAR' as const, length: 64 }]

describe('TdengineObjectDesigner request mapping', () => {
  it('maps supertable and basic table without leaking irrelevant fields', () => {
    const stable = buildTimeSeriesCreateDefinition({
      kind: 'SUPER_TABLE', name: 'meters', columns, tags, tagValues: [], comment: 'meter data',
    })
    expect(stable).toMatchObject({
      kind: 'SUPER_TABLE',
      name: 'meters',
      columns: [{ name: 'ts', type: 'TIMESTAMP' }, { name: 'value', type: 'DOUBLE' }],
      tags: [{ name: 'location', type: 'VARCHAR', length: 64 }],
      stableName: null,
      tagValues: [],
      comment: 'meter data',
    })

    const basic = buildTimeSeriesCreateDefinition({
      kind: 'BASIC_TABLE', name: 'events', columns, tags, stableName: 'ignored', tagValues: [], comment: '',
    })
    expect(basic.tags).toEqual([])
    expect(basic.stableName).toBeNull()
    expect(basic.comment).toBeNull()
  })

  it('maps child tags while preserving null separately from an empty string', () => {
    const child = buildTimeSeriesCreateDefinition({
      kind: 'CHILD_TABLE',
      name: 'd1001',
      columns,
      tags,
      stableName: 'meters',
      tagValues: [
        { name: 'location', value: '', isNull: false },
        { name: 'note', value: 'stale', isNull: true },
      ],
      comment: 'ignored',
    })

    expect(child).toEqual({
      kind: 'CHILD_TABLE',
      name: 'd1001',
      columns: [],
      tags: [],
      stableName: 'meters',
      tagValues: [
        { name: 'location', value: '', isNull: false },
        { name: 'note', value: 'stale', isNull: true },
      ],
      comment: null,
    })
  })
})

describe('TdengineObjectDesigner validation', () => {
  it('rejects invalid first columns duplicates and missing string lengths', () => {
    const errors = validateTimeSeriesDefinition({
      kind: 'SUPER_TABLE',
      name: 'meters',
      columns: [{ name: 'value', type: 'DOUBLE' }, { name: 'location', type: 'VARCHAR' }],
      tags: [{ name: 'location', type: 'VARCHAR', length: 64 }],
      tagValues: [],
    })

    expect(errors.join(' ')).toContain('第一个字段必须是 TIMESTAMP')
    expect(errors.join(' ')).toContain('不能重复')
    expect(errors.join(' ')).toContain('必须设置类型长度')
  })

  it('validates typed tag values but allows explicit null and empty strings', () => {
    const definitions = [
      { name: 'location', type: 'VARCHAR(2)' },
      { name: 'group_id', type: 'BIGINT UNSIGNED' },
      { name: 'enabled', type: 'BOOL' },
      { name: 'note', type: 'VARCHAR(2)' },
    ]
    const errors = validateTimeSeriesTagValues(definitions, [
      { name: 'location', value: '', isNull: false },
      { name: 'group_id', value: '18446744073709551616', isNull: false },
      { name: 'enabled', value: '0', isNull: false },
      { name: 'note', value: 'ignored', isNull: true },
    ])

    expect(errors).toEqual([
      'group_id 超出 BIGINT UNSIGNED 范围',
      'enabled 必须是 true 或 false',
    ])
  })
})

describe('TdengineObjectDesigner preview gate', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.preview.mockResolvedValue({ ddl: 'CREATE STABLE `power`.`meters` (...) TAGS (...)' })
    apiMocks.create.mockResolvedValue({
      success: true,
      ddl: 'CREATE STABLE `power`.`meters` (...) TAGS (...)',
      kind: 'SUPER_TABLE',
      name: 'meters',
    })
  })

  it('preserves identifier casing and disables natural-language input transforms', async () => {
    render(
      <TdengineObjectDesigner
        connectionId="connection-1"
        database="power"
        stableNames={[]}
        onSuccess={vi.fn()}
        onCancel={vi.fn()}
      />,
    )

    const objectName = screen.getByLabelText('对象名称')
    const fieldName = screen.getByLabelText('字段名称 1')
    const tagName = screen.getByLabelText('Tag名称 1')
    for (const input of [objectName, fieldName, tagName]) {
      expect(input).toHaveAttribute('autocapitalize', 'none')
      expect(input).toHaveAttribute('autocorrect', 'off')
      expect(input).toHaveAttribute('spellcheck', 'false')
    }

    fireEvent.change(objectName, { target: { value: 'test001' } })
    fireEvent.change(fieldName, { target: { value: 'sampletime' } })
    fireEvent.change(tagName, { target: { value: 'devicegroup' } })
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))

    await waitFor(() => expect(apiMocks.preview).toHaveBeenCalledTimes(1))
    expect(apiMocks.preview.mock.calls[0][2].name).toBe('test001')
    expect(apiMocks.preview.mock.calls[0][2].columns[0].name).toBe('sampletime')
    expect(apiMocks.preview.mock.calls[0][2].tags[0].name).toBe('devicegroup')
  })

  it('does not expose create before preview and rebuilds preview after returning to modify', async () => {
    render(
      <TdengineObjectDesigner
        connectionId="connection-1"
        database="power"
        stableNames={['existing_stable']}
        onSuccess={vi.fn()}
        onCancel={vi.fn()}
      />,
    )

    expect(screen.queryByRole('button', { name: '确认创建' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: 'meters' } })
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))

    expect(await screen.findByRole('button', { name: '确认创建' })).toBeEnabled()
    expect(apiMocks.preview).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '返回修改' }))
    fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: 'meters_v2' } })
    expect(screen.queryByRole('button', { name: '确认创建' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))
    await waitFor(() => expect(apiMocks.preview).toHaveBeenCalledTimes(2))
    expect(apiMocks.preview.mock.calls[1][2].name).toBe('meters_v2')
  }, 10_000)

  it('submits the current definition only after preview', async () => {
    const onSuccess = vi.fn()
    let resolveCreate!: (value: { success: boolean; ddl: string; kind: string; name: string }) => void
    apiMocks.create.mockImplementationOnce(() => new Promise((resolve) => { resolveCreate = resolve }))
    render(
      <TdengineObjectDesigner
        connectionId="connection-1"
        database="power"
        stableNames={[]}
        onSuccess={onSuccess}
        onCancel={vi.fn()}
      />,
    )

    fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: 'meters' } })
    fireEvent.click(screen.getByRole('button', { name: '生成 DDL 预览' }))
    const createButton = await screen.findByRole('button', { name: '确认创建' })
    fireEvent.click(createButton)

    await waitFor(() => expect(apiMocks.create).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(createButton).toBeDisabled())
    fireEvent.click(createButton)
    expect(apiMocks.create).toHaveBeenCalledTimes(1)
    expect(apiMocks.create.mock.calls[0][2].name).toBe('meters')
    resolveCreate({ success: true, ddl: 'CREATE STABLE ...', kind: 'SUPER_TABLE', name: 'meters' })
    await waitFor(() => expect(onSuccess).toHaveBeenCalled())
    expect(onSuccess).toHaveBeenCalledWith(expect.objectContaining({ name: 'meters' }))
  })
})
