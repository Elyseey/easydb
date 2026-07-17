import type React from 'react'
import { Button, Typography } from 'antd'
import { CopyOutlined } from '@ant-design/icons'
import { handleApiError, toast } from '@/utils/notification'

const { Text } = Typography

interface DdlViewerProps {
  ddl: string
  loading?: boolean
}

const selectContents = (element: HTMLElement) => {
  const selection = window.getSelection()
  if (!selection) return

  const range = document.createRange()
  range.selectNodeContents(element)
  selection.removeAllRanges()
  selection.addRange(range)
}

export const DdlViewer: React.FC<DdlViewerProps> = ({ ddl, loading = false }) => {
  const canCopy = !loading && ddl.trim().length > 0

  const copyDdl = async () => {
    if (!canCopy) return
    try {
      await navigator.clipboard.writeText(ddl)
      toast.success('DDL 已复制')
    } catch (error) {
      handleApiError(error, '复制 DDL 失败')
    }
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLPreElement>) => {
    if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'a') {
      event.preventDefault()
      event.stopPropagation()
      selectContents(event.currentTarget)
    }
  }

  return (
    <div style={{ height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', background: 'var(--glass-panel)' }}>
      <div style={{ flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '8px 12px', borderBottom: '1px solid var(--glass-border)' }}>
        <Text type="secondary" style={{ fontSize: 12 }}>点击代码区后可用 Ctrl/Cmd+A 仅全选 DDL</Text>
        <Button
          size="small"
          icon={<CopyOutlined />}
          aria-label="复制 DDL"
          disabled={!canCopy}
          onClick={copyDdl}
        >
          复制 DDL
        </Button>
      </div>
      <pre
        aria-label="DDL 内容"
        tabIndex={0}
        onKeyDown={handleKeyDown}
        style={{
          flex: 1,
          minHeight: 0,
          margin: 0,
          padding: 16,
          color: 'inherit',
          background: 'var(--glass-panel)',
          fontSize: 12,
          fontFamily: 'var(--font-family-code)',
          overflow: 'auto',
        }}
      >
        {loading ? '正在加载 DDL...' : (ddl || '无 DDL 数据')}
      </pre>
    </div>
  )
}
