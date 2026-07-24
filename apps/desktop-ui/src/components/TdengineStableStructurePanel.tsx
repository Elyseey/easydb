import React, { useCallback, useEffect, useMemo, useReducer, useState } from 'react'
import {
  Alert, Button, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography,
} from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type {
  TimeSeriesDataType,
  TimeSeriesLifecycleCommand,
  TimeSeriesLifecycleField,
  TimeSeriesLifecycleOperation,
  TimeSeriesLifecyclePreview,
  TimeSeriesLifecycleResult,
  TimeSeriesLifecycleSnapshot,
} from '@/types'
import { metadataApi } from '@/services/api'
import { handleApiError, toast } from '@/utils/notification'

const { Text, Title } = Typography

const TYPE_OPTIONS: Array<{ value: TimeSeriesDataType; label: string }> = [
  'TIMESTAMP', 'BOOL', 'TINYINT', 'TINYINT_UNSIGNED', 'SMALLINT', 'SMALLINT_UNSIGNED',
  'INT', 'INT_UNSIGNED', 'BIGINT', 'BIGINT_UNSIGNED', 'FLOAT', 'DOUBLE', 'BINARY', 'VARCHAR', 'NCHAR',
].map((value) => ({ value: value as TimeSeriesDataType, label: value.replace('_UNSIGNED', ' UNSIGNED') }))
const VARIABLE_TYPES = new Set<TimeSeriesDataType>(['BINARY', 'VARCHAR', 'NCHAR'])
const IDENTIFIER_INPUT_PROPS = {
  autoCapitalize: 'none',
  autoCorrect: 'off',
  spellCheck: false,
} as const

type FieldRole = 'column' | 'tag'
type EditorTarget = {
  operation: TimeSeriesLifecycleOperation
  role: FieldRole
  field?: TimeSeriesLifecycleField
}

interface EditorState {
  target: EditorTarget | null
  name: string
  type: TimeSeriesDataType
  length?: number
  newName: string
  preview: TimeSeriesLifecyclePreview | null
  confirmationName: string
  submitting: boolean
  validationError: string | null
}

type EditorAction =
  | { type: 'OPEN'; target: EditorTarget; fieldType: TimeSeriesDataType; length?: number }
  | { type: 'CLOSE' }
  | { type: 'SET_NAME'; value: string }
  | { type: 'SET_DATA_TYPE'; value: TimeSeriesDataType }
  | { type: 'SET_LENGTH'; value?: number }
  | { type: 'SET_NEW_NAME'; value: string }
  | { type: 'SET_PREVIEW'; value: TimeSeriesLifecyclePreview | null }
  | { type: 'SET_CONFIRMATION'; value: string }
  | { type: 'SET_SUBMITTING'; value: boolean }
  | { type: 'SET_VALIDATION_ERROR'; value: string | null }

const initialEditorState: EditorState = {
  target: null,
  name: '',
  type: 'DOUBLE',
  length: undefined,
  newName: '',
  preview: null,
  confirmationName: '',
  submitting: false,
  validationError: null,
}

function editorReducer(state: EditorState, action: EditorAction): EditorState {
  switch (action.type) {
    case 'OPEN':
      return {
        ...initialEditorState,
        target: action.target,
        name: action.target.field?.name ?? '',
        type: action.fieldType,
        length: action.length,
      }
    case 'CLOSE': return initialEditorState
    case 'SET_NAME': return { ...state, name: action.value, preview: null, validationError: null }
    case 'SET_DATA_TYPE': return {
      ...state,
      type: action.value,
      length: VARIABLE_TYPES.has(action.value) ? state.length ?? 64 : undefined,
      preview: null,
      validationError: null,
    }
    case 'SET_LENGTH': return { ...state, length: action.value, preview: null, validationError: null }
    case 'SET_NEW_NAME': return { ...state, newName: action.value, preview: null, validationError: null }
    case 'SET_PREVIEW': return { ...state, preview: action.value, confirmationName: '', validationError: null }
    case 'SET_CONFIRMATION': return { ...state, confirmationName: action.value }
    case 'SET_SUBMITTING': return { ...state, submitting: action.value }
    case 'SET_VALIDATION_ERROR': return { ...state, validationError: action.value }
  }
}

