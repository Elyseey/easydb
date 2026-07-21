import React, { useId, useMemo, useState } from 'react'
import { Button, Input, Select, Space, Tag, Typography, theme } from 'antd'
import { ClockCircleOutlined, SearchOutlined } from '@ant-design/icons'
import {
  anchorTimeSeriesRange,
  createCustomTimeSeriesRange,
  localTimeZoneLabel,
  type TimeSeriesRangePreset,
  type TimeSeriesRangeState,
} from '@/utils/timeSeriesQuery'

const { Text } = Typography

const RANGE_OPTIONS: Array<{ value: TimeSeriesRangePreset; label: string }> = [
  { value: '15m', label: '最近 15 分钟' },
  { value: '1h', label: '最近 1 小时' },
  { value: '6h', label: '最近 6 小时' },
  { value: '24h', label: '最近 24 小时' },
  { value: '7d', label: '最近 7 天' },
  { value: 'custom', label: '自定义范围' },
  { value: 'all', label: '不限时间' },
]

interface TimeSeriesRangeControlProps {
  value: TimeSeriesRangeState
  disabled?: boolean
  onChange: (value: TimeSeriesRangeState) => void
  onQuery: (value: TimeSeriesRangeState) => void | Promise<void>
}

export const TimeSeriesRangeControl: React.FC<TimeSeriesRangeControlProps> = ({
  value,
  disabled = false,
  onChange,
  onQuery,
}) => {
  const { token } = theme.useToken()
  const presetId = useId()
  const [error, setError] = useState('')
  const timezone = useMemo(() => localTimeZoneLabel(), [])

  const update = (next: TimeSeriesRangeState) => {
    setError('')
    onChange(next)
  }

  const selectPreset = (preset: TimeSeriesRangePreset) => {
    if (preset === 'custom') {
      const defaults = createCustomTimeSeriesRange()
      update({
        ...defaults,
        customStartLocal: value.customStartLocal ?? defaults.customStartLocal,
        customEndLocal: value.customEndLocal ?? defaults.customEndLocal,
      })
      return
    }
    update({ preset, applied: false })
  }

  const submit = () => {
    try {
      anchorTimeSeriesRange(value)
      setError('')
      void onQuery(value)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '时间范围无效')
    }
  }

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 8,
        marginBottom: 8,
        padding: '8px 10px',
        border: `1px solid ${token.colorBorderSecondary}`,
        borderRadius: token.borderRadius,
        background: 'var(--edb-bg-surface)',
        flexShrink: 0,
      }}
    >
      <ClockCircleOutlined style={{ color: token.colorTextSecondary }} />
      <label htmlFor={presetId}>
        <Text style={{ fontSize: 12 }}>时间范围</Text>
      </label>
      <Select<TimeSeriesRangePreset>
        id={presetId}
        aria-label="时间范围预设"
        size="small"
        value={value.preset}
        options={RANGE_OPTIONS}
        onChange={selectPreset}
        disabled={disabled}
        style={{ width: 150 }}
      />
      {value.preset === 'custom' && (
        <Space size={6} wrap>
          <Input
            aria-label="开始时间"
            type="datetime-local"
            step={0.000000001}
            size="small"
            value={value.customStartLocal ?? ''}
            onChange={(event) => update({
              ...value,
              applied: false,
              customStartLocal: event.target.value,
              startInclusive: undefined,
              endExclusive: undefined,
            })}
            onPressEnter={submit}
            disabled={disabled}
            style={{ width: 190 }}
          />
          <Text type="secondary" style={{ fontSize: 12 }}>至</Text>
          <Input
            aria-label="结束时间"
            type="datetime-local"
            step={0.000000001}
            size="small"
            value={value.customEndLocal ?? ''}
            onChange={(event) => update({
              ...value,
              applied: false,
              customEndLocal: event.target.value,
              startInclusive: undefined,
              endExclusive: undefined,
            })}
            onPressEnter={submit}
            disabled={disabled}
            style={{ width: 190 }}
          />
        </Space>
      )}
      <Text type="secondary" style={{ fontSize: 11 }}>{timezone}</Text>
      {!value.applied && <Tag color="processing">待查询</Tag>}
      {value.preset === 'all' && <Tag color="warning">可能扫描大量数据</Tag>}
      {value.applied && value.startInclusive && value.endExclusive && (
        <Text
          aria-label="当前固定时间范围"
          type="secondary"
          ellipsis={{ tooltip: `${value.startInclusive} → ${value.endExclusive}` }}
          style={{ fontSize: 11, maxWidth: 420 }}
        >
          当前：{value.startInclusive} → {value.endExclusive}
        </Text>
      )}
      <Button
        aria-label="执行时间范围查询"
        size="small"
        type="primary"
        icon={<SearchOutlined />}
        onClick={submit}
        loading={disabled}
      >
        查询
      </Button>
      {error && (
        <Text type="danger" role="alert" aria-live="polite" style={{ fontSize: 12, flexBasis: '100%' }}>
          {error}
        </Text>
      )}
    </div>
  )
}
