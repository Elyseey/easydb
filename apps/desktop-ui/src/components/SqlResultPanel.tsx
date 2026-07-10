import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Dropdown, Modal, Space, Table, Tag, Tooltip, Typography, theme } from 'antd'
import { CopyOutlined, DownloadOutlined, EditOutlined, StopOutlined } from '@ant-design/icons'
import type { SqlResult, EditabilityReason, DbType } from '@/types'
import { confirmDataExport } from '@/components/confirmDataExport'
import { getEditabilityReasonText, resolveInsertTargetTableName } from '@/utils/editabilityAnalyzer'
import { rowsToSqlInsert } from '@/utils/exportUtils'
import { handleApiError, toast } from '@/utils/notification'
import {
  formatSqlCell,
  isSqlCellTruncated,
  previewSqlCellText,
  stripSqlCellTruncationMarker,
} from '@/pages/sql-editor/queryPreview'
import { startDeferredColumnResize } from '@/utils/columnResize'

const { Text } = Typography

interface SqlResultPanelProps {
  result: SqlResult
  displayLabel: string
  tableHeight: number
  active?: boolean
  loadMoreKey?: string
  currentLoadKey?: string | null
  onLoadMore?: () => void
  onResultMetaChange?: (patch: Partial<SqlResult>) => void
  editabilityReason?: EditabilityReason  // 不可编辑原因
  missingPrimaryKeys?: string[]
  dbType?: DbType
}

interface CellPreviewState {
  column: string
  value: string
  truncated: boolean
}

type SqlResultRow = {
  _key: number
  _rowIndex: number
}

const DEFAULT_RESULT_COLUMN_WIDTH = 180
const MIN_RESULT_COLUMN_WIDTH = 80
const MAX_RESULT_COLUMN_WIDTH = 900