function catalogTypeToDataType(type: string): TimeSeriesDataType {
  return type.trim().toUpperCase().replace(/\s+/g, '_') as TimeSeriesDataType
}

function fieldTypeLabel(field: TimeSeriesLifecycleField): string {
  return field.length == null ? field.type : `${field.type}(${field.length})`
}

function operationTitle(operation?: TimeSeriesLifecycleOperation): string {
  switch (operation) {
    case 'ADD_COLUMN': return '新增字段'
    case 'DROP_COLUMN': return '删除字段'
    case 'MODIFY_COLUMN': return '扩展字段长度'
    case 'ADD_TAG': return '新增 Tag'
    case 'DROP_TAG': return '删除 Tag'
    case 'MODIFY_TAG': return '扩展 Tag 长度'
    case 'RENAME_TAG': return '重命名 Tag'
    default: return '结构变更'
  }
}

function maxLength(type: TimeSeriesDataType, role: FieldRole): number {
  if (role === 'column') return type === 'NCHAR' ? 16_379 : 65_517
  return type === 'NCHAR' ? 4_095 : 16_382
}

export interface TdengineStableStructurePanelProps {
  connectionId: string
  database: string
  stable: string
  onApplied: (result: TimeSeriesLifecycleResult, snapshot: TimeSeriesLifecycleSnapshot | null) => Promise<void> | void
}

