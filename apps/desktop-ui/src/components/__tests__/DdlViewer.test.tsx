import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DdlViewer } from '../DdlViewer'

const notificationMocks = vi.hoisted(() => ({
  success: vi.fn(),
  handleApiError: vi.fn(),
}))

vi.mock('@/utils/notification', () => ({
  toast: { success: notificationMocks.success },
  handleApiError: notificationMocks.handleApiError,
}))

const writeText = vi.fn()

describe('DdlViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    writeText.mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    window.getSelection()?.removeAllRanges()
  })

  it('copies the complete DDL and reports success', async () => {
    const ddl = 'CREATE TABLE "orders" (\n  "id" BIGINT\n);'
    render(<DdlViewer ddl={ddl} />)

    fireEvent.click(screen.getByRole('button', { name: '复制 DDL' }))

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(ddl))
    expect(notificationMocks.success).toHaveBeenCalledWith('DDL 已复制')
  })

  it('disables copying while DDL is empty or loading', () => {
    const { rerender } = render(<DdlViewer ddl="   " />)
    expect(screen.getByRole('button', { name: '复制 DDL' })).toBeDisabled()

    rerender(<DdlViewer ddl="CREATE TABLE t (id INT);" loading />)
    expect(screen.getByRole('button', { name: '复制 DDL' })).toBeDisabled()
    expect(writeText).not.toHaveBeenCalled()
  })

  it('reports clipboard failures', async () => {
    const error = new Error('clipboard unavailable')
    writeText.mockRejectedValueOnce(error)
    render(<DdlViewer ddl="CREATE TABLE t (id INT);" />)

    fireEvent.click(screen.getByRole('button', { name: '复制 DDL' }))

    await waitFor(() => {
      expect(notificationMocks.handleApiError).toHaveBeenCalledWith(error, '复制 DDL 失败')
    })
  })

  it.each([
    { modifier: 'Ctrl', ctrlKey: true, metaKey: false },
    { modifier: 'Cmd', ctrlKey: false, metaKey: true },
  ])('$modifier+A selects only the focused DDL content', ({ ctrlKey, metaKey }) => {
    const ddl = 'CREATE TABLE t (id INT);'
    render(
      <div>
        <span>其他页面内容</span>
        <DdlViewer ddl={ddl} />
      </div>,
    )
    const code = screen.getByLabelText('DDL 内容')

    code.focus()
    fireEvent.keyDown(code, { key: 'a', ctrlKey, metaKey })

    expect(window.getSelection()?.toString()).toBe(ddl)
    expect(window.getSelection()?.toString()).not.toContain('其他页面内容')
  })
})
