import React, { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { metadataApi } from '@/services/api'
import type { TimeSeriesBasicTableCommand, TimeSeriesBasicTableOperation, TimeSeriesBasicTablePreview, TimeSeriesBasicTableSnapshot, TimeSeriesDataType, TimeSeriesLifecycleField } from '@/types'
import { handleApiError, toast } from '@/utils/notification'

const { Text, Title } = Typography
const variableTypes = new Set<TimeSeriesDataType>(['BINARY', 'VARCHAR', 'NCHAR'])
const types: TimeSeriesDataType[] = ['BOOL', 'TINYINT', 'TINYINT_UNSIGNED', 'SMALLINT', 'SMALLINT_UNSIGNED', 'INT', 'INT_UNSIGNED', 'BIGINT', 'BIGINT_UNSIGNED', 'FLOAT', 'DOUBLE', 'BINARY', 'VARCHAR', 'NCHAR']
type Editor = { operation: TimeSeriesBasicTableOperation; field?: TimeSeriesLifecycleField }

export interface TdengineBasicTableStructurePanelProps {
  connectionId: string
  database: string
  table: string
  onApplied: () => Promise<void> | void
}

export const TdengineBasicTableStructurePanel: React.FC<TdengineBasicTableStructurePanelProps> = ({ connectionId, database, table, onApplied }) => {
  const [snapshot, setSnapshot] = useState<TimeSeriesBasicTableSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [editor, setEditor] = useState<Editor | null>(null)
  const [name, setName] = useState('')
  const [newName, setNewName] = useState('')
  const [type, setType] = useState<TimeSeriesDataType>('DOUBLE')
  const [length, setLength] = useState<number>()
  const [preview, setPreview] = useState<TimeSeriesBasicTablePreview | null>(null)
  const [confirmation, setConfirmation] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try { setSnapshot(await metadataApi.timeSeriesBasicTableLifecycle(connectionId, database, table)) }
    catch (error) { handleApiError(error, '加载普通表结构失败') }
    finally { setLoading(false) }
  }, [connectionId, database, table])
  useEffect(() => { void load() }, [load])

  const open = (next: Editor) => {
    setEditor(next); setPreview(null); setConfirmation(''); setName(''); setNewName('')
    const currentType = next.field?.type.trim().toUpperCase().replace(/\s+/g, '_') as TimeSeriesDataType | undefined
    setType(currentType ?? 'DOUBLE')
    setLength(next.operation === 'MODIFY_COLUMN' ? (next.field?.length ?? 0) + 1 : undefined)
  }
  const command = (): TimeSeriesBasicTableCommand => {
    if (!editor) throw new Error('请选择结构操作')
    if (editor.operation === 'ADD_COLUMN') return { operation: editor.operation, name, type, ...(variableTypes.has(type) ? { length } : {}) }
    if (editor.operation === 'MODIFY_COLUMN') return { operation: editor.operation, name: editor.field!.name, type, length }
    if (editor.operation === 'RENAME_COLUMN') return { operation: editor.operation, name: editor.field!.name, newName }
    return { operation: editor.operation, name: editor.field!.name }
  }
  const generatePreview = async () => {
    try {
      const next = command()
      if (next.operation === 'ADD_COLUMN' && (!next.name || next.name !== next.name.trim())) throw new Error('字段名不能为空或包含首尾空白')
      if (next.operation === 'RENAME_COLUMN' && (!next.newName || next.newName !== next.newName.trim())) throw new Error('新字段名不能为空或包含首尾空白')
      if (next.type && variableTypes.has(next.type) && (!next.length || next.length < 1)) throw new Error('可变长类型必须设置长度')
      setLoading(true)
      setPreview(await metadataApi.previewTimeSeriesBasicTableLifecycle(connectionId, database, table, next))
    } catch (error) { handleApiError(error, '生成普通表结构预览失败') } finally { setLoading(false) }
  }
  const apply = async () => {
    if (!preview) return
    setLoading(true)
    try {
      await metadataApi.applyTimeSeriesBasicTableLifecycle(connectionId, database, table, {
        command: preview.command, expectedFingerprint: preview.snapshot.fingerprint, previewToken: preview.previewToken,
        ...(preview.destructive ? { confirmationName: confirmation } : {}),
      })
      toast.success('普通表结构修改成功'); setEditor(null); setPreview(null); await load(); await onApplied()
    } catch (error) { setPreview(null); handleApiError(error, '修改普通表结构失败，请重新预览') } finally { setLoading(false) }
  }

  return <div style={{ height: '100%', overflow: 'auto', padding: 16 }}>
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <div><Title level={5} style={{ margin: 0 }}>普通表结构管理</Title><Text type="secondary">{database}.{table}</Text></div>
        <Space><Button icon={<PlusOutlined />} onClick={() => open({ operation: 'ADD_COLUMN' })}>新增字段</Button><Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>刷新</Button></Space>
      </Space>
      <Alert type="info" showIcon title="TDengine 专属单步结构变更" description="主时间戳不可修改；字符串只能扩展；按 TDengine 3.0.4 使用 48 KB 保守行宽门禁。" />
      <Table<TimeSeriesLifecycleField> rowKey="name" size="small" pagination={false} loading={loading && !snapshot} dataSource={snapshot?.columns ?? []} columns={[
        { title: '字段', dataIndex: 'name' },
        { title: '类型', render: (_, field) => field.length == null ? field.type : `${field.type}(${field.length})` },
        { title: '操作', width: 280, render: (_, field) => field.primaryTimestamp ? <Tag color="blue">主时间戳（不可修改）</Tag> : <Space>
          {field.length != null && <Button type="link" icon={<EditOutlined />} onClick={() => open({ operation: 'MODIFY_COLUMN', field })}>扩展长度</Button>}
          <Button type="link" onClick={() => open({ operation: 'RENAME_COLUMN', field })}>重命名</Button>
          <Button type="link" danger icon={<DeleteOutlined />} disabled={(snapshot?.columns.length ?? 0) <= 2} onClick={() => open({ operation: 'DROP_COLUMN', field })}>删除</Button>
        </Space> },
      ]} />
    </Space>
    <Modal open={editor != null} title="普通表字段变更" onCancel={() => !loading && setEditor(null)} onOk={() => void (preview ? apply() : generatePreview())} okText={preview ? '确认执行' : '生成 DDL 预览'} confirmLoading={loading} okButtonProps={{ danger: preview?.destructive, disabled: Boolean(preview?.destructive && confirmation !== preview.command.name) }} destroyOnHidden>
      <Space orientation="vertical" style={{ width: '100%' }}>
        {!preview && editor?.operation === 'ADD_COLUMN' && <><Text>字段名称</Text><Input autoCapitalize="none" autoCorrect="off" spellCheck={false} value={name} onChange={e => setName(e.target.value)} /><Text>数据类型</Text><Select style={{ width: '100%' }} value={type} options={types.map(value => ({ value, label: value.replace('_UNSIGNED', ' UNSIGNED') }))} onChange={setType} /></>}
        {!preview && editor?.operation === 'MODIFY_COLUMN' && <Text>扩展字段 <Text code>{editor.field?.name}</Text></Text>}
        {!preview && (editor?.operation === 'ADD_COLUMN' || editor?.operation === 'MODIFY_COLUMN') && variableTypes.has(type) && <><Text>新长度</Text><InputNumber style={{ width: '100%' }} min={(editor.field?.length ?? 0) + 1} max={type === 'NCHAR' ? 16_379 : 65_517} value={length} onChange={value => setLength(value ?? undefined)} /></>}
        {!preview && editor?.operation === 'RENAME_COLUMN' && <><Text>将 <Text code>{editor.field?.name}</Text> 重命名为</Text><Input autoCapitalize="none" autoCorrect="off" spellCheck={false} value={newName} onChange={e => setNewName(e.target.value)} /></>}
        {!preview && editor?.operation === 'DROP_COLUMN' && <Alert type="warning" showIcon title={`永久删除字段 ${editor.field?.name}`} />}
        {preview && <><Alert type={preview.destructive ? 'error' : 'info'} showIcon title={preview.warnings.join('；') || '服务端 DDL 预览'} /><pre style={{ padding: 12, overflow: 'auto', background: 'var(--edb-bg-surface)' }}>{preview.ddl}</pre>{preview.destructive && <><Text>输入精确字段名 <Text code>{preview.command.name}</Text></Text><Input value={confirmation} onChange={e => setConfirmation(e.target.value)} /></>}</>}
      </Space>
    </Modal>
  </div>
}
