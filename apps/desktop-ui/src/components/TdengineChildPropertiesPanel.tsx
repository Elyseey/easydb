import React, { useState } from 'react'
import { Button, Checkbox, Input, InputNumber, Modal, Space, Table, Typography } from 'antd'
import { EditOutlined } from '@ant-design/icons'
import type {
  TimeSeriesChildPropertyCommand,
  TimeSeriesChildPropertyPreview,
  TimeSeriesChildPropertySnapshot,
  TimeSeriesTagValue,
} from '@/types'
import { metadataApi } from '@/services/api'
import { handleApiError, toast } from '@/utils/notification'

const { Text } = Typography
const { TextArea } = Input

type EditorTarget =
  | { kind: 'tag'; tag: TimeSeriesTagValue }
  | { kind: 'ttl' }
  | { kind: 'comment' }

export interface TdengineChildPropertiesPanelProps {
  connectionId: string
  database: string
  table: string
  snapshot?: TimeSeriesChildPropertySnapshot
  loading: boolean
  onApplied: () => Promise<void> | void
  onOpenStableStructure: (stableName: string) => void
}

export const TdengineChildPropertiesPanel: React.FC<TdengineChildPropertiesPanelProps> = ({
  connectionId,
  database,
  table,
  snapshot,
  loading,
  onApplied,
  onOpenStableStructure,
}) => {
  const [target, setTarget] = useState<EditorTarget | null>(null)
  const [value, setValue] = useState('')
  const [isNull, setIsNull] = useState(false)
  const [ttl, setTtl] = useState(0)
  const [comment, setComment] = useState('')
  const [preview, setPreview] = useState<TimeSeriesChildPropertyPreview | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const closeEditor = () => {
    if (submitting) return
    setTarget(null)
    setPreview(null)
  }

  const openTagEditor = (tag: TimeSeriesTagValue) => {
    setTarget({ kind: 'tag', tag })
    setValue(tag.value ?? '')
    setIsNull(tag.value == null)
    setPreview(null)
  }

  const openTtlEditor = () => {
    setTarget({ kind: 'ttl' })
    setTtl(snapshot?.ttl ?? 0)
    setPreview(null)
  }

  const openCommentEditor = () => {
    setTarget({ kind: 'comment' })
    setComment(snapshot?.comment ?? '')
    setPreview(null)
  }

  const buildCommand = (): TimeSeriesChildPropertyCommand | null => {
    if (!target) return null
    if (target.kind === 'tag') {
      return {
        operation: 'SET_TAG',
        tagName: target.tag.name,
        value: isNull ? null : value,
        isNull,
      }
    }
    if (target.kind === 'ttl') return { operation: 'SET_TTL', ttl }
    return { operation: 'SET_COMMENT', comment }
  }

  const handlePreview = async () => {
    const command = buildCommand()
    if (!command) return
    setSubmitting(true)
    try {
      setPreview(await metadataApi.previewTimeSeriesChildProperty(connectionId, database, table, command))
    } catch (error) {
      handleApiError(error, '生成子表属性 DDL 预览失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleApply = async () => {
    if (!preview) return
    setSubmitting(true)
    try {
      await metadataApi.applyTimeSeriesChildProperty(connectionId, database, table, {
        command: preview.command,
        expectedFingerprint: preview.snapshot.fingerprint,
        previewToken: preview.previewToken,
      })
      toast.success('子表属性修改成功')
      setTarget(null)
      setPreview(null)
      await onApplied()
    } catch (error) {
      handleApiError(error, '修改子表属性失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div style={{ height: '100%', overflow: 'auto', padding: 16 }}>
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Space size={24} wrap>
          <span><Text type="secondary">所属超级表：</Text><Text strong>{snapshot?.stableName ?? '-'}</Text></span>
          <Button size="small" disabled={!snapshot?.stableName} onClick={() => snapshot?.stableName && onOpenStableStructure(snapshot.stableName)}>打开超级表结构管理</Button>
          <span><Text type="secondary">TTL：</Text><Text>{snapshot?.ttl ?? 0}</Text></span>
          <Button size="small" icon={<EditOutlined />} disabled={!snapshot} onClick={openTtlEditor}>修改 TTL</Button>
        </Space>
        <Space align="start" style={{ width: '100%', justifyContent: 'space-between' }}>
          <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
            <Text type="secondary">COMMENT：</Text>
            <Text>{snapshot?.comment === '' ? '（空字符串）' : snapshot?.comment ?? 'NULL'}</Text>
          </span>
          <Button size="small" icon={<EditOutlined />} disabled={!snapshot} onClick={openCommentEditor}>修改 COMMENT</Button>
        </Space>
        <Table<TimeSeriesTagValue>
          size="small"
          rowKey="name"
          loading={loading}
          pagination={false}
          dataSource={snapshot?.tagValues ?? []}
          columns={[
            { title: 'Tag 名称', dataIndex: 'name', key: 'name', width: 240 },
            { title: '类型', dataIndex: 'type', key: 'type', width: 200 },
            {
              title: '值',
              dataIndex: 'value',
              key: 'value',
              render: (tagValue: string | null | undefined) => tagValue == null
                ? <Text type="secondary">NULL</Text>
                : tagValue === '' ? <Text type="secondary">（空字符串）</Text> : tagValue,
            },
            {
              title: '操作',
              key: 'action',
              width: 90,
              render: (_, tag) => <Button type="link" size="small" onClick={() => openTagEditor(tag)}>编辑</Button>,
            },
          ]}
          locale={{ emptyText: loading ? '正在加载子表属性...' : '暂无 Tag 信息' }}
        />
      </Space>

      <Modal
        open={target != null}
        title={target?.kind === 'tag' ? `修改 Tag · ${target.tag.name}` : target?.kind === 'ttl' ? '修改 TTL' : '修改 COMMENT'}
        width={640}
        okText={preview ? '确认执行' : '生成 DDL 预览'}
        cancelText="取消"
        confirmLoading={submitting}
        onCancel={closeEditor}
        onOk={preview ? handleApply : handlePreview}
      >
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          {!preview && target?.kind === 'tag' && (
            <>
              <Text type="secondary">类型：{target.tag.type}</Text>
              <Checkbox checked={isNull} onChange={(event) => setIsNull(event.target.checked)}>写入 NULL</Checkbox>
              <Input value={value} disabled={isNull} onChange={(event) => setValue(event.target.value)} placeholder="空内容表示空字符串" />
            </>
          )}
          {!preview && target?.kind === 'ttl' && (
            <InputNumber
              min={0}
              max={2147483647}
              precision={0}
              value={ttl}
              style={{ width: '100%' }}
              onChange={(next) => setTtl(next ?? 0)}
            />
          )}
          {!preview && target?.kind === 'comment' && (
            <TextArea value={comment} maxLength={1024} showCount rows={5} onChange={(event) => setComment(event.target.value)} />
          )}
          {preview && (
            <>
              <Text type="secondary">服务端已根据当前属性快照生成以下单条 DDL：</Text>
              <pre style={{ margin: 0, padding: 12, overflow: 'auto', background: 'var(--edb-bg-surface)', border: '1px solid var(--edb-border-default)', borderRadius: 6 }}>
                {preview.ddl}
              </pre>
            </>
          )}
        </Space>
      </Modal>
    </div>
  )
}
