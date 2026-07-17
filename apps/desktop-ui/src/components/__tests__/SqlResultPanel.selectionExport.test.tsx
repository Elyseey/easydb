import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SqlResultPanel } from '../SqlResultPanel'
import { confirmDataExport } from '../confirmDataExport'
import type { SqlResult } from '@/types'

vi.mock('../confirmDataExport', () => ({
  confirmDataExport: vi.fn(),
}))

const result: SqlResult = {
  type: 'query',
  columns: ['id', 'name'],
  rows: [
    { id: 1, name: 'Alice' },
    { id: 2, name: 'Bob' },
    { id: 3, name: 'Carol' },
  ],
  duration: 12,
  sql: 'SELECT id, name FROM users',
  executedAt: '2026-07-10T10:00:00.000Z',
}

describe('SqlResultPanel selected row export', () => {
  beforeEach(() => {
    vi.mocked(confirmDataExport).mockClear()
  })

  it('exports only the checked rows', async () => {
    const user = userEvent.setup()
    render(<SqlResultPanel result={result} dbType="mysql" displayLabel="users" tableHeight={420} />)

    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(4)
    await user.click(checkboxes[1])
    await user.click(checkboxes[3])

    expect(screen.getByText('已选 2 行')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '导出所选 2 行' }))
    fireEvent.click(await screen.findByText('导出所选为 CSV'))

    expect(confirmDataExport).toHaveBeenCalledWith(expect.objectContaining({
      format: 'csv',
      scope: 'selected',
      rows: [
        { id: 1, name: 'Alice' },
        { id: 3, name: 'Carol' },
      ],
    }))
  })

  it('right-clicks an unselected row as a single-row selection', async () => {
    render(<SqlResultPanel result={result} dbType="mysql" displayLabel="users" tableHeight={420} />)

    const targetRow = document.querySelector('.ant-table-row[data-row-key="1"]')
    expect(targetRow).not.toBeNull()
    fireEvent.contextMenu(targetRow!)

    await waitFor(() => {
      expect(screen.getByText('已选 1 行')).toBeInTheDocument()
      expect(screen.getByText('导出此行')).toBeInTheDocument()
    })
  })

  it('remeasures the virtual table after result rows change', async () => {
    const handleResize = vi.fn()
    window.addEventListener('resize', handleResize)

    const { rerender } = render(
      <SqlResultPanel result={result} dbType="mysql" displayLabel="users" tableHeight={420} />
    )
    await waitFor(() => expect(handleResize).toHaveBeenCalled())
    handleResize.mockClear()

    rerender(
      <SqlResultPanel
        result={{
          ...result,
          rows: [...result.rows!, { id: 4, name: 'Dave' }],
        }}
        dbType="mysql"
        displayLabel="users"
        tableHeight={420}
      />
    )

    await waitFor(() => expect(handleResize).toHaveBeenCalled())
    window.removeEventListener('resize', handleResize)
  })
})