const SqlResultPanelComponent: React.FC<SqlResultPanelProps> = ({
  result,
  displayLabel,
  tableHeight,
  active = true,
  loadMoreKey,
  currentLoadKey,
  onLoadMore,
  editabilityReason,
  missingPrimaryKeys,
  dbType,
}) => {
  const { token } = theme.useToken()
  const [cellPreview, setCellPreview] = useState<CellPreviewState | null>(null)
  const [tableScrollY, setTableScrollY] = useState(Math.max(220, tableHeight - 24))
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({})
  const [columnOrder, setColumnOrder] = useState<string[]>([])
  const dragColumnRef = useRef<string | null>(null)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const toolbarRef = useRef<HTMLDivElement | null>(null)
  const tableWrapperRef = useRef<HTMLDivElement | null>(null)
  const tableBodyRef = useRef<HTMLDivElement | null>(null)
  const scrollTopRef = useRef(0)
  const autoLoadLockRef = useRef(false)
  const isLoadingMore = currentLoadKey === loadMoreKey
  const insertTargetTableName = useMemo(
    () => resolveInsertTargetTableName(result.sql),
    [result.sql]
  )
  const copyInsertDisabled = !insertTargetTableName || !dbType || (result.rows?.length ?? 0) === 0
  const copyInsertTooltip = !insertTargetTableName
    ? '仅支持可确定目标表的单表明细查询'
    : !dbType
      ? '无法识别当前连接的数据库类型'
      : (result.rows?.length ?? 0) === 0
        ? '当前结果没有可复制的数据'
        : result.preview && result.hasMore
          ? '复制当前已加载数据为 INSERT（仍有未加载数据）'
          : '复制当前查询结果为 INSERT'

  const handleCopyAsInsert = useCallback(async () => {
    if (!insertTargetTableName || !dbType || !result.rows?.length) return
    try {
      const sql = rowsToSqlInsert(
        insertTargetTableName,
        result.columns ?? [],
        result.rows,
        dbType,
      )
      await navigator.clipboard.writeText(sql)
      toast.success(result.preview && result.hasMore
        ? `已复制当前加载的 ${result.rows.length} 行 INSERT`
        : `已复制 ${result.rows.length} 行 INSERT`)
    } catch (error) {
      handleApiError(error, '复制 INSERT 失败')
    }
  }, [dbType, insertTargetTableName, result.columns, result.hasMore, result.preview, result.rows])

  const updateMeasuredTableHeight = useCallback(() => {
    const wrapperEl = tableWrapperRef.current
    if (!wrapperEl) return
    const wrapperHeight = wrapperEl.clientHeight
    if (wrapperHeight === 0) return
    const thead = wrapperEl.querySelector('.ant-table-thead')
    const headerHeight = thead ? Math.ceil(thead.getBoundingClientRect().height) : 40
    setTableScrollY(Math.max(220, wrapperHeight - headerHeight - 2))
  }, [])

  const forceVirtualRefresh = useCallback(() => {
    const scrollBody = tableBodyRef.current
    if (scrollBody) {
      scrollBody.scrollTop = scrollTopRef.current
    }
    window.dispatchEvent(new Event('resize'))
    if (!scrollBody) return
    scrollBody.scrollTop = scrollTopRef.current
    scrollBody.dispatchEvent(new Event('scroll'))
  }, [])

  const beginColumnResize = useCallback((column: string, event: React.MouseEvent) => {
    startDeferredColumnResize({
      event,
      startWidth: columnWidths[column] ?? DEFAULT_RESULT_COLUMN_WIDTH,
      minWidth: MIN_RESULT_COLUMN_WIDTH,
      maxWidth: MAX_RESULT_COLUMN_WIDTH,
      boundsElement: containerRef.current,
      onCommit: (width) => {
        setColumnWidths((prev) => {
          if (prev[column] === width) return prev
          return { ...prev, [column]: width }
        })
      },
    })
  }, [columnWidths])

  const moveColumn = useCallback((source: string, target: string) => {
    if (source === target) return
    setColumnOrder((prev) => {
      const current = prev.length > 0 ? prev : (result.columns ?? [])
      const from = current.indexOf(source)
      const to = current.indexOf(target)
      if (from < 0 || to < 0) return current
      const next = [...current]
      const [item] = next.splice(from, 1)
      next.splice(to, 0, item)
      return next
    })
  }, [result.columns])

  const orderedColumns = useMemo(() => {
    const sourceColumns = result.columns ?? []
    const order = columnOrder.length > 0 ? columnOrder : sourceColumns
    return [
      ...order.filter((column) => sourceColumns.includes(column)),
      ...sourceColumns.filter((column) => !order.includes(column)),
    ]
  }, [columnOrder, result.columns])

  const tableData = useMemo<SqlResultRow[]>(
    () => (result.rows ?? []).map((_, index) => ({ _key: index, _rowIndex: index })),
    [result.rows]
  )

  const tableScrollX = useMemo(
    () => Math.max(
      900,
      orderedColumns.reduce(
        (sum, column) => sum + (columnWidths[column] ?? DEFAULT_RESULT_COLUMN_WIDTH),
        0
      )
    ),
    [columnWidths, orderedColumns]
  )

  const maybeLoadMore = useMemo(() => {
    return () => {
      const scrollBody = tableBodyRef.current
      if (!scrollBody || !result.preview || !result.hasMore || !onLoadMore || isLoadingMore || autoLoadLockRef.current) {
        return
      }

      const threshold = 120
      const reachedBottom = scrollBody.scrollTop + scrollBody.clientHeight >= scrollBody.scrollHeight - threshold
      const noOverflowYet = scrollBody.scrollHeight <= scrollBody.clientHeight + 8
      if (!reachedBottom && !noOverflowYet) return

      autoLoadLockRef.current = true
      onLoadMore()
    }
  }, [isLoadingMore, onLoadMore, result.hasMore, result.preview])

  useLayoutEffect(() => {
    const wrapperEl = tableWrapperRef.current
    if (!wrapperEl) return undefined

    updateMeasuredTableHeight()
    requestAnimationFrame(updateMeasuredTableHeight)

    const observer = new ResizeObserver(updateMeasuredTableHeight)
    observer.observe(wrapperEl)
    if (toolbarRef.current) observer.observe(toolbarRef.current)
    return () => observer.disconnect()
  }, [result.hasMore, result.rows?.length, tableHeight, updateMeasuredTableHeight])

  useEffect(() => {
    if (!active) return
    requestAnimationFrame(() => {
      updateMeasuredTableHeight()
      requestAnimationFrame(forceVirtualRefresh)
    })
  }, [active, forceVirtualRefresh, updateMeasuredTableHeight])

  useEffect(() => {
    autoLoadLockRef.current = false
  }, [isLoadingMore, result.rows?.length])

  useEffect(() => {
    const container = containerRef.current
    if (!container) return undefined

    const nextTableBody = (
      container.querySelector('.ant-table-tbody-virtual-holder') ??
      container.querySelector('.ant-table-body')
    ) as HTMLDivElement | null

    tableBodyRef.current = nextTableBody
    if (!nextTableBody) return undefined

    const handleScroll = () => {
      scrollTopRef.current = nextTableBody.scrollTop
      maybeLoadMore()
    }

    nextTableBody.addEventListener('scroll', handleScroll, { passive: true })
    window.requestAnimationFrame(maybeLoadMore)

    return () => {
      nextTableBody.removeEventListener('scroll', handleScroll)
      if (tableBodyRef.current === nextTableBody) {
        tableBodyRef.current = null
      }
    }
  }, [maybeLoadMore, result.rows?.length, tableScrollY])

  const columns = useMemo(() => (
    orderedColumns.map((column) => ({
      title: (
        <div
          draggable
          onDragStart={(event) => {
            dragColumnRef.current = column
            event.dataTransfer.effectAllowed = 'move'
            event.dataTransfer.setData('text/plain', column)
          }}
          onDragOver={(event) => {
            event.preventDefault()
            event.dataTransfer.dropEffect = 'move'
          }}
          onDrop={(event) => {
            event.preventDefault()
            const source = dragColumnRef.current || event.dataTransfer.getData('text/plain')
            dragColumnRef.current = null
            if (source) moveColumn(source, column)
          }}
          onDragEnd={() => { dragColumnRef.current = null }}
          style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, cursor: 'grab', userSelect: 'none' }}
          title="拖动表头调整列顺序"
        >
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>{column}</span>
          <span
            aria-hidden
            onMouseDown={(event) => beginColumnResize(column, event)}
            style={{
              width: 8,
              height: 24,
              cursor: 'col-resize',
              borderRight: `2px solid ${token.colorBorderSecondary}`,
              flex: '0 0 auto',
            }}
            title="拖动调整列宽"
          />
        </div>
      ),
      dataIndex: column,
      key: column,
      width: columnWidths[column] ?? DEFAULT_RESULT_COLUMN_WIDTH,
      ellipsis: true,
      render: (_value: unknown, record: SqlResultRow) => {
        const value = result.rows?.[record._rowIndex]?.[column]
        const cellText = formatSqlCell(value)
        const truncated = isSqlCellTruncated(cellText)
        const cleanValue = stripSqlCellTruncationMarker(cellText)
        const columnWidth = columnWidths[column] ?? DEFAULT_RESULT_COLUMN_WIDTH
        const maxPreviewChars = Math.max(24, Math.min(4096, Math.floor((columnWidth - 24) / 8)))
        return (
          <button
            type="button"
            onClick={() => setCellPreview({ column, value: cleanValue, truncated })}
            style={{
              all: 'unset',
              display: 'block',
              width: '100%',
              cursor: 'pointer',
              fontFamily: 'Menlo, Monaco, Consolas, monospace',
              fontSize: 13,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              color: token.colorText,
            }}
            title={truncated ? '点击查看已加载内容（当前单元格已截断）' : '点击查看内容'}
          >
            {previewSqlCellText(cleanValue, maxPreviewChars)}
          </button>
        )
      },
      onCell: () => ({
        style: {
          fontFamily: 'Menlo, Monaco, Consolas, monospace',
          fontSize: 13,
          padding: '4px 8px',
          whiteSpace: 'nowrap',
        },
      }),
      onHeaderCell: () => ({
        style: { padding: '6px 8px', fontWeight: 600, userSelect: 'none' as const },
      }),
    })) ?? []
  ), [beginColumnResize, columnWidths, moveColumn, orderedColumns, result.rows, token.colorBorderSecondary, token.colorText])

  return (
    <div ref={containerRef} style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div ref={toolbarRef} style={{ display: 'flex', justifyContent: 'flex-end', padding: '2px 0 4px', gap: 8, flexShrink: 0 }}>
        <Space size={8} style={{ flex: 1, flexWrap: 'wrap' }}>
          {result.type === 'query' && (
            editabilityReason ? (
              <Tooltip title={getEditabilityReasonText(editabilityReason, missingPrimaryKeys)}>
                <Tag icon={<StopOutlined />} color="warning" style={{ cursor: 'help' }}>
                  不可编辑
                </Tag>
              </Tooltip>
            ) : (
              <Tooltip title="双击单元格可编辑数据">
                <Tag icon={<EditOutlined />} color="success" style={{ cursor: 'help' }}>
                  可编辑
                </Tag>
              </Tooltip>
            )
          )}
          <Text type="secondary" style={{ fontSize: 12, lineHeight: '24px' }}>
            已加载 {result.loadedRows ?? result.rows?.length ?? 0} 行 · {result.duration}ms
            {typeof result.totalRows === 'number' ? ` · 共 ${result.totalRows} 条` : ''}
          </Text>
        </Space>
        <Dropdown
          menu={{
            items: [
              {
                key: 'csv',
                label: result.preview ? '导出当前已加载为 CSV' : '导出为 CSV',
                onClick: () => confirmDataExport({
                  columns: result.columns ?? [],
                  rows: result.rows ?? [],
                  format: 'csv',
                  filenameBase: displayLabel,
                  loadedOnly: Boolean(result.preview && result.hasMore),
                }),
              },
              {
                key: 'json',
                label: result.preview ? '导出当前已加载为 JSON' : '导出为 JSON',
                onClick: () => confirmDataExport({
                  columns: result.columns ?? [],
                  rows: result.rows ?? [],
                  format: 'json',
                  filenameBase: displayLabel,
                  loadedOnly: Boolean(result.preview && result.hasMore),
                }),
              },
            ],
          }}
        >
          <Typography.Link>
            <Space size={4}>
              <DownloadOutlined />
              导出
            </Space>
          </Typography.Link>
        </Dropdown>
        <Tooltip title={copyInsertTooltip}>
          <Button
            type="text"
            size="small"
            icon={<CopyOutlined />}
            disabled={copyInsertDisabled}
            onClick={handleCopyAsInsert}
          >
            复制 INSERT
          </Button>
        </Tooltip>
      </div>

      <div ref={tableWrapperRef} style={{ flex: 1, overflow: 'hidden', minHeight: 0 }}>
        <Table
          bordered
          virtual
          className="sql-spreadsheet-grid"
          columns={columns}
          dataSource={tableData}
          rowKey="_key"
          pagination={false}
          size="small"
          scroll={{ x: tableScrollX, y: tableScrollY }}
          rowClassName={(_, index) => index % 2 === 0 ? 'table-row-light' : 'table-row-dark'}
        />
      </div>

      <Modal
        open={Boolean(cellPreview)}
        title={cellPreview ? `查看单元格：${cellPreview.column}` : '查看单元格'}
        footer={null}
        width={720}
        onCancel={() => setCellPreview(null)}
      >
        {cellPreview?.truncated && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="当前单元格内容已在预览模式中截断"
            description="为避免大结果集拖慢页面，这里显示的是当前已加载内容，不一定是数据库中的完整值。"
          />
        )}
        <div
          style={{
            maxHeight: 480,
            overflow: 'auto',
            padding: 12,
            borderRadius: token.borderRadius,
            background: token.colorFillAlter,
            border: '1px solid var(--glass-border)',
            fontFamily: 'Menlo, Monaco, Consolas, monospace',
            fontSize: 13,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          {cellPreview?.value ?? ''}
        </div>
      </Modal>
    </div>
  )
}

export const SqlResultPanel = React.memo(SqlResultPanelComponent)
SqlResultPanel.displayName = 'SqlResultPanel'
