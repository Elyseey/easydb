import React, { useState } from 'react'
import { Button, Dropdown, Space, Tag, Tooltip } from 'antd'
import { CloseOutlined, CodeOutlined, DownloadOutlined } from '@ant-design/icons'
import type { ExportFormat } from '@/utils/exportUtils'

interface SelectedRowsActionsProps {
  selectedCount: number
  canUseSql: boolean
  onExport: (format: ExportFormat) => void
  onCopyInsert: () => void
  onClear: () => void
  compact?: boolean
  showCount?: boolean
}

export const SelectedRowsActions: React.FC<SelectedRowsActionsProps> = ({
  selectedCount,
  canUseSql,
  onExport,
  onCopyInsert,
  onClear,
  compact = false,
  showCount = true,
}) => {
  const [exportMenuOpen, setExportMenuOpen] = useState(false)
  if (selectedCount === 0) return null

  const items = [
    { key: 'csv', label: '导出所选为 CSV', onClick: () => onExport('csv') },
    { key: 'json', label: '导出所选为 JSON', onClick: () => onExport('json') },
    ...(canUseSql
      ? [{ key: 'sql', label: '导出所选为 SQL INSERT', onClick: () => onExport('sql') }]
      : []),
  ]

  return (
    <Space size={compact ? 2 : 6}>
      {showCount && <Tag color="processing" style={{ marginInlineEnd: 0 }}>已选 {selectedCount} 行</Tag>}
      <Dropdown
        trigger={['click']}
        menu={{ items }}
        onOpenChange={setExportMenuOpen}
      >
        <Tooltip title={`导出所选 ${selectedCount} 行`} open={exportMenuOpen ? false : undefined}>
          <Button
            size="small"
            type={compact ? 'text' : 'default'}
            icon={<DownloadOutlined />}
            aria-label={`导出所选 ${selectedCount} 行`}
          >
            {compact ? null : '导出所选'}
          </Button>
        </Tooltip>
      </Dropdown>
      {canUseSql && (
        <Tooltip title={`复制所选 ${selectedCount} 行为 INSERT`}>
          <Button
            size="small"
            type="text"
            icon={<CodeOutlined />}
            onClick={onCopyInsert}
            aria-label={`复制所选 ${selectedCount} 行为 INSERT`}
          >
            {compact ? null : '复制所选 INSERT'}
          </Button>
        </Tooltip>
      )}
      <Tooltip title="清除选择">
        <Button
          size="small"
          type="text"
          icon={<CloseOutlined />}
          onClick={onClear}
          aria-label="清除选择"
        >
          {compact ? null : '清除'}
        </Button>
      </Tooltip>
    </Space>
  )
}
