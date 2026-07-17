import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TableDesigner } from '../TableDesigner'

const apiMocks = vi.hoisted(() => ({
  tableDesign: vi.fn(),
  renameTable: vi.fn(),
  executeSql: vi.fn(),
}))

const notificationMocks = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  handleApiError: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  metadataApi: {
    tableDesign: apiMocks.tableDesign,
    renameTable: apiMocks.renameTable,
  },
  sqlApi: {
    execute: apiMocks.executeSql,
  },
}))

vi.mock('@/utils/notification', () => ({
  toast: {
    success: notificationMocks.success,
    warning: notificationMocks.warning,
    error: notificationMocks.error,
  },
  handleApiError: notificationMocks.handleApiError,
}))

const tableDefinition = {
  table: { name: 'orders', comment: '' },
  columns: [{
    name: 'id',
    type: 'BIGINT(20)',
    nullable: false,
    defaultValue: undefined,
    isPrimaryKey: true,
    isAutoIncrement: true,
    comment: '主键',
  }],
  indexes: [],
}

const renderDesigner = (dbType: 'mysql' | 'dameng' = 'mysql') => {
  const onSuccess = vi.fn()
  render(
    <TableDesigner
      connectionId="conn-1"
      connectionName="测试连接"
      database="ENERGY"
      editTableName="orders"
      dbType={dbType}
      onSuccess={onSuccess}
      onCancel={vi.fn()}
    />,
  )
  return { onSuccess }
}

describe('TableDesigner table rename', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.tableDesign.mockResolvedValue(tableDefinition)
    apiMocks.renameTable.mockResolvedValue({ success: true })
    apiMocks.executeSql.mockResolvedValue({ success: true })
  })

  it('allows renaming without executing an empty structural SQL batch', async () => {
    const { onSuccess } = renderDesigner()
    const tableNameInput = await screen.findByPlaceholderText('表名')

    expect(tableNameInput).toBeEnabled()
    fireEvent.change(tableNameInput, { target: { value: 'orders_archive' } })
    fireEvent.click(screen.getByRole('button', { name: /保存修改/ }))

    await waitFor(() => {
      expect(apiMocks.renameTable).toHaveBeenCalledWith('conn-1', 'ENERGY', 'orders', 'orders_archive')
    })
    expect(apiMocks.executeSql).not.toHaveBeenCalled()
    expect(apiMocks.tableDesign).toHaveBeenCalledTimes(1)
    expect(apiMocks.tableDesign).toHaveBeenCalledWith('conn-1', 'ENERGY', 'orders')
    expect(onSuccess).toHaveBeenCalledWith({
      tableName: 'orders_archive',
      previousTableName: 'orders',
      renamed: true,
    })
  })

  it('applies MySQL structural changes to the original table before renaming', async () => {
    renderDesigner('mysql')
    fireEvent.change(await screen.findByPlaceholderText('表名'), { target: { value: 'orders_v2' } })
    fireEvent.change(screen.getByPlaceholderText('表备注（可选）'), { target: { value: '订单表' } })
    fireEvent.click(screen.getByRole('button', { name: /保存修改/ }))

    await waitFor(() => expect(apiMocks.renameTable).toHaveBeenCalledTimes(1))
    expect(apiMocks.executeSql).toHaveBeenCalledWith(
      'conn-1',
      'ENERGY',
      "ALTER TABLE `orders` COMMENT = '订单表';",
    )
    expect(apiMocks.executeSql.mock.invocationCallOrder[0]).toBeLessThan(apiMocks.renameTable.mock.invocationCallOrder[0])
  })

  it('applies Dameng structural changes to the original table before renaming', async () => {
    renderDesigner('dameng')
    fireEvent.change(await screen.findByPlaceholderText('表名'), { target: { value: 'ORDERS_V2' } })
    fireEvent.change(screen.getByPlaceholderText('表备注（可选）'), { target: { value: '订单表' } })
    fireEvent.click(screen.getByRole('button', { name: /保存修改/ }))

    await waitFor(() => expect(apiMocks.renameTable).toHaveBeenCalledTimes(1))
    expect(apiMocks.executeSql).toHaveBeenCalledWith(
      'conn-1',
      'ENERGY',
      `COMMENT ON TABLE "orders" IS '订单表';`,
    )
    expect(apiMocks.renameTable).toHaveBeenCalledWith('conn-1', 'ENERGY', 'orders', 'ORDERS_V2')
    expect(apiMocks.executeSql.mock.invocationCallOrder[0]).toBeLessThan(apiMocks.renameTable.mock.invocationCallOrder[0])
  })

  it('warns about partial structural success and does not attempt the rename', async () => {
    apiMocks.executeSql
      .mockResolvedValueOnce({ success: true })
      .mockRejectedValueOnce(new Error('second statement failed'))

    renderDesigner('mysql')
    fireEvent.change(await screen.findByPlaceholderText('表名'), { target: { value: 'orders_v2' } })
    fireEvent.click(screen.getByRole('button', { name: /添加字段/ }))
    const fieldNameInputs = screen.getAllByPlaceholderText('field_name')
    fireEvent.change(fieldNameInputs[1], { target: { value: 'note' } })
    fireEvent.change(screen.getByPlaceholderText('表备注（可选）'), { target: { value: '订单表' } })
    fireEvent.click(screen.getByRole('button', { name: /保存修改/ }))

    await waitFor(() => expect(notificationMocks.handleApiError).toHaveBeenCalledTimes(1))
    expect(notificationMocks.warning).toHaveBeenCalledWith(
      '部分修改已生效（1/2 条），请重新打开设计页确认实际结构',
    )
    expect(apiMocks.renameTable).not.toHaveBeenCalled()
  })
})
