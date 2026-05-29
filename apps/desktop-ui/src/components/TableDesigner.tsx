import React, { useState, useCallback, useEffect, useRef } from 'react'
import { Input, Button, Tabs, Table, Select, Checkbox, Space, Typography, Tooltip, theme, Modal } from 'antd'
import { PlusOutlined, DeleteOutlined, ArrowUpOutlined, ArrowDownOutlined, SaveOutlined } from '@ant-design/icons'
import { metadataApi } from '@/services/api'
import { handleApiError, toast } from '@/utils/notification'
import type { DbType } from '@/types'

const { Text } = Typography

// MySQL 常用类型分组
const MYSQL_TYPES = [
  { label: '数值', options: [
    { label: 'INT', value: 'INT' },
    { label: 'BIGINT', value: 'BIGINT' },
    { label: 'SMALLINT', value: 'SMALLINT' },
    { label: 'TINYINT', value: 'TINYINT' },
    { label: 'DECIMAL', value: 'DECIMAL' },
    { label: 'FLOAT', value: 'FLOAT' },
    { label: 'DOUBLE', value: 'DOUBLE' },
  ]},
  { label: '字符串', options: [
    { label: 'VARCHAR', value: 'VARCHAR' },
    { label: 'CHAR', value: 'CHAR' },
    { label: 'TEXT', value: 'TEXT' },
    { label: 'MEDIUMTEXT', value: 'MEDIUMTEXT' },
    { label: 'LONGTEXT', value: 'LONGTEXT' },
    { label: 'JSON', value: 'JSON' },
    { label: 'ENUM', value: 'ENUM' },
  ]},
  { label: '日期时间', options: [
    { label: 'DATETIME', value: 'DATETIME' },
    { label: 'TIMESTAMP', value: 'TIMESTAMP' },
    { label: 'DATE', value: 'DATE' },
    { label: 'TIME', value: 'TIME' },
  ]},
  { label: '二进制', options: [
    { label: 'BLOB', value: 'BLOB' },
    { label: 'MEDIUMBLOB', value: 'MEDIUMBLOB' },
    { label: 'LONGBLOB', value: 'LONGBLOB' },
    { label: 'BIT', value: 'BIT' },
  ]},
]

const DAMENG_TYPES = [
  { label: '数值', options: [
    { label: 'INT', value: 'INT' },
    { label: 'BIGINT', value: 'BIGINT' },
    { label: 'SMALLINT', value: 'SMALLINT' },
    { label: 'NUMBER', value: 'NUMBER' },
    { label: 'DECIMAL', value: 'DECIMAL' },
    { label: 'FLOAT', value: 'FLOAT' },
    { label: 'DOUBLE', value: 'DOUBLE' },
  ]},
  { label: '字符串', options: [
    { label: 'VARCHAR2', value: 'VARCHAR2' },
    { label: 'VARCHAR', value: 'VARCHAR' },
    { label: 'CHAR', value: 'CHAR' },
    { label: 'CLOB', value: 'CLOB' },
  ]},
  { label: '日期时间', options: [
    { label: 'TIMESTAMP', value: 'TIMESTAMP' },
    { label: 'DATE', value: 'DATE' },
    { label: 'TIME', value: 'TIME' },
  ]},
  { label: '二进制', options: [
    { label: 'BLOB', value: 'BLOB' },
    { label: 'BINARY', value: 'BINARY' },
    { label: 'VARBINARY', value: 'VARBINARY' },
  ]},
]

// 需要长度参数的类型
const TYPES_WITH_LENGTH = new Set(['VARCHAR', 'CHAR', 'DECIMAL', 'INT', 'BIGINT', 'SMALLINT', 'TINYINT', 'FLOAT', 'DOUBLE', 'BIT', 'ENUM'])
const DAMENG_TYPES_WITH_LENGTH = new Set(['VARCHAR', 'VARCHAR2', 'CHAR', 'DECIMAL', 'NUMBER', 'BINARY', 'VARBINARY'])

// 从完整类型字符串中解析出基础类型和长度
function parseType(fullType: string): { baseType: string; length: string } {
  const match = fullType.match(/^(\w+)(?:\(([^)]+)\))?/)
  if (!match) return { baseType: fullType.toUpperCase(), length: '' }
  return { baseType: match[1].toUpperCase(), length: match[2] || '' }
}

