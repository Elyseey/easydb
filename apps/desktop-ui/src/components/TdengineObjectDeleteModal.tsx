import { useEffect, useRef, useState } from 'react'
import { Alert, Descriptions, Input, Modal, Space, Typography } from 'antd'
import type { TimeSeriesDeletePreview, TimeSeriesDeleteResult } from '@/types'
import { metadataApi } from '@/services/api'
import { handleApiError } from '@/utils/notification'

export interface TdengineObjectDeleteTarget {
  connectionId: string
  database: string
  objectName: string
}

interface Props {
  target: TdengineObjectDeleteTarget | null
  onCancel: () => void
  onDeleted: (result: TimeSeriesDeleteResult) => void
}

const KIND_LABELS = {
  BASIC_TABLE: '普通表',
  CHILD_TABLE: '子表',
  SUPER_TABLE: '超级表',
} as const

export function TdengineObjectDeleteModal({ target, onCancel, onDeleted }: Props) {
  const [preview, setPreview] = useState<TimeSeriesDeletePreview | null>(null)
  const [confirmationName, setConfirmationName] = useState('')
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const onCancelRef = useRef(onCancel)
  onCancelRef.current = onCancel

  useEffect(() => {
    setPreview(null)
    setConfirmationName('')
    if (!target) return undefined

    let active = true
    setLoading(true)
    void metadataApi.previewTimeSeriesObjectDelete(
      target.connectionId,
      target.database,
      target.objectName,
    ).then((result) => {
      if (active) setPreview(result)
    }).catch((error) => {
      if (active) {
        handleApiError(error, '加载删除预览失败')
        onCancelRef.current()
      }
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => { active = false }
  }, [target])

  const apply = async () => {
    if (!target || !preview || confirmationName !== preview.snapshot.name || submitting) return
    setSubmitting(true)
    try {
      const result = await metadataApi.applyTimeSeriesObjectDelete(
        target.connectionId,
        target.database,
        target.objectName,
        {
          expectedFingerprint: preview.snapshot.fingerprint,
          previewToken: preview.previewToken,
          confirmationName,
        },
      )
      onDeleted(result)
    } catch (error) {
      handleApiError(error, '删除 TDengine 对象失败')
    } finally {
      setSubmitting(false)
    }
  }

  const name = preview?.snapshot.name ?? target?.objectName ?? ''
  return (
    <Modal
      open={target !== null}
      title={`删除 TDengine 对象「${name}」`}
      okText="永久删除"
      okButtonProps={{ danger: true, disabled: !preview || confirmationName !== name }}
      cancelText="取消"
      confirmLoading={submitting}
      loading={loading}
      onOk={() => void apply()}
      onCancel={onCancel}
      destroyOnHidden
    >
      {preview && (
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          {preview.warnings.map((warning) => (
            <Alert key={warning} type="error" showIcon title={warning} />
          ))}
          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="对象类型">{KIND_LABELS[preview.snapshot.kind]}</Descriptions.Item>
            {preview.snapshot.stableName && (
              <Descriptions.Item label="所属超级表">{preview.snapshot.stableName}</Descriptions.Item>
            )}
            {preview.snapshot.kind === 'SUPER_TABLE' && (
              <Descriptions.Item label="受影响子表">{preview.snapshot.affectedChildTables}</Descriptions.Item>
            )}
            <Descriptions.Item label="执行 DDL">
              <Typography.Text code copyable>{preview.ddl}</Typography.Text>
            </Descriptions.Item>
          </Descriptions>
          <div>
            <Typography.Text>
              请输入对象名 <Typography.Text code>{name}</Typography.Text> 以确认：
            </Typography.Text>
            <Input
              aria-label="确认对象名"
              value={confirmationName}
              onChange={(event) => setConfirmationName(event.target.value)}
              autoComplete="off"
              style={{ marginTop: 8 }}
            />
          </div>
        </Space>
      )}
    </Modal>
  )
}
