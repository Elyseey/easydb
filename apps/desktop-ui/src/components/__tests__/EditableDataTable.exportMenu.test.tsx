import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { EditableDataTable } from '../EditableDataTable'
import { confirmDataExport } from '../confirmDataExport'

vi.mock('../confirmDataExport', () => ({
  confirmDataExport: vi.fn(),
}))

beforeAll(() => {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

describe('EditableDataTable export menu', () => {
  it('hides the trigger tooltip while the export menu is open', async () => {
    const user = userEvent.setup()
    render(
      <EditableDataTable
        connectionId="connection-1"
        database="database-1"
        tableName="users"
        columns={[{
          name: 'id',
          type: 'INT',
          nullable: false,
          isPrimaryKey: true,
          isAutoIncrement: false,
        }]}
        dataSource={[{ id: 1 }]}
        onRefresh={() => {}}
      />,
    )

    const exportButton = document.querySelector('.anticon-download')?.closest('button')
    expect(exportButton).not.toBeNull()

    await user.hover(exportButton!)
    await waitFor(() => {
      expect(exportButton).toHaveClass('ant-tooltip-open')
    })

    await user.click(exportButton!)

    const csvItem = await screen.findByText('导出为 CSV')
    await waitFor(() => {
      expect(exportButton).not.toHaveClass('ant-tooltip-open')
      expect(csvItem.closest('.ant-dropdown')).not.toHaveClass('ant-dropdown-hidden')
    })
    expect(screen.getByText('导出为 JSON')).toBeInTheDocument()

    fireEvent.click(csvItem)
    expect(confirmDataExport).toHaveBeenCalledWith(expect.objectContaining({ format: 'csv' }))
  })
})
