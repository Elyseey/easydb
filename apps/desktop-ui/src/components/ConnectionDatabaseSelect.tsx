import React from 'react'
import { Button, Select, Space, Spin, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useConnectionDatabases } from '@/hooks/useConnectionDatabases'

const { Text } = Typography

interface ConnectionDatabaseSelectProps {
  connectionId?: string
  value?: string
  onChange: (database: string) => void
}

export const ConnectionDatabaseSelect: React.FC<ConnectionDatabaseSelectProps> = ({
  connectionId,
  value,
  onChange,
}) => {
  const { databases, loading, error, reload } = useConnectionDatabases(connectionId)

  const notFoundContent = loading ? (
    <Space size={8}>
      <Spin size="small" />
      <Text type="secondary">正在加载数据库...</Text>
    </Space>
  ) : error ? (
    <Button
      type="link"
      size="small"
      icon={<ReloadOutlined />}
      onMouseDown={(event) => event.preventDefault()}
      onClick={reload}
    >
      加载失败，点击重试
    </Button>
  ) : (
    <Text type="secondary">暂无可用数据库</Text>
  )

  return (
    <Select
      key={connectionId ?? 'no-connection'}
      aria-label="选择数据库"
      size="small"
      variant="filled"
      style={{ width: 160 }}
      placeholder="选择数据库"
      value={value}
      onChange={onChange}
      options={databases.map((database) => ({ label: database, value: database }))}
      disabled={!connectionId}
      loading={loading}
      status={error ? 'error' : undefined}
      notFoundContent={notFoundContent}
      optionFilterProp="label"
      showSearch
      virtual={false}
    />
  )
}
