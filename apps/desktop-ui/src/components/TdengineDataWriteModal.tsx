import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Checkbox, Input, Modal, Segmented, Select, Space, Table, Tag, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { metadataApi } from '@/services/api'
import type { ColumnInfo, TableKind, TimeSeriesChildTable, TimeSeriesTagDefinition, TimeSeriesTagValueDraft, TimeSeriesWriteCell, TimeSeriesWritePreview, TimeSeriesWriteRequest, TimeSeriesWriteResult, TimeSeriesWriteRow } from '@/types'
import { handleApiError, toast } from '@/utils/notification'
import { explicitTimeSeriesNow, parseTimeSeriesPaste, timeSeriesWriteTargetKind, validateExplicitTimeSeriesRows } from './tdengineDataWrite'

const { Text } = Typography
const MAX_ROWS = 100

function blankRow(columns: readonly ColumnInfo[]): TimeSeriesWriteRow {
  return { cells: columns.map(column => ({ name: column.name, value: '', isNull: false })) }
}

export interface TdengineDataWriteModalProps {
  open: boolean
  connectionId: string
  database: string
  table: string
  tableKind: TableKind
  columns: ColumnInfo[]
  onClose: () => void
  onApplied: (result: TimeSeriesWriteResult) => Promise<void> | void
}

export type TdengineDataWriteActionProps = Omit<TdengineDataWriteModalProps, 'open' | 'onClose'>

export const TdengineDataWriteAction: React.FC<TdengineDataWriteActionProps> = (props) => {
  const [open, setOpen] = useState(false)
  return <><Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>写入数据</Button><TdengineDataWriteModal {...props} open={open} onClose={() => setOpen(false)} /></>
}