interface ColumnRow {
  key: string
  name: string
  type: string      // 基础类型如 VARCHAR
  length: string    // 长度如 255
  nullable: boolean
  defaultValue: string
  isPrimaryKey: boolean
  isAutoIncrement: boolean
  comment: string
  _original?: string // 编辑模式下原始列名，用于 CHANGE COLUMN
}

interface IndexRow {
  key: string
  name: string
  columns: string[]
  isUnique: boolean
  _original?: string // 编辑模式下原始索引名
}

interface TableDesignerProps {
  connectionId: string
  connectionName: string
  database: string
  /** 编辑模式下传入已有表名 */
  editTableName?: string
  /** Avoid full table definition loads for drivers where DDL lookup is expensive. */
  lightweightStructureLoad?: boolean
  dbType?: DbType
  onSuccess: () => void
  onCancel: () => void
}

let colCounter = 0
let idxCounter = 0

export const TableDesigner: React.FC<TableDesignerProps> = ({
  connectionId, connectionName, database, editTableName, lightweightStructureLoad = false, dbType = 'mysql', onSuccess, onCancel,
}) => {
  const { token } = theme.useToken()
  const isEditMode = !!editTableName
  const isDameng = dbType === 'dameng'
  const typeOptions = isDameng ? DAMENG_TYPES : MYSQL_TYPES
  const typesWithLength = isDameng ? DAMENG_TYPES_WITH_LENGTH : TYPES_WITH_LENGTH
  const [tableName, setTableName] = useState(editTableName || '')
  const [tableComment, setTableComment] = useState('')
  const [columns, setColumns] = useState<ColumnRow[]>(() =>
    isEditMode ? [] : [
      { key: `col_${++colCounter}`, name: 'id', type: 'BIGINT', length: isDameng ? '' : '20', nullable: false, defaultValue: '', isPrimaryKey: true, isAutoIncrement: !isDameng, comment: '主键' },
    ]
  )
  const [indexes, setIndexes] = useState<IndexRow[]>([])
  const [ddlPreview, setDdlPreview] = useState('')
  const [saving, setSaving] = useState(false)
  const [activeTab, setActiveTab] = useState('columns')
  const [loading, setLoading] = useState(false)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 编辑模式下保存原始状态用于 diff
  const originalColumnsRef = useRef<ColumnRow[]>([])
  const originalIndexesRef = useRef<IndexRow[]>([])
  const originalCommentRef = useRef('')

  // 编辑模式：加载现有表结构
  useEffect(() => {
    if (!isEditMode || !editTableName) return
    setLoading(true)
    const loadStructure = lightweightStructureLoad
      ? Promise.all([
          metadataApi.tableInfo(connectionId, database, editTableName),
          metadataApi.columns(connectionId, database, editTableName),
          metadataApi.indexes(connectionId, database, editTableName),
        ]).then(([tableInfo, columnsData, indexesData]) => ({
          table: tableInfo,
          columns: columnsData,
          indexes: indexesData,
        }))
      : metadataApi.tableDefinition(connectionId, database, editTableName)

    loadStructure
      .then((def: unknown) => {
        const d = def as { table: { name?: string; comment?: string }; columns: Array<{ name: string; type: string; nullable: boolean; defaultValue?: string; isPrimaryKey: boolean; isAutoIncrement: boolean; comment?: string }>; indexes: Array<{ name: string; columns: string[]; isUnique: boolean; isPrimary: boolean }> }
        setTableComment(d.table.comment || '')
        originalCommentRef.current = d.table.comment || ''

        const cols: ColumnRow[] = d.columns.map(c => {
          const { baseType, length } = parseType(c.type)
          return {
            key: `col_${++colCounter}`,
            name: c.name,
            type: baseType,
            length,
            nullable: c.nullable,
            defaultValue: c.defaultValue || '',
            isPrimaryKey: c.isPrimaryKey,
            isAutoIncrement: c.isAutoIncrement,
            comment: c.comment || '',
            _original: c.name,
          }
        })
        setColumns(cols)
        originalColumnsRef.current = cols.map(c => ({ ...c }))

        // 过滤 PRIMARY KEY 索引（主键由字段 isPrimaryKey 管理）
        const idxs: IndexRow[] = d.indexes
          .filter(i => !i.isPrimary)
          .map(i => ({
            key: `idx_${++idxCounter}`,
            name: i.name,
            columns: i.columns,
            isUnique: i.isUnique,
            _original: i.name,
          }))
        setIndexes(idxs)
        originalIndexesRef.current = idxs.map(i => ({ ...i, columns: [...i.columns] }))
      })
      .catch(e => handleApiError(e, '加载表结构失败'))
      .finally(() => setLoading(false))
  }, [isEditMode, editTableName, connectionId, database, lightweightStructureLoad])

  // 列操作
  const updateColumn = useCallback((key: string, field: keyof ColumnRow, value: unknown) => {
    setColumns(prev => prev.map(c => c.key === key ? { ...c, [field]: value } : c))
  }, [])

  const addColumn = useCallback(() => {
    setColumns(prev => [...prev, {
      key: `col_${++colCounter}`, name: '', type: isDameng ? 'VARCHAR2' : 'VARCHAR', length: '255',
      nullable: true, defaultValue: '', isPrimaryKey: false, isAutoIncrement: false, comment: '',
    }])
  }, [isDameng])

  const removeColumn = useCallback((key: string) => {
    setColumns(prev => prev.filter(c => c.key !== key))
  }, [])

  const moveColumn = useCallback((key: string, direction: 'up' | 'down') => {
    setColumns(prev => {
      const idx = prev.findIndex(c => c.key === key)
      if ((direction === 'up' && idx <= 0) || (direction === 'down' && idx >= prev.length - 1)) return prev
      const newCols = [...prev]
      const target = direction === 'up' ? idx - 1 : idx + 1
      ;[newCols[idx], newCols[target]] = [newCols[target], newCols[idx]]
      return newCols
    })
  }, [])

  // 索引操作
  const addIndex = useCallback(() => {
    setIndexes(prev => [...prev, {
      key: `idx_${++idxCounter}`, name: '', columns: [], isUnique: false,
    }])
  }, [])

  const updateIndex = useCallback((key: string, field: keyof IndexRow, value: unknown) => {
    setIndexes(prev => prev.map(i => i.key === key ? { ...i, [field]: value } : i))
  }, [])

  const removeIndex = useCallback((key: string) => {
    setIndexes(prev => prev.filter(i => i.key !== key))
  }, [])

  const escapeSqlString = (value: string) => value.replace(/'/g, "''")

  // 构建类型全名
  const fullType = (col: ColumnRow) => {
    if (typesWithLength.has(col.type) && col.length) return `${col.type}(${col.length})`
    return col.type
  }

  // 引用标识符
  const qi = (name: string) => (
    isDameng
      ? `"${name.replace(/"/g, '""')}"`
      : `\`${name.replace(/`/g, '``')}\``
  )

  const normalizeTypeForDialect = (col: ColumnRow) => {
    if (!isDameng) return fullType(col)

    const type = col.type.toUpperCase()
    if (type === 'VARCHAR') return col.length ? `VARCHAR2(${col.length})` : 'VARCHAR2(255)'
    if (type === 'VARCHAR2') return col.length ? `VARCHAR2(${col.length})` : 'VARCHAR2(255)'
    if (['INT', 'INTEGER', 'BIGINT', 'SMALLINT'].includes(type)) return type
    if (type === 'DECIMAL' || type === 'NUMBER') return col.length ? `NUMBER(${col.length})` : 'NUMBER'
    return fullType(col)
  }

  const defaultClause = (col: ColumnRow) => {
    if (!col.defaultValue || col.isAutoIncrement) return ''
    const dv = col.defaultValue.trim()
    const upper = dv.toUpperCase()
    if (['CURRENT_TIMESTAMP', 'NULL', 'NOW()', 'SYSDATE'].includes(upper) || dv.startsWith("'")) {
      return ` DEFAULT ${isDameng && upper === 'NOW()' ? 'CURRENT_TIMESTAMP' : dv}`
    }
    return ` DEFAULT '${escapeSqlString(dv)}'`
  }

  // 构建列定义子句
  const colDef = (col: ColumnRow) => {
    let def = `${qi(col.name)} ${normalizeTypeForDialect(col)}`
    if (!col.nullable) def += ' NOT NULL'
    def += defaultClause(col)
    if (col.isAutoIncrement) def += isDameng ? ' IDENTITY(1,1)' : ' AUTO_INCREMENT'
    if (!isDameng && col.comment) def += ` COMMENT '${escapeSqlString(col.comment)}'`
    return def
  }

  const damengColumnTarget = (columnName: string) => `${qi(tableName)}.${qi(columnName)}`

  // 生成 ALTER TABLE SQL（编辑模式）
  const generateAlterSql = useCallback(() => {
    const stmts: string[] = []
    const t = qi(tableName)

    // 1. 删除的列
    for (const orig of originalColumnsRef.current) {
      if (!columns.find(c => c._original === orig._original)) {
        stmts.push(`ALTER TABLE ${t} DROP COLUMN ${qi(orig.name)};`)
      }
    }

    // 2. 新增的列
    for (const col of columns) {
      if (!col._original) {
        stmts.push(`ALTER TABLE ${t} ADD COLUMN ${colDef(col)};`)
      }
    }

    // 3. 修改的列
    for (const col of columns) {
      if (!col._original) continue
      const orig = originalColumnsRef.current.find(o => o._original === col._original)
      if (!orig) continue
      const typeChanged = normalizeTypeForDialect(col) !== normalizeTypeForDialect(orig) ||
        col.nullable !== orig.nullable || col.defaultValue !== orig.defaultValue ||
        col.isAutoIncrement !== orig.isAutoIncrement
      const nameChanged = col.name !== orig.name
      const commentChanged = col.comment !== orig.comment
      if (!isDameng && (nameChanged || typeChanged || commentChanged)) {
        stmts.push(`ALTER TABLE ${t} CHANGE COLUMN ${qi(orig.name)} ${colDef(col)};`)
      } else if (isDameng) {
        if (nameChanged) {
          stmts.push(`ALTER TABLE ${t} RENAME COLUMN ${qi(orig.name)} TO ${qi(col.name)};`)
        }
        if (typeChanged) {
          stmts.push(`ALTER TABLE ${t} MODIFY ${colDef(col)};`)
        }
        if (commentChanged) {
          stmts.push(`COMMENT ON COLUMN ${damengColumnTarget(col.name)} IS '${escapeSqlString(col.comment)}';`)
        }
      }
    }

    // 4. 主键变更
    const origPkCols = originalColumnsRef.current.filter(c => c.isPrimaryKey).map(c => c.name)
    const newPkCols = columns.filter(c => c.isPrimaryKey).map(c => c.name)
    if (JSON.stringify(origPkCols) !== JSON.stringify(newPkCols)) {
      if (origPkCols.length > 0) stmts.push(`ALTER TABLE ${t} DROP PRIMARY KEY;`)
      if (newPkCols.length > 0) stmts.push(`ALTER TABLE ${t} ADD PRIMARY KEY (${newPkCols.map(qi).join(', ')});`)
    }

    // 5. 删除的索引
    for (const orig of originalIndexesRef.current) {
      if (!indexes.find(i => i._original === orig._original)) {
        stmts.push(isDameng ? `DROP INDEX ${qi(orig.name)};` : `ALTER TABLE ${t} DROP INDEX ${qi(orig.name)};`)
      }
    }

    // 6. 新增的索引
    for (const idx of indexes) {
      if (!idx._original && idx.name.trim() && idx.columns.length > 0) {
        const unique = idx.isUnique ? 'UNIQUE ' : ''
        stmts.push(isDameng
          ? `CREATE ${unique}INDEX ${qi(idx.name)} ON ${t} (${idx.columns.map(qi).join(', ')});`
          : `ALTER TABLE ${t} ADD ${unique}INDEX ${qi(idx.name)} (${idx.columns.map(qi).join(', ')});`
        )
      }
    }

    // 7. 修改的索引（drop + add）
    for (const idx of indexes) {
      if (!idx._original) continue
      const orig = originalIndexesRef.current.find(o => o._original === idx._original)
      if (!orig) continue
      if (idx.name !== orig.name || idx.isUnique !== orig.isUnique ||
          JSON.stringify(idx.columns) !== JSON.stringify(orig.columns)) {
        stmts.push(isDameng ? `DROP INDEX ${qi(orig.name)};` : `ALTER TABLE ${t} DROP INDEX ${qi(orig.name)};`)
        if (idx.name.trim() && idx.columns.length > 0) {
          const unique = idx.isUnique ? 'UNIQUE ' : ''
          stmts.push(isDameng
            ? `CREATE ${unique}INDEX ${qi(idx.name)} ON ${t} (${idx.columns.map(qi).join(', ')});`
            : `ALTER TABLE ${t} ADD ${unique}INDEX ${qi(idx.name)} (${idx.columns.map(qi).join(', ')});`
          )
        }
      }
    }

    // 8. 表注释变更
    if (tableComment !== originalCommentRef.current) {
      stmts.push(isDameng
        ? `COMMENT ON TABLE ${t} IS '${escapeSqlString(tableComment)}';`
        : `ALTER TABLE ${t} COMMENT = '${escapeSqlString(tableComment)}';`
      )
    }

    return stmts.join('\n')
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tableName, tableComment, columns, indexes])

  // 构建请求体（新建模式）
  const buildTableDef = useCallback(() => ({
    tableName,
    comment: tableComment,
    columns: columns.filter(c => c.name.trim()).map(c => ({
      name: c.name, type: c.type, length: c.length, nullable: c.nullable,
      defaultValue: c.defaultValue || null, isPrimaryKey: c.isPrimaryKey,
      isAutoIncrement: c.isAutoIncrement, comment: c.comment || null,
    })),
    indexes: indexes.filter(i => i.name.trim() && i.columns.length > 0).map(i => ({
      name: i.name, columns: i.columns, isUnique: i.isUnique, isPrimary: false,
    })),
  }), [tableName, tableComment, columns, indexes])

  // DDL 预览
  useEffect(() => {
    if (activeTab !== 'ddl') return
    if (isEditMode) {
      const sql = generateAlterSql()
      setDdlPreview(sql || '-- 无变更')
      return
    }
    if (!tableName.trim()) { setDdlPreview('-- 请先输入表名'); return }
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(async () => {
      try {
        const res = await metadataApi.previewCreateTable(connectionId, database, buildTableDef()) as { ddl: string }
        setDdlPreview(res.ddl)
      } catch { setDdlPreview('-- 生成预览失败') }
    }, 300)
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current) }
  }, [activeTab, tableName, tableComment, columns, indexes, connectionId, database, buildTableDef, isEditMode, generateAlterSql])

  // 保存
  const handleSave = async () => {
    if (!tableName.trim()) { toast.error('请输入表名'); return }
    if (columns.filter(c => c.name.trim()).length === 0) { toast.error('请至少添加一个字段'); return }
    setSaving(true)
    try {
      if (isEditMode) {
        const sql = generateAlterSql()
        if (!sql.trim()) { toast.warning('无变更'); setSaving(false); return }
        // 通过 SQL 执行接口执行 ALTER TABLE
        const { sqlApi } = await import('@/services/api')
        const statements = sql.split('\n').map(stmt => stmt.trim()).filter(Boolean)
        for (const statement of statements) {
          await sqlApi.execute(connectionId, database, statement)
        }
        toast.success(`表「${tableName}」已更新`)
        onSuccess()
      } else {
        const res = await metadataApi.createTable(connectionId, database, buildTableDef()) as { success: boolean }
        if (res.success) {
          toast.success(`表「${tableName}」创建成功`)
          onSuccess()
        }
      }
    } catch (e) {
      handleApiError(e, isEditMode ? '修改表失败' : '创建表失败')
    } finally {
      setSaving(false)
    }
  }

  const availableColumnNames = columns.filter(c => c.name.trim()).map(c => c.name)

  // 字段表列定义
  const columnTableCols = [
    {
      title: '字段名', dataIndex: 'name', key: 'name', width: 150,
      render: (_: string, record: ColumnRow) => (
        <Input size="small" value={record.name} placeholder="field_name"
          onChange={e => updateColumn(record.key, 'name', e.target.value)}
          status={!record.name.trim() ? 'warning' : undefined} />
      ),
    },
    {
      title: '类型', dataIndex: 'type', key: 'type', width: 130,
      render: (_: string, record: ColumnRow) => (
        <Select size="small" value={record.type} showSearch style={{ width: '100%' }} options={typeOptions}
          onChange={(val: string) => {
            updateColumn(record.key, 'type', val)
            if ((val === 'VARCHAR' || val === 'VARCHAR2') && !record.length) updateColumn(record.key, 'length', '255')
            if (!isDameng && val === 'INT' && !record.length) updateColumn(record.key, 'length', '11')
            if (!isDameng && val === 'BIGINT' && !record.length) updateColumn(record.key, 'length', '20')
            if (!DAMENG_TYPES_WITH_LENGTH.has(val) && isDameng) updateColumn(record.key, 'length', '')
          }}
        />
      ),
    },
    {
      title: '长度', dataIndex: 'length', key: 'length', width: 80,
      render: (_: string, record: ColumnRow) => (
        typesWithLength.has(record.type) ? (
          <Input size="small" value={record.length} onChange={e => updateColumn(record.key, 'length', e.target.value)} />
        ) : <Text type="secondary">—</Text>
      ),
    },
    {
      title: '非空', dataIndex: 'nullable', key: 'nullable', width: 50, align: 'center' as const,
      render: (_: boolean, record: ColumnRow) => (
        <Checkbox checked={!record.nullable} onChange={e => updateColumn(record.key, 'nullable', !e.target.checked)} />
      ),
    },
    {
      title: '主键', dataIndex: 'isPrimaryKey', key: 'isPrimaryKey', width: 50, align: 'center' as const,
      render: (_: boolean, record: ColumnRow) => (
        <Checkbox checked={record.isPrimaryKey} onChange={e => {
          updateColumn(record.key, 'isPrimaryKey', e.target.checked)
          if (e.target.checked) updateColumn(record.key, 'nullable', false)
        }} />
      ),
    },
    {
      title: '自增', dataIndex: 'isAutoIncrement', key: 'isAutoIncrement', width: 50, align: 'center' as const,
      render: (_: boolean, record: ColumnRow) => (
        <Checkbox disabled={isDameng} checked={!isDameng && record.isAutoIncrement} onChange={e => updateColumn(record.key, 'isAutoIncrement', e.target.checked)} />
      ),
    },
    {
      title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 120,
      render: (_: string, record: ColumnRow) => (
        <Input size="small" value={record.defaultValue} placeholder="NULL"
          onChange={e => updateColumn(record.key, 'defaultValue', e.target.value)} />
      ),
    },
    {
      title: '备注', dataIndex: 'comment', key: 'comment',
      render: (_: string, record: ColumnRow) => (
        <Input size="small" value={record.comment} onChange={e => updateColumn(record.key, 'comment', e.target.value)} />
      ),
    },
    {
      title: '', key: 'actions', width: 80, align: 'center' as const,
      render: (_: unknown, record: ColumnRow) => (
        <Space size={2}>
          <Tooltip title="上移"><Button type="text" size="small" icon={<ArrowUpOutlined />} onClick={() => moveColumn(record.key, 'up')} /></Tooltip>
          <Tooltip title="下移"><Button type="text" size="small" icon={<ArrowDownOutlined />} onClick={() => moveColumn(record.key, 'down')} /></Tooltip>
          <Tooltip title="删除">
            <Button type="text" size="small" danger icon={<DeleteOutlined />}
              onClick={() => {
                if (isEditMode && record._original) {
                  Modal.confirm({
                    title: `确认删除字段「${record.name}」？`,
                    content: '删除字段将丢失该列所有数据，保存后生效。',
                    okText: '删除', okType: 'danger', cancelText: '取消',
                    onOk: () => removeColumn(record.key),
                  })
                } else {
                  removeColumn(record.key)
                }
              }}
            />
          </Tooltip>
        </Space>
      ),
    },
  ]

  // 索引表列定义
  const indexTableCols = [
    {
      title: '索引名', dataIndex: 'name', key: 'name', width: 200,
      render: (_: string, record: IndexRow) => (
        <Input size="small" value={record.name} placeholder="idx_xxx"
          onChange={e => updateIndex(record.key, 'name', e.target.value)} />
      ),
    },
    {
      title: '唯一', dataIndex: 'isUnique', key: 'isUnique', width: 60, align: 'center' as const,
      render: (_: boolean, record: IndexRow) => (
        <Checkbox checked={record.isUnique} onChange={e => updateIndex(record.key, 'isUnique', e.target.checked)} />
      ),
    },
    {
      title: '包含字段', dataIndex: 'columns', key: 'columns',
      render: (_: string[], record: IndexRow) => (
        <Select size="small" mode="multiple" value={record.columns} style={{ width: '100%' }}
          options={availableColumnNames.map(n => ({ label: n, value: n }))}
          onChange={(val: string[]) => updateIndex(record.key, 'columns', val)} placeholder="选择字段..." />
      ),
    },
    {
      title: '', key: 'actions', width: 50, align: 'center' as const,
      render: (_: unknown, record: IndexRow) => (
        <Tooltip title="删除"><Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={() => removeIndex(record.key)} /></Tooltip>
      ),
    },
  ]

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <Text type="secondary">加载表结构中...</Text>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden', padding: 16 }}>
      {/* 顶部 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, flexShrink: 0 }}>
        <Text type="secondary" style={{ fontSize: 12, flexShrink: 0 }}>
          {connectionName} / {database}
        </Text>
        <Input
          placeholder="表名" value={tableName} style={{ width: 200 }}
          onChange={e => setTableName(e.target.value)}
          status={!tableName.trim() ? 'warning' : undefined}
          disabled={isEditMode}
        />
        <Input
          placeholder="表备注（可选）" value={tableComment} style={{ width: 200 }}
          onChange={e => setTableComment(e.target.value)}
        />
        <div style={{ flex: 1 }} />
        <Button onClick={onCancel}>取消</Button>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
          {isEditMode ? '保存修改' : '创建表'}
        </Button>
      </div>

      {/* Tabs */}
      <Tabs
        size="small" activeKey={activeTab} onChange={setActiveTab}
        style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        tabBarStyle={{ flexShrink: 0, marginBottom: 0 }}
        items={[
          {
            key: 'columns',
            label: `字段 (${columns.length})`,
            children: (
              <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
                <div style={{ marginBottom: 8, flexShrink: 0 }}>
                  <Button size="small" icon={<PlusOutlined />} onClick={addColumn}>添加字段</Button>
                </div>
                <div style={{ flex: 1, overflow: 'auto' }}>
                  <Table dataSource={columns} columns={columnTableCols} rowKey="key" size="small" pagination={false}
                    scroll={{ y: 'calc(100vh - 280px)' }} />
                </div>
              </div>
            ),
          },
          {
            key: 'indexes',
            label: `索引 (${indexes.length})`,
            children: (
              <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
                <div style={{ marginBottom: 8, flexShrink: 0 }}>
                  <Button size="small" icon={<PlusOutlined />} onClick={addIndex}>添加索引</Button>
                  <Text type="secondary" style={{ marginLeft: 12, fontSize: 12 }}>提示：主键字段已自动生成 PRIMARY KEY，无需手动添加</Text>
                </div>
                <div style={{ flex: 1, overflow: 'auto' }}>
                  <Table dataSource={indexes} columns={indexTableCols} rowKey="key" size="small" pagination={false} />
                </div>
              </div>
            ),
          },
          {
            key: 'ddl',
            label: isEditMode ? 'ALTER SQL 预览' : 'DDL 预览',
            children: (
              <pre style={{
                background: '#1e1e1e', color: '#d4d4d4', padding: 16,
                borderRadius: token.borderRadius, fontSize: 12,
                fontFamily: 'Menlo, Monaco, monospace',
                overflow: 'auto', height: '100%', margin: 0,
              }}>
                {ddlPreview || (isEditMode ? '-- 切换到此标签页查看 ALTER SQL' : '-- 切换到此标签页时自动生成 DDL')}
              </pre>
            ),
          },
        ]}
      />
    </div>
  )
}
