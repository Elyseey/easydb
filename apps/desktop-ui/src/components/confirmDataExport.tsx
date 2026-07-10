import { Modal, Space, Tag, Typography } from 'antd'
import { DownloadOutlined } from '@ant-design/icons'
import {
  createResultExportFile,
  createTableExportFile,
  saveExportFile,
  type ExportFormat,
} from '@/utils/exportUtils'
import { handleApiError, toast } from '@/utils/notification'
import type { DbType } from '@/types'

const { Text } = Typography

interface ConfirmDataExportBaseOptions {
  columns: string[]
  rows: Record<string, unknown>[]
  filenameBase: string
  loadedOnly?: boolean
}

type ConfirmDataExportOptions = ConfirmDataExportBaseOptions & (
  | { format: 'csv' | 'json'; tableName?: string }
  | { format: 'sql'; tableName: string; dbType: DbType }
)

const FORMAT_LABELS: Record<ExportFormat, string> = {
  csv: 'CSV',
  json: 'JSON',
  sql: 'SQL INSERT',
}

export function confirmDataExport(options: ConfirmDataExportOptions): void {
  const { columns, rows, format, filenameBase, loadedOnly = false } = options

  Modal.confirm({
    title: '确认导出当前数据？',
    icon: <DownloadOutlined />,
    okText: '选择保存位置',
    cancelText: '取消',
    content: (
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space wrap>
          <Tag color="blue">{FORMAT_LABELS[format]}</Tag>
          <Text>{rows.length} 行</Text>
          <Text>{columns.length} 列</Text>
        </Space>
        <Text type="secondary">
          {loadedOnly
            ? '当前结果仍有未加载数据，本次仅导出表格中已加载的内容。'
            : '确认后将打开系统“另存为”窗口，可选择文件名和保存目录。'}
        </Text>
      </Space>
    ),
    onOk: async () => {
      try {
        const file = options.format === 'sql'
          ? createTableExportFile(options.tableName, columns, rows, options.format, options.dbType)
          : options.tableName
            ? createTableExportFile(options.tableName, columns, rows, options.format)
            : createResultExportFile(columns, rows, options.format, filenameBase)
        const savedPath = await saveExportFile(file)
        if (savedPath) toast.success('导出成功')
      } catch (error) {
        handleApiError(error, '导出数据失败')
        throw error
      }
    },
  })
}
