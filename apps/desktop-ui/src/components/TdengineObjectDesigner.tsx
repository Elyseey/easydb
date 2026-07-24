import React, { useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Checkbox, Divider, Input, InputNumber, Radio, Select, Space, Spin, Steps, Typography,
} from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import type {
  TimeSeriesCreateKind,
  TimeSeriesCreateResult,
  TimeSeriesDataType,
  TimeSeriesTagDefinition,
  TimeSeriesTagValueDraft,
} from '@/types'
import { metadataApi } from '@/services/api'
import { DdlViewer } from '@/components/DdlViewer'
import { handleApiError, toast } from '@/utils/notification'
import {
  buildTimeSeriesCreateDefinition,
  TIME_SERIES_STRING_TYPES,
  validateTimeSeriesDefinition,
  validateTimeSeriesTagValues,
  type TimeSeriesFieldRow,
} from '@/utils/timeSeriesDesigner'

const { Text, Title } = Typography

type FieldRow = TimeSeriesFieldRow

const TYPE_OPTIONS: { value: TimeSeriesDataType; label: string }[] = [
  'TIMESTAMP', 'BOOL', 'TINYINT', 'TINYINT_UNSIGNED', 'SMALLINT', 'SMALLINT_UNSIGNED',
  'INT', 'INT_UNSIGNED', 'BIGINT', 'BIGINT_UNSIGNED', 'FLOAT', 'DOUBLE', 'BINARY', 'VARCHAR', 'NCHAR',
].map((value) => ({ value: value as TimeSeriesDataType, label: value.replace('_UNSIGNED', ' UNSIGNED') }))

const newId = () => `${Date.now()}-${Math.random().toString(36).slice(2)}`
const initialColumns = (): FieldRow[] => [
  { id: newId(), name: 'ts', type: 'TIMESTAMP' },
  { id: newId(), name: 'value', type: 'DOUBLE' },
]
const initialTags = (): FieldRow[] => [
  { id: newId(), name: '', type: 'VARCHAR', length: 64 },
]
const IDENTIFIER_INPUT_PROPS = {
  autoCapitalize: 'none',
  autoCorrect: 'off',
  spellCheck: false,
} as const

interface Props {
  connectionId: string
  database: string
  stableNames: string[]
  onSuccess: (result: TimeSeriesCreateResult) => void
  onCancel: () => void
}

