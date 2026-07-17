import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EditableDataTable } from '../EditableDataTable'
import { confirmDataExport } from '../confirmDataExport'

vi.mock('../confirmDataExport', () => ({
  confirmDataExport: vi.fn(),
}))

describe('EditableDataTable export menu', () => {
  beforeEach(() => {
    vi.mocked(confirmDataExport).mockClear()
  })

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

    const csvItem = await screen.findByText('导出全部为 CSV')
    await waitFor(() => {
      expect(exportButton).not.toHaveClass('ant-tooltip-open')
      expect(csvItem.closest('.ant-dropdown')).not.toHaveClass('ant-dropdown-hidden')
    })
    expect(screen.getByText('导出全部为 JSON')).toBeInTheDocument()

    fireEvent.click(csvItem)
    expect(confirmDataExport).toHaveBeenCalledWith(expect.objectContaining({ format: 'csv' }))
  })

  it('exports only the checked rows', async () => {
    const user = userEvent.setup()
    render(
      <EditableDataTable
        connectionId="connection-1"
        dbType="mysql"
        database="database-1"
        tableName="users"
        columns={[{
          name: 'id',
          type: 'INT',
          nullable: false,
          isPrimaryKey: true,
          isAutoIncrement: false,
        }]}
        dataSource={[{ id: 1 }, { id: 2 }, { id: 3 }]}
        onRefresh={() => {}}
      />,
    )

    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(4)
    await user.click(checkboxes[1])
    await user.click(checkboxes[2])

    await user.click(screen.getByRole('button', { name: '导出所选 2 行' }))
    fireEvent.click(await screen.findByText('导出所选为 JSON'))

    expect(confirmDataExport).toHaveBeenCalledWith(expect.objectContaining({
      format: 'json',
      scope: 'selected',
      rows: [{ id: 1 }, { id: 2 }],
    }))
  })

  it('remeasures the virtual table after query rows change', async () => {
    const handleResize = vi.fn()
    window.addEventListener('resize', handleResize)
    const columns = [{
      name: 'id',
      type: 'INT',
      nullable: false,
      isPrimaryKey: true,
      isAutoIncrement: false,
    }]

    const { rerender } = render(
      <EditableDataTable
        connectionId="connection-1"
        database="database-1"
        tableName="users"
        columns={columns}
        dataSource={[]}
        onRefresh={() => {}}
      />,
    )
    await waitFor(() => expect(handleResize).toHaveBeenCalled())
    handleResize.mockClear()

    rerender(
      <EditableDataTable
        connectionId="connection-1"
        database="database-1"
        tableName="users"
        columns={columns}
        dataSource={[{ id: 1 }, { id: 2 }]}
        onRefresh={() => {}}
      />,
    )

    await waitFor(() => expect(handleResize).toHaveBeenCalled())
    window.removeEventListener('resize', handleResize)
  })
})
