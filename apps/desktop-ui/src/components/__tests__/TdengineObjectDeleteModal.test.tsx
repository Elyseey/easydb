import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { TdengineObjectDeleteModal } from '../TdengineObjectDeleteModal'

const apiMocks = vi.hoisted(() => ({ preview: vi.fn(), apply: vi.fn() }))

vi.mock('@/services/api', () => ({
  metadataApi: {
    previewTimeSeriesObjectDelete: apiMocks.preview,
    applyTimeSeriesObjectDelete: apiMocks.apply,
  },
}))

vi.mock('@/utils/notification', () => ({ handleApiError: vi.fn() }))

const preview = {
  snapshot: {
    database: 'power',
    name: 'meters',
    kind: 'SUPER_TABLE' as const,
    affectedChildTables: 7,
    fingerprint: 'delete-v1',
  },
  ddl: 'DROP STABLE `power`.`meters`',
  previewToken: 'preview-token',
  warnings: ['删除超级表会永久删除全部子表'],
}

describe('TdengineObjectDeleteModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.preview.mockResolvedValue(preview)
    apiMocks.apply.mockResolvedValue({ success: true, snapshot: preview.snapshot, ddl: preview.ddl })
  })

  it('keeps apply disabled until the exact case-sensitive name is entered and cancel never applies', async () => {
    const onCancel = vi.fn()
    render(
      <TdengineObjectDeleteModal
        target={{ connectionId: 'c1', database: 'power', objectName: 'meters' }}
        onCancel={onCancel}
        onDeleted={vi.fn()}
      />,
    )
    const input = await screen.findByRole('textbox', { name: '确认对象名' })
    const applyButton = screen.getByRole('button', { name: '永久删除' })
    expect(applyButton).toBeDisabled()
    fireEvent.change(input, { target: { value: 'Meters' } })
    expect(applyButton).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(apiMocks.apply).not.toHaveBeenCalled()
  })

  it('submits only the server preview proof and exact confirmation name', async () => {
    const onDeleted = vi.fn()
    render(
      <TdengineObjectDeleteModal
        target={{ connectionId: 'c1', database: 'power', objectName: 'meters' }}
        onCancel={vi.fn()}
        onDeleted={onDeleted}
      />,
    )
    fireEvent.change(await screen.findByRole('textbox', { name: '确认对象名' }), {
      target: { value: 'meters' },
    })
    fireEvent.click(screen.getByRole('button', { name: '永久删除' }))

    await waitFor(() => expect(apiMocks.apply).toHaveBeenCalledWith('c1', 'power', 'meters', {
      expectedFingerprint: 'delete-v1',
      previewToken: 'preview-token',
      confirmationName: 'meters',
    }))
    await waitFor(() => expect(onDeleted).toHaveBeenCalledTimes(1))
  })
})
