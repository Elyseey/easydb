import React, { useMemo, useState } from 'react'
import { Button, Input, Modal, Select, Space, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import type { TimeSeriesTagDefinition, TimeSeriesTagFilter, TimeSeriesTagFilterOperator } from '@/types'
import {
  defaultTagFilterOperator,
  TAG_FILTER_OPERATOR_LABELS,
  tagFilterNeedsValue,
  tagFilterOperators,
} from '@/pages/workbench/tdengineTagFilters'

const { Text } = Typography
const MAX_FILTERS = 8

export interface TdengineTagFilterModalProps {
  open: boolean
  stableName: string
  definitions: TimeSeriesTagDefinition[]
  initialFilters: TimeSeriesTagFilter[]
  loading?: boolean
  onCancel: () => void
  onApply: (filters: TimeSeriesTagFilter[]) => void
}

export const TdengineTagFilterModal: React.FC<TdengineTagFilterModalProps> = ({
  open,
  stableName,
  definitions,
  initialFilters,
  loading,
  onCancel,
  onApply,
}) => {
  const [filters, setFilters] = useState<TimeSeriesTagFilter[]>(() => (
    initialFilters.map((filter) => ({ ...filter }))
  ))

  const definitionsByName = useMemo(
    () => new Map(definitions.map((definition) => [definition.name, definition])),
    [definitions],
  )
  const duplicateNames = new Set(
    filters.map((filter) => filter.name).filter((name, index, all) => all.indexOf(name) !== index),
  )
  const valid = filters.every((filter) => (
    filter.name &&
    definitionsByName.has(filter.name) &&
    !duplicateNames.has(filter.name) &&
    (!tagFilterNeedsValue(filter.operator) || filter.value !== undefined)
  ))

  const addFilter = () => {
    const definition = definitions.find((item) => !filters.some((filter) => filter.name === item.name))
    if (!definition) return
    setFilters((previous) => [
      ...previous,
      { name: definition.name, operator: defaultTagFilterOperator(definition), value: '' },
    ])
  }

  return (
    <Modal
      open={open}
      title={`按 Tag 筛选 · ${stableName}`}
      width={720}
      okText="应用筛选"
      cancelText="取消"
      confirmLoading={loading}
      okButtonProps={{ disabled: !valid }}
      onCancel={onCancel}
      onOk={() => onApply(filters.map((filter) => (
        tagFilterNeedsValue(filter.operator) ? filter : { ...filter, value: undefined }
      )))}
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Text type="secondary">多个条件采用 AND 关系，最多 {MAX_FILTERS} 个。空字符串是有效值，与 NULL 不同。</Text>
        {filters.map((filter, index) => {
          const definition = definitionsByName.get(filter.name)
          const operators: TimeSeriesTagFilterOperator[] = definition ? tagFilterOperators(definition.type) : ['EQ']
          return (
            <Space key={`${filter.name}-${index}`} align="start" style={{ width: '100%' }}>
              <Select
                value={filter.name}
                style={{ width: 200 }}
                options={definitions.map((item) => ({ label: `${item.name} · ${item.type}`, value: item.name }))}
                onChange={(name) => {
                  const nextDefinition = definitionsByName.get(name)
                  setFilters((previous) => previous.map((item, itemIndex) => itemIndex === index ? {
                    name,
                    operator: defaultTagFilterOperator(nextDefinition),
                    value: '',
                  } : item))
                }}
                status={duplicateNames.has(filter.name) ? 'error' : undefined}
              />
              <Select
                value={filter.operator}
                style={{ width: 130 }}
                options={operators.map((operator) => ({ label: TAG_FILTER_OPERATOR_LABELS[operator], value: operator }))}
                onChange={(operator) => setFilters((previous) => previous.map((item, itemIndex) => (
                  itemIndex === index ? { ...item, operator, value: tagFilterNeedsValue(operator) ? item.value ?? '' : undefined } : item
                )))}
              />
              {tagFilterNeedsValue(filter.operator) ? (
                <Input
                  value={filter.value ?? ''}
                  style={{ flex: 1, minWidth: 220 }}
                  placeholder="输入筛选值"
                  onChange={(event) => setFilters((previous) => previous.map((item, itemIndex) => (
                    itemIndex === index ? { ...item, value: event.target.value } : item
                  )))}
                />
              ) : <div style={{ flex: 1, minWidth: 220 }} />}
              <Button
                type="text"
                danger
                icon={<DeleteOutlined />}
                aria-label={`删除条件 ${index + 1}`}
                onClick={() => setFilters((previous) => previous.filter((_, itemIndex) => itemIndex !== index))}
              />
            </Space>
          )
        })}
        <Button
          icon={<PlusOutlined />}
          disabled={filters.length >= MAX_FILTERS || filters.length >= definitions.length}
          onClick={addFilter}
        >
          添加条件
        </Button>
      </Space>
    </Modal>
  )
}