export const TdengineStableStructurePanel: React.FC<TdengineStableStructurePanelProps> = ({
  connectionId,
  database,
  stable,
  onApplied,
}) => {
  const [snapshot, setSnapshot] = useState<TimeSeriesLifecycleSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [editor, dispatch] = useReducer(editorReducer, initialEditorState)

  const loadSnapshot = useCallback(async (): Promise<TimeSeriesLifecycleSnapshot | null> => {
    setLoading(true)
    setLoadError(null)
    try {
      const next = await metadataApi.timeSeriesLifecycle(connectionId, database, stable)
      setSnapshot(next)
      return next
    } catch (error) {
      const message = error instanceof Error ? error.message : '加载超级表结构失败'
      setLoadError(message)
      handleApiError(error, '加载超级表结构失败')
      return null
    } finally {
      setLoading(false)
    }
  }, [connectionId, database, stable])

  useEffect(() => {
    void loadSnapshot()
  }, [loadSnapshot])

  const allNames = useMemo(
    () => new Set([...(snapshot?.columns ?? []), ...(snapshot?.tags ?? [])].map((field) => field.name)),
    [snapshot],
  )

  const openEditor = (target: EditorTarget) => {
    const fieldType = target.field ? catalogTypeToDataType(target.field.type) : target.role === 'tag' ? 'VARCHAR' : 'DOUBLE'
    const length = target.operation.startsWith('ADD_')
      ? (VARIABLE_TYPES.has(fieldType) ? 64 : undefined)
      : target.field?.length == null ? undefined : target.field.length + 1
    dispatch({ type: 'OPEN', target, fieldType, length })
  }

  const closeEditor = () => {
    if (!editor.submitting) dispatch({ type: 'CLOSE' })
  }

  const validateCommand = (): string | null => {
    const target = editor.target
    if (!target) return '请选择结构操作'
    const candidateName = target.operation === 'RENAME_TAG' ? editor.newName : editor.name
    if (target.operation.startsWith('ADD_') || target.operation === 'RENAME_TAG') {
      const label = target.role === 'tag' ? 'Tag 名' : '字段名'
      if (!candidateName) return `${label}不能为空`
      if (candidateName !== candidateName.trim()) return `${label}不能包含首尾空白`
      if (allNames.has(candidateName)) return `字段或 Tag 已存在：${candidateName}`
    }
    if (target.operation.startsWith('ADD_') && VARIABLE_TYPES.has(editor.type)) {
      if (editor.length == null || editor.length < 1 || editor.length > maxLength(editor.type, target.role)) {
        return `${editor.type} 长度必须在 1 到 ${maxLength(editor.type, target.role)} 之间`
      }
    }
    if (target.operation.startsWith('MODIFY_')) {
      const currentLength = target.field?.length
      if (currentLength == null || editor.length == null || editor.length <= currentLength) {
        return `新长度必须大于当前长度 ${currentLength ?? '-'}`
      }
      if (editor.length > maxLength(editor.type, target.role)) {
        return `新长度不能超过 ${maxLength(editor.type, target.role)}`
      }
    }
    return null
  }

  const buildCommand = (): TimeSeriesLifecycleCommand | null => {
    const target = editor.target
    if (!target) return null
    switch (target.operation) {
      case 'ADD_COLUMN':
      case 'ADD_TAG':
        return {
          operation: target.operation,
          name: editor.name,
          type: editor.type,
          ...(VARIABLE_TYPES.has(editor.type) ? { length: editor.length } : {}),
        }
      case 'MODIFY_COLUMN':
      case 'MODIFY_TAG':
        return {
          operation: target.operation,
          name: target.field!.name,
          type: editor.type,
          length: editor.length,
        }
      case 'RENAME_TAG':
        return { operation: target.operation, name: target.field!.name, newName: editor.newName }
      case 'DROP_COLUMN':
      case 'DROP_TAG':
        return { operation: target.operation, name: target.field!.name }
    }
  }

  const handlePreview = async () => {
    const error = validateCommand()
    if (error) {
      dispatch({ type: 'SET_VALIDATION_ERROR', value: error })
      return
    }
    const command = buildCommand()
    if (!command) return
    dispatch({ type: 'SET_SUBMITTING', value: true })
    try {
      const preview = await metadataApi.previewTimeSeriesLifecycle(connectionId, database, stable, command)
      dispatch({ type: 'SET_PREVIEW', value: preview })
    } catch (errorValue) {
      handleApiError(errorValue, '生成超级表结构 DDL 预览失败')
    } finally {
      dispatch({ type: 'SET_SUBMITTING', value: false })
    }
  }

  const handleApply = async () => {
    const preview = editor.preview
    if (!preview || editor.submitting) return
    dispatch({ type: 'SET_SUBMITTING', value: true })
    try {
      const result = await metadataApi.applyTimeSeriesLifecycle(connectionId, database, stable, {
        command: preview.command,
        expectedFingerprint: preview.snapshot.fingerprint,
        previewToken: preview.previewToken,
        ...(preview.destructive ? { confirmationName: editor.confirmationName } : {}),
      })
      const nextSnapshot = await loadSnapshot()
      toast.success(`${operationTitle(preview.command.operation)}成功`)
      dispatch({ type: 'CLOSE' })
      await onApplied(result, nextSnapshot)
    } catch (errorValue) {
      dispatch({ type: 'SET_PREVIEW', value: null })
      handleApiError(errorValue, '修改超级表结构失败，请重新生成预览')
    } finally {
      dispatch({ type: 'SET_SUBMITTING', value: false })
    }
  }

  const isDestructiveReady = !editor.preview?.destructive || editor.confirmationName === editor.preview.command.name
  const target = editor.target

  const actionColumn = (role: FieldRole) => ({
    title: '操作',
    key: 'actions',
    width: role === 'tag' ? 250 : 190,
    render: (_: unknown, field: TimeSeriesLifecycleField) => {
      if (field.primaryTimestamp) return <Tag color="blue">主时间戳</Tag>
      const variable = field.length != null && VARIABLE_TYPES.has(catalogTypeToDataType(field.type))
      const minimumReached = role === 'tag'
        ? (snapshot?.tags.length ?? 0) <= 1
        : (snapshot?.columns.length ?? 0) <= 2
      return (
        <Space size={4} wrap>
          {variable && (
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEditor({
              operation: role === 'tag' ? 'MODIFY_TAG' : 'MODIFY_COLUMN', role, field,
            })}>扩展长度</Button>
          )}
          {role === 'tag' && (
            <Button type="link" size="small" onClick={() => openEditor({ operation: 'RENAME_TAG', role, field })}>
              重命名
            </Button>
          )}
          <Button
            type="link"
            size="small"
            danger
            icon={<DeleteOutlined />}
            disabled={minimumReached}
            title={minimumReached ? (role === 'tag' ? '超级表至少保留一个 Tag' : '超级表至少保留主时间戳和一个普通字段') : undefined}
            onClick={() => openEditor({ operation: role === 'tag' ? 'DROP_TAG' : 'DROP_COLUMN', role, field })}
          >删除</Button>
        </Space>
      )
    },
  })

  return (
    <div style={{ height: '100%', overflow: 'auto', padding: 16 }}>
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <Space align="start" style={{ width: '100%', justifyContent: 'space-between' }} wrap>
          <div>
            <Title level={5} style={{ margin: 0 }}>超级表结构管理</Title>
            <Space size={16} wrap style={{ marginTop: 6 }}>
              <Text type="secondary">{database}.{stable}</Text>
              <Text type="secondary">影响子表：{snapshot?.affectedChildTables ?? '-'}</Text>
            </Space>
          </div>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void loadSnapshot()}>刷新结构</Button>
        </Space>

        {loadError && <Alert type="error" showIcon title="结构加载失败" description={loadError} action={<Button size="small" onClick={() => void loadSnapshot()}>重试</Button>} />}
        <Alert type="info" showIcon title="每次只执行一项结构变更" description="所有 DDL 由服务端根据实时结构生成；主时间戳不可修改，字符串类型只允许扩展长度。" />

        <section aria-labelledby="tdengine-columns-heading">
          <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}>
            <Text id="tdengine-columns-heading" strong>数据字段（{snapshot?.columns.length ?? 0}）</Text>
            <Button size="small" icon={<PlusOutlined />} disabled={!snapshot} onClick={() => openEditor({ operation: 'ADD_COLUMN', role: 'column' })}>新增字段</Button>
          </Space>
          <Table<TimeSeriesLifecycleField>
            size="small"
            rowKey="name"
            loading={loading && !snapshot}
            pagination={false}
            dataSource={snapshot?.columns ?? []}
            columns={[
              { title: '字段名称', dataIndex: 'name', key: 'name' },
              { title: '类型', key: 'type', width: 220, render: (_, field) => fieldTypeLabel(field) },
              actionColumn('column'),
            ]}
            locale={{ emptyText: loading ? '正在加载字段...' : '暂无字段' }}
          />
        </section>

        <section aria-labelledby="tdengine-tags-heading">
          <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}>
            <Text id="tdengine-tags-heading" strong>Tags（{snapshot?.tags.length ?? 0}）</Text>
            <Button size="small" icon={<PlusOutlined />} disabled={!snapshot} onClick={() => openEditor({ operation: 'ADD_TAG', role: 'tag' })}>新增 Tag</Button>
          </Space>
          <Table<TimeSeriesLifecycleField>
            size="small"
            rowKey="name"
            loading={loading && !snapshot}
            pagination={false}
            dataSource={snapshot?.tags ?? []}
            columns={[
              { title: 'Tag 名称', dataIndex: 'name', key: 'name' },
              { title: '类型', key: 'type', width: 220, render: (_, field) => fieldTypeLabel(field) },
              actionColumn('tag'),
            ]}
            locale={{ emptyText: loading ? '正在加载 Tags...' : '暂无 Tag' }}
          />
        </section>
      </Space>

      <Modal
        open={target != null}
        title={operationTitle(target?.operation)}
        width={680}
        okText={editor.preview ? '确认执行' : '生成 DDL 预览'}
        cancelText="取消"
        okButtonProps={{
          danger: editor.preview?.destructive,
          disabled: Boolean(editor.preview && !isDestructiveReady),
        }}
        confirmLoading={editor.submitting}
        onCancel={closeEditor}
        onOk={editor.preview ? () => void handleApply() : () => void handlePreview()}
        destroyOnHidden
      >
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          {editor.validationError && <Alert role="alert" type="error" showIcon title={editor.validationError} />}

          {!editor.preview && target?.operation.startsWith('ADD_') && (
            <>
              <label htmlFor="tdengine-lifecycle-name">{target.role === 'tag' ? 'Tag 名称' : '字段名称'}</label>
              <Input
                {...IDENTIFIER_INPUT_PROPS}
                id="tdengine-lifecycle-name"
                aria-label={target.role === 'tag' ? 'Tag 名称' : '字段名称'}
                value={editor.name}
                onChange={(event) => dispatch({ type: 'SET_NAME', value: event.target.value })}
              />
              <label htmlFor="tdengine-lifecycle-type">数据类型</label>
              <Select
                id="tdengine-lifecycle-type"
                aria-label="数据类型"
                value={editor.type}
                options={TYPE_OPTIONS}
                onChange={(value) => dispatch({ type: 'SET_DATA_TYPE', value })}
                style={{ width: '100%' }}
              />
              {VARIABLE_TYPES.has(editor.type) && (
                <>
                  <label htmlFor="tdengine-lifecycle-length">类型长度</label>
                  <InputNumber
                    id="tdengine-lifecycle-length"
                    aria-label="类型长度"
                    min={1}
                    max={maxLength(editor.type, target.role)}
                    precision={0}
                    value={editor.length}
                    onChange={(value) => dispatch({ type: 'SET_LENGTH', value: value ?? undefined })}
                    style={{ width: '100%' }}
                  />
                </>
              )}
            </>
          )}

          {!editor.preview && target?.operation.startsWith('MODIFY_') && target.field && (
            <>
              <Text>当前定义：<Text code>{target.field.name} {fieldTypeLabel(target.field)}</Text></Text>
              <label htmlFor="tdengine-lifecycle-new-length">新长度</label>
              <InputNumber
                id="tdengine-lifecycle-new-length"
                aria-label="新长度"
                min={(target.field.length ?? 0) + 1}
                max={maxLength(editor.type, target.role)}
                precision={0}
                value={editor.length}
                onChange={(value) => dispatch({ type: 'SET_LENGTH', value: value ?? undefined })}
                style={{ width: '100%' }}
              />
            </>
          )}

          {!editor.preview && target?.operation === 'RENAME_TAG' && target.field && (
            <>
              <Text>当前 Tag：<Text code>{target.field.name}</Text></Text>
              <label htmlFor="tdengine-lifecycle-new-name">新 Tag 名称</label>
              <Input
                {...IDENTIFIER_INPUT_PROPS}
                id="tdengine-lifecycle-new-name"
                aria-label="新 Tag 名称"
                value={editor.newName}
                onChange={(event) => dispatch({ type: 'SET_NEW_NAME', value: event.target.value })}
              />
            </>
          )}

          {!editor.preview && target?.operation.startsWith('DROP_') && target.field && (
            <Alert
              type="warning"
              showIcon
              title={`将删除 ${target.role === 'tag' ? 'Tag' : '字段'}「${target.field.name}」`}
              description={`该操作将影响当前超级表下 ${snapshot?.affectedChildTables ?? 0} 个子表，下一步会展示服务端 DDL 和精确名称确认。`}
            />
          )}

          {editor.preview && (
            <>
              <Alert
                type={editor.preview.destructive ? 'error' : 'info'}
                showIcon
                title={editor.preview.destructive ? '不可逆结构操作' : '服务端 DDL 预览'}
                description={editor.preview.warnings.join('；') || '确认后将执行以下单条 DDL。'}
              />
              <Text type="secondary">当前影响子表：{editor.preview.snapshot.affectedChildTables}</Text>
              <pre style={{ margin: 0, padding: 12, overflow: 'auto', background: 'var(--edb-bg-surface)', border: '1px solid var(--edb-border-default)', borderRadius: 6 }}>
                {editor.preview.ddl}
              </pre>
              {editor.preview.destructive && (
                <>
                  <label htmlFor="tdengine-lifecycle-confirmation">
                    请输入精确名称 <Text code>{editor.preview.command.name}</Text> 以确认
                  </label>
                  <Input
                    {...IDENTIFIER_INPUT_PROPS}
                    id="tdengine-lifecycle-confirmation"
                    aria-label="确认删除名称"
                    value={editor.confirmationName}
                    onChange={(event) => dispatch({ type: 'SET_CONFIRMATION', value: event.target.value })}
                  />
                </>
              )}
              <Button disabled={editor.submitting} onClick={() => dispatch({ type: 'SET_PREVIEW', value: null })}>返回修改</Button>
            </>
          )}
        </Space>
      </Modal>
    </div>
  )
}