export const TdengineObjectDesigner: React.FC<Props> = ({
  connectionId, database, stableNames, onSuccess, onCancel,
}) => {
  const [step, setStep] = useState(0)
  const [kind, setKind] = useState<TimeSeriesCreateKind>('SUPER_TABLE')
  const [name, setName] = useState('')
  const [columns, setColumns] = useState<FieldRow[]>(initialColumns)
  const [tags, setTags] = useState<FieldRow[]>(initialTags)
  const [stableName, setStableName] = useState<string>()
  const [tagDefinitions, setTagDefinitions] = useState<TimeSeriesTagDefinition[]>([])
  const [tagValues, setTagValues] = useState<TimeSeriesTagValueDraft[]>([])
  const [comment, setComment] = useState('')
  const [touched, setTouched] = useState(false)
  const [loadingTags, setLoadingTags] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const [creating, setCreating] = useState(false)
  const [ddl, setDdl] = useState('')
  const [previewKey, setPreviewKey] = useState('')

  const definition = useMemo(() => buildTimeSeriesCreateDefinition({
    kind, name, columns, tags, stableName, tagValues, comment,
  }), [kind, name, columns, tags, stableName, tagValues, comment])
  const definitionKey = useMemo(() => JSON.stringify(definition), [definition])
  const errors = useMemo(() => [
    ...validateTimeSeriesDefinition(definition),
    ...(kind === 'CHILD_TABLE' ? validateTimeSeriesTagValues(tagDefinitions, tagValues) : []),
  ], [definition, kind, tagDefinitions, tagValues])
  const previewCurrent = Boolean(ddl) && previewKey === definitionKey

  useEffect(() => {
    if (previewKey && previewKey !== definitionKey) setDdl('')
  }, [definitionKey, previewKey])

  const changeKind = (next: TimeSeriesCreateKind) => {
    setKind(next)
    setTouched(false)
    setStep(0)
  }

  const loadStableTags = async (nextStable: string) => {
    setStableName(nextStable)
    setLoadingTags(true)
    try {
      const definitions = await metadataApi.timeSeriesTagDefinitions(connectionId, database, nextStable) as TimeSeriesTagDefinition[]
      setTagDefinitions(definitions)
      setTagValues(definitions.map((tag) => ({ name: tag.name, value: '', isNull: false })))
    } catch (error) {
      setTagDefinitions([])
      setTagValues([])
      handleApiError(error, '加载父超级表 Tags 失败')
    } finally {
      setLoadingTags(false)
    }
  }

  const updateField = (setter: React.Dispatch<React.SetStateAction<FieldRow[]>>, id: string, patch: Partial<FieldRow>) => {
    setter((rows) => rows.map((row) => row.id === id ? { ...row, ...patch } : row))
  }

  const renderFields = (rows: FieldRow[], setter: React.Dispatch<React.SetStateAction<FieldRow[]>>, role: '字段' | 'Tag') => (
    <Space orientation="vertical" size={8} style={{ width: '100%' }}>
      {rows.map((field, index) => {
        const locked = role === '字段' && index === 0
        return (
          <div key={field.id} style={{ display: 'grid', gridTemplateColumns: 'minmax(180px, 1fr) 220px 130px 36px', gap: 8, alignItems: 'center' }}>
            <Input
              {...IDENTIFIER_INPUT_PROPS}
              aria-label={`${role}名称 ${index + 1}`}
              value={field.name}
              maxLength={64}
              onBlur={() => setTouched(true)}
              onChange={(event) => updateField(setter, field.id, { name: event.target.value })}
              placeholder={`${role}名称`}
            />
            <Select
              aria-label={`${role}类型 ${index + 1}`}
              value={field.type}
              disabled={locked}
              options={TYPE_OPTIONS}
              onChange={(type) => updateField(setter, field.id, { type, length: TIME_SERIES_STRING_TYPES.has(type) ? (field.length || 64) : undefined })}
            />
            {TIME_SERIES_STRING_TYPES.has(field.type) ? (
              <InputNumber
                aria-label={`${role}长度 ${index + 1}`}
                value={field.length}
                min={1}
                precision={0}
                onChange={(length) => updateField(setter, field.id, { length: length ?? undefined })}
                style={{ width: '100%' }}
              />
            ) : <Text type="secondary">—</Text>}
            <Button
              aria-label={`删除${role} ${index + 1}`}
              type="text"
              danger
              icon={<DeleteOutlined />}
              disabled={locked || (role === '字段' && rows.length <= 2) || (role === 'Tag' && rows.length <= 1)}
              onClick={() => setter((current) => current.filter((item) => item.id !== field.id))}
            />
          </div>
        )
      })}
      <Button
        icon={<PlusOutlined />}
        onClick={() => setter((current) => [...current, { id: newId(), name: '', type: role === 'Tag' ? 'VARCHAR' : 'DOUBLE', ...(role === 'Tag' ? { length: 64 } : {}) }])}
      >
        添加{role}
      </Button>
    </Space>
  )

  const preview = async () => {
    setTouched(true)
    if (errors.length > 0) return
    setPreviewing(true)
    try {
      const result = await metadataApi.previewTimeSeriesObject(connectionId, database, definition)
      setDdl(result.ddl)
      setPreviewKey(definitionKey)
      setStep(1)
    } catch (error) {
      handleApiError(error, '生成 TDengine DDL 预览失败')
    } finally {
      setPreviewing(false)
    }
  }

  const create = async () => {
    if (!previewCurrent || creating) return
    setCreating(true)
    try {
      const result = await metadataApi.createTimeSeriesObject(connectionId, database, definition)
      toast.success(`时序对象「${result.name}」创建成功`)
      onSuccess(result)
    } catch (error) {
      handleApiError(error, '创建 TDengine 时序对象失败')
    } finally {
      setCreating(false)
    }
  }

  return (
    <div style={{ height: '100%', overflow: 'auto', padding: 20 }}>
      <div style={{ maxWidth: 1040, margin: '0 auto', minHeight: '100%', display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>新建 TDengine 对象</Title>
          <Text type="secondary">{database} · 超级表、普通表与单个子表使用同一创建入口</Text>
        </div>
        <Steps current={step} items={[{ title: '配置对象' }, { title: '预览并确认' }]} />

        {step === 0 ? (
          <div style={{ padding: 20, border: '1px solid var(--glass-border)', borderRadius: 12, background: 'var(--glass-panel)' }}>
            <Space orientation="vertical" size={18} style={{ width: '100%' }}>
              <div>
                <Text strong style={{ display: 'block', marginBottom: 8 }}>对象类型</Text>
                <Radio.Group
                  optionType="button"
                  buttonStyle="solid"
                  value={kind}
                  onChange={(event) => changeKind(event.target.value)}
                  options={[
                    { value: 'SUPER_TABLE', label: '超级表' },
                    { value: 'BASIC_TABLE', label: '普通表' },
                    { value: 'CHILD_TABLE', label: '子表' },
                  ]}
                />
              </div>
              <div>
                <Text strong style={{ display: 'block', marginBottom: 8 }}>对象名称</Text>
                <Input
                  {...IDENTIFIER_INPUT_PROPS}
                  aria-label="对象名称"
                  value={name}
                  maxLength={192}
                  onBlur={() => setTouched(true)}
                  onChange={(event) => setName(event.target.value)}
                  placeholder="请输入对象名称"
                />
              </div>

              {kind !== 'CHILD_TABLE' ? (
                <>
                  <Divider style={{ margin: 0 }} />
                  <div>
                    <Text strong style={{ display: 'block', marginBottom: 4 }}>数据字段</Text>
                    <Text type="secondary" style={{ display: 'block', marginBottom: 10 }}>首列自动设为 TIMESTAMP，可重命名，不可删除或修改类型。</Text>
                    {renderFields(columns, setColumns, '字段')}
                  </div>
                  {kind === 'SUPER_TABLE' && (
                    <div>
                      <Text strong style={{ display: 'block', marginBottom: 10 }}>Tags</Text>
                      {renderFields(tags, setTags, 'Tag')}
                    </div>
                  )}
                  <div>
                    <Text strong style={{ display: 'block', marginBottom: 8 }}>COMMENT（可选）</Text>
                    <Input.TextArea value={comment} rows={3} onBlur={() => setTouched(true)} onChange={(event) => setComment(event.target.value)} showCount maxLength={1024} />
                  </div>
                </>
              ) : (
                <>
                  <div>
                    <Text strong style={{ display: 'block', marginBottom: 8 }}>父超级表</Text>
                    <Select
                      aria-label="父超级表"
                      showSearch
                      value={stableName}
                      placeholder="请选择当前数据库中的超级表"
                      options={stableNames.map((stable) => ({ value: stable, label: stable }))}
                      onChange={loadStableTags}
                      style={{ width: '100%' }}
                    />
                  </div>
                  <Spin spinning={loadingTags}>
                    <Space orientation="vertical" size={8} style={{ width: '100%' }}>
                      {tagDefinitions.map((tag, index) => {
                        const value = tagValues[index]
                        return (
                          <div key={tag.name} style={{ display: 'grid', gridTemplateColumns: 'minmax(160px, 0.8fr) minmax(240px, 1.4fr) 110px', gap: 10, alignItems: 'center' }}>
                            <div><Text strong>{tag.name}</Text><Text type="secondary" style={{ display: 'block', fontSize: 12 }}>{tag.type}</Text></div>
                            <Input
                              aria-label={`Tag 值 ${tag.name}`}
                              value={value?.value ?? ''}
                              disabled={value?.isNull}
                              onBlur={() => setTouched(true)}
                              onChange={(event) => setTagValues((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, value: event.target.value } : item))}
                              placeholder="空字符串将按空字符串创建"
                            />
                            <Checkbox
                              checked={value?.isNull}
                              onChange={(event) => setTagValues((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, isNull: event.target.checked } : item))}
                            >显式 NULL</Checkbox>
                          </div>
                        )
                      })}
                    </Space>
                  </Spin>
                </>
              )}

              {touched && errors.length > 0 && (
                <Alert type="error" showIcon title="请修正配置" description={errors.join('；')} />
              )}
              <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
                <Button onClick={onCancel}>取消</Button>
                <Button type="primary" loading={previewing} disabled={loadingTags} onClick={preview}>生成 DDL 预览</Button>
              </Space>
            </Space>
          </div>
        ) : (
          <div style={{ minHeight: 0, flex: 1, display: 'flex', flexDirection: 'column', gap: 12 }}>
            <Alert type="info" showIcon title="DDL 由服务端根据当前表单重新生成；创建时不会执行客户端回传的 SQL。" />
            <div style={{ minHeight: 320, flex: 1, border: '1px solid var(--glass-border)', borderRadius: 12, overflow: 'hidden' }}>
              <DdlViewer ddl={ddl} loading={previewing} />
            </div>
            <Space style={{ justifyContent: 'space-between', width: '100%' }}>
              <Button disabled={creating} onClick={() => setStep(0)}>返回修改</Button>
              <Space>
                <Button disabled={creating} onClick={onCancel}>取消</Button>
                <Button type="primary" loading={creating} disabled={!previewCurrent || previewing || creating} onClick={create}>确认创建</Button>
              </Space>
            </Space>
          </div>
        )}
      </div>
    </div>
  )
}
