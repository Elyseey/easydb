import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { Alert, Button, Checkbox, Input, Modal, Segmented, Select, Space, Table, Tag, Typography } from 'antd'
import { FileAddOutlined } from '@ant-design/icons'
import { metadataApi, taskApi } from '@/services/api'
import type { ColumnInfo, CsvDelimiter, CsvEncoding, SelectedCsvFile, TableKind, TaskInfo, TimeSeriesChildTable, TimeSeriesCsvColumnMapping, TimeSeriesCsvImportConfig, TimeSeriesCsvPreview, TimeSeriesTagDefinition, TimeSeriesTagValueDraft, TimeSeriesWriteTargetKind } from '@/types'
import { handleApiError, toast } from '@/utils/notification'
import { canStartTimeSeriesCsvImport, shouldRefreshAfterCsvImport } from './tdengineCsvImport'

const { Text } = Typography

export interface TdengineCsvImportWizardProps {
  open: boolean
  connectionId: string
  database: string
  table: string
  tableKind: TableKind
  columns: ColumnInfo[]
  onClose: () => void
  onStarted?: (taskId: string) => void
  onCompleted?: (task: TaskInfo) => void | Promise<void>
}

export type TdengineCsvImportActionProps = Omit<TdengineCsvImportWizardProps, 'open' | 'onClose'>

export const TdengineCsvImportAction: React.FC<TdengineCsvImportActionProps> = (props) => {
  const [open, setOpen] = useState(false)
  const polling = useRef<number | undefined>(undefined)
  useEffect(() => () => window.clearTimeout(polling.current), [])
  const pollCompletion = useCallback((taskId: string) => {
    const poll = async () => {
      try {
        const task = await taskApi.detail(taskId) as TaskInfo
        if (task.status === 'completed' || task.status === 'failed' || task.status === 'cancelled') {
          if (shouldRefreshAfterCsvImport(task)) await props.onCompleted?.(task)
          return
        }
      } catch { /* Task Center remains the recovery surface if a transient poll fails. */ }
      polling.current = window.setTimeout(poll, 1500)
    }
    void poll()
  }, [props])
  return <><Button size="small" icon={<FileAddOutlined />} onClick={() => setOpen(true)}>CSV 导入</Button><TdengineCsvImportWizard {...props} onStarted={taskId => { props.onStarted?.(taskId); pollCompletion(taskId) }} open={open} onClose={() => setOpen(false)} /></>
}