export const TdengineDataWriteModal: React.FC<TdengineDataWriteModalProps> = ({ open, connectionId, database, table, tableKind, columns, onClose, onApplied }) => {
  const isStable = tableKind === 'SUPER_TABLE'
  const [mode, setMode] = useState<'existing' | 'new'>('existing')
  const [targetTable, setTargetTable] = useState(tableKind === 'SUPER_TABLE' ? '' : table)
  const [children, setChildren] = useState<TimeSeriesChildTable[]>([])
  const [tags, setTags] = useState<TimeSeriesTagDefinition[]>([])
  const [tagValues, setTagValues] = useState<TimeSeriesTagValueDraft[]>([])
  const [rows, setRows] = useState<TimeSeriesWriteRow[]>([blankRow(columns)])
  const [preview, setPreview] = useState<TimeSeriesWritePreview | null>(null)
  const [loading, setLoading] = useState(false)
  const [childrenLoading, setChildrenLoading] = useState(false)
  const childRequestSeq = useRef(0)
  const childSearchTimer = useRef<number | undefined>(undefined)

  const loadChildren = useCallback((search?: string) => {
    const sequence = ++childRequestSeq.current
    setChildrenLoading(true)
    void (metadataApi.timeSeriesChildren(connectionId, database, table, { limit: 100, search }) as Promise<{ items: TimeSeriesChildTable[] }>)
      .then(page => { if (sequence === childRequestSeq.current) setChildren(page.items) })
      .catch(error => { if (sequence === childRequestSeq.current) handleApiError(error, '加载子表失败') })
      .finally(() => { if (sequence === childRequestSeq.current) setChildrenLoading(false) })
  }, [connectionId, database, table])

  const searchChildren = (search: string) => {
    window.clearTimeout(childSearchTimer.current)
    childSearchTimer.current = window.setTimeout(() => loadChildren(search || undefined), 250)
  }

  useEffect(() => {
    if (!open) return
    setMode('existing'); setTargetTable(isStable ? '' : table); setRows([blankRow(columns)]); setPreview(null)
    if (isStable) {
      loadChildren()
      setLoading(true)
      void (metadataApi.timeSeriesTagDefinitions(connectionId, database, table) as Promise<TimeSeriesTagDefinition[]>)
        .then(definitions => { setTags(definitions); setTagValues(definitions.map(tag => ({ name: tag.name, value: '', isNull: false }))) })
        .catch(error => handleApiError(error, '加载 Tags 失败')).finally(() => setLoading(false))
    }
    return () => { window.clearTimeout(childSearchTimer.current); childRequestSeq.current += 1 }
  }, [open, isStable, table, columns, connectionId, database, loadChildren])

  const timestampColumn = useMemo(() => columns.find(column => column.isPrimaryKey && column.type.toUpperCase().startsWith('TIMESTAMP')) ?? columns.find(column => column.type.toUpperCase().startsWith('TIMESTAMP')), [columns])
  const updateCell = (rowIndex: number, columnIndex: number, patch: Partial<TimeSeriesWriteCell>) => {
    setPreview(null)
    setRows(previous => previous.map((row, index) => index !== rowIndex ? row : ({ cells: row.cells.map((cell, cellIndex) => cellIndex === columnIndex ? { ...cell, ...patch } : cell) })))
  }
  const pasteAt = (rowIndex: number, columnIndex: number, text: string) => {
    const matrix = parseTimeSeriesPaste(text)
    if (matrix.length === 0) return
    if (rowIndex + matrix.length > MAX_ROWS) { toast.warning(`单次最多 ${MAX_ROWS} 行`); return }
    setPreview(null)
    setRows(previous => {
      const next = [...previous]
      while (next.length < rowIndex + matrix.length) next.push(blankRow(columns))
      matrix.forEach((values, rowOffset) => values.forEach((value, columnOffset) => {
        const targetColumn = columnIndex + columnOffset
        if (targetColumn < columns.length) {
          const cells = [...next[rowIndex + rowOffset].cells]
          cells[targetColumn] = { ...cells[targetColumn], value, isNull: false }
          next[rowIndex + rowOffset] = { cells }
        }
      }))
      return next
    })
  }
  const request = (): TimeSeriesWriteRequest => {
    const targetKind = timeSeriesWriteTargetKind(tableKind, mode)
    if (!targetTable || targetTable !== targetTable.trim()) throw new Error(mode === 'new' ? '请输入不含首尾空白的新子表名' : '请选择已有子表')
    if (!timestampColumn || !columns.some(column => column.name === timestampColumn.name)) throw new Error('目标缺少主时间戳列')
    validateExplicitTimeSeriesRows(rows, timestampColumn.name)
    return { targetKind, table: targetTable, ...(targetKind === 'NEW_CHILD_TABLE' ? { stableName: table } : {}), columns: columns.map(column => column.name), rows, tagValues: targetKind === 'NEW_CHILD_TABLE' ? tagValues : [] }
  }
  const generatePreview = async () => {
    try { setLoading(true); setPreview(await metadataApi.previewTimeSeriesWrite(connectionId, database, request())) }
    catch (error) { handleApiError(error, '生成写入预览失败') } finally { setLoading(false) }
  }
  const apply = async () => {
    if (!preview) return
    try {
      setLoading(true)
      const result = await metadataApi.applyTimeSeriesWrite(connectionId, database, { request: preview.request, expectedFingerprint: preview.snapshot.fingerprint, previewToken: preview.previewToken })
      toast.success(`已写入 ${result.insertedRows} 行`); await onApplied(result); onClose()
    } catch (error) { setPreview(null); handleApiError(error, '写入失败，输入内容已保留，请重新预览') } finally { setLoading(false) }
  }

  return <Modal open={open} title={isStable ? '向超级表的子表写入数据' : `写入 ${table}`} width="min(94vw, 1200px)" onCancel={() => !loading && onClose()} onOk={() => void (preview ? apply() : generatePreview())} okText={preview ? '确认写入' : '生成写入预览'} confirmLoading={loading} destroyOnHidden>
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="info" showIcon title="追加/同时间戳写入，不是行编辑" description={`时间戳必须显式填写；可粘贴 Excel/TSV 矩形数据；整批最多 ${MAX_ROWS} 行，任一单元格无效时不会执行。`} />
      {isStable && <><Segmented value={mode} options={[{ label: '写入已有子表', value: 'existing' }, { label: '创建子表并写入', value: 'new' }]} onChange={value => { setMode(value as 'existing' | 'new'); setTargetTable(''); setPreview(null) }} />
        {mode === 'existing' ? <Select showSearch filterOption={false} onSearch={searchChildren} loading={childrenLoading} placeholder="输入子表名由服务端搜索" value={targetTable || undefined} options={children.map(child => ({ value: child.name, label: child.name }))} onChange={value => { setTargetTable(value); setPreview(null) }} style={{ width: '100%' }} /> : <><Input autoCapitalize="none" autoCorrect="off" spellCheck={false} placeholder="新子表名称" value={targetTable} onChange={event => { setTargetTable(event.target.value); setPreview(null) }} />{tags.map((tag, index) => <Space key={tag.name} style={{ width: '100%' }}><Text style={{ width: 160 }}>{tag.name} ({tag.type})</Text><Input disabled={tagValues[index]?.isNull} value={tagValues[index]?.value ?? ''} onChange={event => { setPreview(null); setTagValues(values => values.map((value, i) => i === index ? { ...value, value: event.target.value } : value)) }} /><Checkbox checked={tagValues[index]?.isNull} onChange={event => { setPreview(null); setTagValues(values => values.map((value, i) => i === index ? { ...value, isNull: event.target.checked, ...(event.target.checked ? { value: null } : { value: '' }) } : value)) }}>NULL</Checkbox></Space>)}</>}</>}
      {!preview && <>
        <Space><Button icon={<PlusOutlined />} disabled={rows.length >= MAX_ROWS} onClick={() => setRows(value => [...value, blankRow(columns)])}>新增行</Button><Text type="secondary">{rows.length}/{MAX_ROWS} 行</Text></Space>
        <Table size="small" pagination={false} scroll={{ x: Math.max(900, columns.length * 220) }} rowKey={(_, index) => String(index)} dataSource={rows} columns={[
          { title: '#', width: 56, fixed: 'left', render: (_, __, index) => index + 1 },
          ...columns.map((column, columnIndex) => ({ title: <Space>{column.name}{column.name === timestampColumn?.name && <Tag color="blue">必填时间戳</Tag>}</Space>, width: 220, render: (_: unknown, row: TimeSeriesWriteRow, rowIndex: number) => <Space.Compact style={{ width: '100%' }}><Input aria-label={`${rowIndex + 1}-${column.name}`} disabled={row.cells[columnIndex].isNull} value={row.cells[columnIndex].value ?? ''} onPaste={event => { const text = event.clipboardData.getData('text'); if (text.includes('\t') || text.includes('\n')) { event.preventDefault(); pasteAt(rowIndex, columnIndex, text) } }} onChange={event => updateCell(rowIndex, columnIndex, { value: event.target.value })} />{column.name === timestampColumn?.name && <Button onClick={() => updateCell(rowIndex, columnIndex, { value: explicitTimeSeriesNow(), isNull: false })}>当前时间</Button>}<Checkbox style={{ paddingInline: 6 }} disabled={column.name === timestampColumn?.name} checked={row.cells[columnIndex].isNull} onChange={event => updateCell(rowIndex, columnIndex, { isNull: event.target.checked, ...(event.target.checked ? { value: null } : { value: '' }) })}>NULL</Checkbox></Space.Compact> })),
          { title: '操作', width: 70, fixed: 'right', render: (_: unknown, __: TimeSeriesWriteRow, index: number) => <Button type="text" danger icon={<DeleteOutlined />} disabled={rows.length === 1} onClick={() => { setPreview(null); setRows(value => value.filter((_, rowIndex) => rowIndex !== index)) }} /> },
        ]} />
      </>}
      {preview && <><Alert type="warning" showIcon title={`将向 ${preview.request.table} 写入 ${preview.rowCount} 行${preview.createsChildTable ? '，并创建该子表' : ''}`} description="SQL 仅供展示；确认执行时客户端只提交结构化行值、目标和预览证明。" /><pre style={{ maxHeight: 300, overflow: 'auto', padding: 12, background: 'var(--edb-bg-surface)', border: '1px solid var(--edb-border-default)' }}>{preview.sql}</pre><Button onClick={() => setPreview(null)}>返回修改</Button></>}
    </Space>
  </Modal>
}