export const TdengineCsvImportWizard: React.FC<TdengineCsvImportWizardProps> = ({ open, connectionId, database, table, tableKind, columns, onClose, onStarted }) => {
  const isStable = tableKind === 'SUPER_TABLE'
  const [mode, setMode] = useState<'existing' | 'new'>('existing')
  const [targetTable, setTargetTable] = useState(isStable ? '' : table)
  const [file, setFile] = useState<SelectedCsvFile | null>(null)
  const [encoding, setEncoding] = useState<CsvEncoding>('AUTO')
  const [delimiter, setDelimiter] = useState<CsvDelimiter>('AUTO')
  const [nullMarker, setNullMarker] = useState('NULL')
  const [emptyAsNull, setEmptyAsNull] = useState(false)
  const [mappings, setMappings] = useState<TimeSeriesCsvColumnMapping[]>([])
  const [preview, setPreview] = useState<TimeSeriesCsvPreview | null>(null)
  const [probe, setProbe] = useState<TimeSeriesCsvPreview | null>(null)
  const [children, setChildren] = useState<TimeSeriesChildTable[]>([])
  const [tags, setTags] = useState<TimeSeriesTagDefinition[]>([])
  const [tagValues, setTagValues] = useState<TimeSeriesTagValueDraft[]>([])
  const [loading, setLoading] = useState(false)
  const sequence = useRef(0)
  const childSearchTimer = useRef<number | undefined>(undefined)

  const invalidate = () => setPreview(null)
  const loadStableFields = useCallback(async () => {
    const request = ++sequence.current
    try {
      const [page, definitions] = await Promise.all([
        metadataApi.timeSeriesChildren(connectionId, database, table, { limit: 100 }) as Promise<{ items: TimeSeriesChildTable[] }>,
        metadataApi.timeSeriesTagDefinitions(connectionId, database, table) as Promise<TimeSeriesTagDefinition[]>,
      ])
      if (request !== sequence.current) return
      setChildren(page.items)
      setTags(definitions)
      setTagValues(definitions.map(tag => ({ name: tag.name, value: '', isNull: false })))
    } catch (error) { if (request === sequence.current) handleApiError(error, '加载子表和 Tags 失败') }
  }, [connectionId, database, table])

  useEffect(() => {
    if (!open) return
    setMode('existing'); setTargetTable(isStable ? '' : table); setFile(null); setMappings([]); setProbe(null); setPreview(null)
    setEncoding('AUTO'); setDelimiter('AUTO'); setNullMarker('NULL'); setEmptyAsNull(false)
    if (isStable) void loadStableFields()
    return () => { sequence.current += 1; window.clearTimeout(childSearchTimer.current) }
  }, [open, isStable, table, loadStableFields])

  const targetKind: TimeSeriesWriteTargetKind = tableKind === 'BASIC_TABLE' ? 'BASIC_TABLE' : tableKind === 'CHILD_TABLE' ? 'EXISTING_CHILD_TABLE' : mode === 'new' ? 'NEW_CHILD_TABLE' : 'EXISTING_CHILD_TABLE'
  const config = (): TimeSeriesCsvImportConfig => {
    if (!file) throw new Error('请选择 CSV 文件')
    if (!targetTable || targetTable !== targetTable.trim()) throw new Error(mode === 'new' ? '请输入不含首尾空白的新子表名' : '请选择已有子表')
    if (!nullMarker) throw new Error('NULL 标记不能为空')
    return { filePath: file.path, targetKind, table: targetTable, ...(targetKind === 'NEW_CHILD_TABLE' ? { stableName: table } : {}), tagValues: targetKind === 'NEW_CHILD_TABLE' ? tagValues : [], encoding, delimiter, nullMarker, emptyAsNull, mappings }
  }

  const chooseFile = async () => {
    try {
      const selected = await invoke<SelectedCsvFile | null>('pick_csv_file')
      if (!selected) return
      if (selected.size > 1024 * 1024 * 1024) throw new Error('CSV 文件不能超过 1 GB')
      setFile(selected); setMappings([]); setProbe(null); invalidate()
    } catch (error) { handleApiError(error, '选择 CSV 文件失败') }
  }

  const generatePreview = async () => {
    try {
      setLoading(true)
      const result = await metadataApi.previewTimeSeriesCsvImport(connectionId, database, config())
      setEncoding(result.encoding); setDelimiter(result.delimiter); setMappings(result.suggestedMappings); setProbe(result); setPreview(result)
    } catch (error) { handleApiError(error, 'CSV 预览失败') } finally { setLoading(false) }
  }

  const canStart = canStartTimeSeriesCsvImport(preview)
  const start = async () => {
    if (!preview || !canStart) return
    try {
      setLoading(true)
      const result = await metadataApi.startTimeSeriesCsvImport(connectionId, database, {
        config: preview.config,
        expectedFile: preview.file,
        expectedTargetFingerprint: preview.target.fingerprint,
      })
      toast.success('CSV 导入任务已启动，可在任务中心查看进度；请勿盲目重导原文件')
      onStarted?.(result.taskId)
      onClose()
    } catch (error) { setPreview(null); handleApiError(error, '启动 CSV 导入失败，请重新预览') } finally { setLoading(false) }
  }

  const targetOptions = useMemo(() => columns.map(column => ({ value: column.name, label: `${column.name} (${column.type})` })), [columns])
  const previewColumns = probe?.headers.map((header, index) => ({
    title: <Space orientation="vertical" size={2}><Text>{header || '(空表头)'}</Text><Select allowClear placeholder="忽略" value={mappings.find(item => item.sourceHeader === header)?.targetColumn ?? undefined} options={targetOptions} style={{ width: 180 }} onChange={value => { setMappings(items => items.map(item => item.sourceHeader === header ? { ...item, targetColumn: value ?? null } : item)); invalidate() }} /></Space>,
    width: 210,
    render: (_: unknown, row: TimeSeriesCsvPreview['rows'][number]) => { const cell = row.cells[index]; return <Space orientation="vertical" size={0}><Text type={cell?.error ? 'danger' : undefined}>{cell?.rawValue ?? ''}</Text>{cell?.isNull && <Tag>NULL</Tag>}{cell?.error && <Text type="danger">{cell.error}</Text>}</Space> },
  })) ?? []

  return <Modal open={open} title="TDengine CSV 批量导入" width="min(96vw, 1280px)" onCancel={() => !loading && onClose()} footer={<Space><Button onClick={onClose} disabled={loading}>关闭</Button><Button onClick={() => void generatePreview()} loading={loading}>{preview ? '重新预览' : '生成预览'}</Button><Button type="primary" disabled={!canStart} loading={loading} onClick={() => void start()}>启动后台任务</Button></Space>} destroyOnHidden>
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="info" showIcon title="文件由内核流式读取，不会载入 WebView 内存" description="最多 1 GB / 1000 万条逻辑数据记录。任务失败行会生成可修正后重试的 CSV 回执；不要直接重导原文件。" />
      {isStable && <><Segmented value={mode} options={[{ label: '导入已有子表', value: 'existing' }, { label: '创建单个新子表', value: 'new' }]} onChange={value => { setMode(value as 'existing' | 'new'); setTargetTable(''); invalidate() }} />{mode === 'existing' ? <Select showSearch filterOption={false} value={targetTable || undefined} options={children.map(child => ({ value: child.name, label: child.name }))} onSearch={search => { window.clearTimeout(childSearchTimer.current); const request = ++sequence.current; childSearchTimer.current = window.setTimeout(() => { void (metadataApi.timeSeriesChildren(connectionId, database, table, { limit: 100, search: search || undefined }) as Promise<{ items: TimeSeriesChildTable[] }>).then(page => { if (request === sequence.current) setChildren(page.items) }).catch(error => { if (request === sequence.current) handleApiError(error, '搜索子表失败') }) }, 250) }} onChange={value => { setTargetTable(value); invalidate() }} placeholder="搜索已有子表" style={{ width: '100%' }} /> : <><Input autoCapitalize="none" autoCorrect="off" spellCheck={false} value={targetTable} onChange={event => { setTargetTable(event.target.value); invalidate() }} placeholder="新子表名" />{tags.map((tag, index) => <Space key={tag.name}><Text style={{ width: 180 }}>{tag.name} ({tag.type})</Text><Input autoCapitalize="none" autoCorrect="off" spellCheck={false} disabled={tagValues[index]?.isNull} value={tagValues[index]?.value ?? ''} onChange={event => { setTagValues(values => values.map((item, i) => i === index ? { ...item, value: event.target.value } : item)); invalidate() }} /><Checkbox checked={tagValues[index]?.isNull} onChange={event => { setTagValues(values => values.map((item, i) => i === index ? { ...item, isNull: event.target.checked, value: event.target.checked ? null : '' } : item)); invalidate() }}>NULL</Checkbox></Space>)}</>}</>}
      <Space wrap><Button icon={<FileAddOutlined />} onClick={() => void chooseFile()}>选择 CSV</Button><Text>{file ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(2)} MB` : '未选择文件'}</Text><Select value={encoding} options={[{ value: 'AUTO', label: '自动 UTF-8/BOM' }, { value: 'UTF8', label: 'UTF-8' }, { value: 'GB18030', label: 'GB18030' }]} onChange={value => { setEncoding(value); invalidate() }} /><Select value={delimiter} options={[{ value: 'AUTO', label: '自动分隔符' }, { value: 'COMMA', label: '逗号' }, { value: 'TAB', label: 'Tab' }, { value: 'SEMICOLON', label: '分号' }]} onChange={value => { setDelimiter(value); invalidate() }} /><Input value={nullMarker} onChange={event => { setNullMarker(event.target.value); invalidate() }} addonBefore="NULL 标记" style={{ width: 220 }} /><Checkbox checked={emptyAsNull} onChange={event => { setEmptyAsNull(event.target.checked); invalidate() }}>空字段视为 NULL</Checkbox></Space>
      {probe && <><Alert type={preview && canStart ? 'success' : 'warning'} showIcon title={preview ? (canStart ? '预览校验通过' : '存在阻断错误') : '设置或映射已变化，请重新预览'} description={[...probe.blockingErrors, ...probe.rows.map(row => row.error).filter(Boolean)].join('；') || undefined} /><Table size="small" pagination={false} rowKey="recordNumber" dataSource={probe.rows} columns={[{ title: '逻辑记录', dataIndex: 'recordNumber', width: 90, fixed: 'left' }, ...previewColumns]} scroll={{ x: Math.max(900, previewColumns.length * 210), y: 360 }} /></>}
    </Space>
  </Modal>
}
