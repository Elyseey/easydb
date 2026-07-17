/*
 * Copyright (c) 2024-2026 EasyDB Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
import React, { useEffect, useState, useCallback, useRef, useDeferredValue, useMemo, useLayoutEffect, type CSSProperties } from 'react'
import {
  Layout, Tree, Tabs, Table, Typography, Input, Space, Button, Tag, Tooltip, Modal, Dropdown,
  theme, Breadcrumb,
} from 'antd'
import type { InputRef } from 'antd'
import {
  DatabaseOutlined, ReloadOutlined, 
  CodeOutlined, StarOutlined,
  DeleteOutlined, EditOutlined,
  CloseOutlined, PlusOutlined, UploadOutlined, DownloadOutlined, ExportOutlined
} from '@ant-design/icons'
import type { DataNode } from 'antd/es/tree'
import {
  FileText, Table2, Activity, Zap, Eye, KeyRound, Search, Plus, Database, Cog, FunctionSquare,
} from 'lucide-react'
import type {
  TableInfo, ColumnInfo, ConnectionConfig, SavedScript, DatabaseInfo, TableKind,
  TimeSeriesChildTablePage, TimeSeriesTagDefinition, TimeSeriesTagValue,
} from '@/types'
import { useWorkbenchStore, type TableDataQuery, type TableTabState, type WorkbenchTab } from '@/stores/workbenchStore'
import { useConnectionStore } from '@/stores/connectionStore'
import { useSqlEditorStore } from '@/stores/sqlEditorStore'
import { useCommandStore } from '@/stores/commandStore'
import { metadataApi, connectionApi, scriptApi, setReconnectCallback } from '@/services/api'
import { handleApiError, toast } from '@/utils/notification'
import { confirmDataExport } from '@/components/confirmDataExport'
import { EmptyState } from '@/components/EmptyState'
import { EditableDataTable } from '@/components/EditableDataTable'
import { CreateDatabaseModal } from '@/components/CreateDatabaseModal'
import { EditDatabaseModal } from '@/components/EditDatabaseModal'
import { ImportSqlDialog } from '@/components/ImportSqlDialog'
import ExportDatabaseModal from '@/components/ExportDatabaseModal'
import BackupDatabaseModal from '@/components/BackupDatabaseModal'
import RestoreDatabaseModal from '@/components/RestoreDatabaseModal'
import { TableDesigner, type TableDesignerSaveResult } from '@/components/TableDesigner'
import { DdlViewer } from '@/components/DdlViewer'
import { QueryEditorPane } from '@/components/QueryEditorPane'
import { ShortcutsModal } from '@/components/ShortcutsModal'
import { CallProcedurePanel, type CallProcedureTarget } from '@/components/CallProcedurePanel'
import { formatHotkey } from '@/utils/osUtils'
import { getDbCapabilities } from '@/utils/dbCapabilities'
import { databaseNamespaceLabel } from '@/utils/databaseNamespace'
import type { OpenObjectDetailRequest } from '@/utils/openObjectDetail'
import {
  MAX_QUERY_TAB_TITLE_LENGTH,
  normalizeQueryTabTitle,
  resolveWorkbenchTabKey,
} from './queryTabTitle'
import { tableDetailLoadPlan, type TableDetailLoadTarget } from './tableDetailLoadPlan'

const { Sider, Content } = Layout
const { Text } = Typography
type TreeDataNode = DataNode & { 'data-node-key'?: string }
type WorkbenchPaneRenderState = {
  keepAliveKeys: string[]
  dirtyKeys: string[]
}
type ChildTableTreeState = TimeSeriesChildTablePage & {
  loading: boolean
  search: string
}

/** 每次 previewRows 加载的行数（与后端 limit 对齐） */
const PREVIEW_PAGE_SIZE = 1000
const WORKBENCH_KEEP_ALIVE_LIMIT = 8
const addUnique = (items: string[], item: string) => items.includes(item) ? items : [...items, item]
const removeItems = (items: string[], removing: string[]) => items.filter((item) => !removing.includes(item))

/** 分类列表视图 — 独立组件，搜索状态通过 props 持久化 */
import type { MenuProps } from 'antd'

const CategoryListView: React.FC<{
  connectionId: string
  database: string
  category: string
  objects: TableInfo[]
  objectCategories: { key: string; label: string; types: string[]; icon: React.ReactNode }[]
  onSelectObject: (name: string) => void
  search: string
  onSearchChange: (value: string) => void
}> = ({ database, category, objects, objectCategories, onSelectObject, search, onSearchChange }) => {
  const { token } = theme.useToken()
  const deferredSearch = useDeferredValue(search)
  const compactPanelStyle = useMemo<CSSProperties>(() => ({
    background: 'var(--glass-panel)',
    backdropFilter: 'var(--glass-blur-sm)',
    border: '1px solid var(--glass-border)',
    borderRadius: token.borderRadiusLG,
    boxShadow: 'var(--glass-shadow), var(--glass-inner-glow)',
  }), [token.borderRadiusLG])
  const summaryCardStyle = useMemo<CSSProperties>(() => ({
    ...compactPanelStyle,
    padding: '16px 18px',
    display: 'flex',
    flexDirection: 'column',
    gap: 6,
    minHeight: 112,
  }), [compactPanelStyle])

  const catDef = objectCategories.find((c) => c.key === category)
  const categoryObjects = objects.filter((t) => catDef?.types.includes(t.type))
  const filtered = deferredSearch
    ? categoryObjects.filter(
        (t) =>
          t.name.toLowerCase().includes(deferredSearch.toLowerCase()) ||
          (t.comment && t.comment.toLowerCase().includes(deferredSearch.toLowerCase()))
      )
    : categoryObjects

  const isTables = category === 'tables'

  const formatBytes = (bytes: number) => {
    if (!bytes || bytes === 0) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
  }

  const maxDisk = isTables ? Math.max(...categoryObjects.map(t => (t.dataLength || 0) + (t.indexLength || 0)), 1) : 1
  const totalDisk = isTables ? categoryObjects.reduce((acc, t) => acc + (t.dataLength || 0) + (t.indexLength || 0), 0) : 0
  const topTable = isTables ? [...categoryObjects].sort((a, b) => ((b.dataLength || 0) + (b.indexLength || 0)) - ((a.dataLength || 0) + (a.indexLength || 0)))[0] : null
  const nonInnodbCount = isTables ? categoryObjects.filter(t => t.engine && t.engine.toUpperCase() !== 'INNODB').length : 0

  const catColumns =
    category === 'tables'
      ? [
          { title: '表名', dataIndex: 'name', key: 'name', ellipsis: true,
            render: (name: string) => <Text copyable={{ text: name }} strong style={{ fontSize: 13, color: token.colorPrimary }}>{name}</Text>,
          },
          { title: '注释', dataIndex: 'comment', key: 'comment', ellipsis: true,
            render: (v: string) => v ? <Text style={{ fontSize: 13 }}>{v}</Text> : <Text type="secondary" style={{ fontStyle: 'italic', fontSize: 12 }}>—</Text>,
          },
          { title: '物理空间占有 (Data+Index)', key: 'diskUsage', width: 220,
            render: (_: unknown, t: TableInfo) => {
              const totalBytes = (t.dataLength || 0) + (t.indexLength || 0)
              if (totalBytes === 0) return <Text type="secondary">—</Text>
              const pct = Math.min(100, Math.max(0.5, (totalBytes / maxDisk) * 100))
              return (
                <div style={{ position: 'relative', width: '100%', height: 22, display: 'flex', alignItems: 'center' }}>
                  <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${pct}%`, background: token.colorPrimaryBg, borderRadius: 4, zIndex: 0 }} />
                  <span style={{ position: 'relative', zIndex: 1, paddingLeft: 6, fontSize: 12, fontFamily: 'monospace' }}>{formatBytes(totalBytes)}</span>
                </div>
              )
            },
          },
          { title: '引擎', dataIndex: 'engine', key: 'engine', width: 100,
            render: (v: string) => v ? <Tag color={v.toUpperCase() === 'INNODB' ? 'blue' : 'warning'}>{v.toUpperCase()}</Tag> : <Text type="secondary">—</Text>,
          },
          { title: '最后写入时间', dataIndex: 'updateTime', key: 'updateTime', width: 180,
            render: (v: string) => v ? <Text style={{ fontSize: 12 }}>{v}</Text> : <Text type="secondary" style={{ fontStyle: 'italic', fontSize: 12 }}>空/未记录</Text>,
          },
        ]
      : category === 'views'
        ? [
            { title: '视图名', dataIndex: 'name', key: 'name', ellipsis: true,
              render: (name: string) => <Text copyable={{ text: name }} style={{ fontSize: 13 }}>{name}</Text>,
            },
            { title: '注释', dataIndex: 'comment', key: 'comment', ellipsis: true,
              render: (v: string) => v || <Text type="secondary">—</Text>,
            },
          ]
        : [
            { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true,
              render: (name: string) => <Text style={{ fontSize: 13 }}>{name}</Text>,
            },
            { title: '类型', dataIndex: 'type', key: 'type', width: 100,
              render: (v: string) => <Tag>{v.toUpperCase()}</Tag>,
            },
          ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden', padding: '20px 24px', background: 'transparent' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 16, gap: 16 }}>
        <div>
          <Text strong style={{ fontSize: 18, display: 'flex', alignItems: 'center', gap: 8 }}>
            {catDef?.icon}
            <span>{catDef?.label}</span>
          </Text>
          <Space size={8} style={{ marginTop: 6 }} wrap>
            <Text type="secondary" style={{ fontSize: 12 }}>{database}</Text>
            <Tag variant="filled" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
              {categoryObjects.length} 个对象
            </Tag>
          </Space>
        </div>
        <Input
          placeholder="筛选对象名称或注释"
          prefix={<Search size={14} color={token.colorTextQuaternary} style={{ marginRight: 4 }}/>}
          size="middle"
          style={{ width: 300, borderRadius: 10 }}
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
          allowClear
        />
      </div>

      {isTables && categoryObjects.length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 12, marginBottom: 16, flexShrink: 0 }}>
          <div style={summaryCardStyle}>
            <Text type="secondary" style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
              <Zap size={14} color={token.colorPrimary} />
              最大存储占用表
            </Text>
            <Text strong style={{ fontSize: 16 }}>{topTable?.name ?? '—'}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {topTable ? formatBytes((topTable.dataLength || 0) + (topTable.indexLength || 0)) : '暂无统计'}
            </Text>
          </div>
          <div style={summaryCardStyle}>
            <Text type="secondary" style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
              <Database size={14} />
              表空间合计
            </Text>
            <Text strong style={{ fontSize: 22, fontFamily: 'var(--font-family-code)', lineHeight: 1.15 }}>{formatBytes(totalDisk)}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>数据与索引占用总和</Text>
          </div>
          <div style={summaryCardStyle}>
            <Text type="secondary" style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
              <Eye size={14} color={nonInnodbCount > 0 ? token.colorWarning : token.colorSuccess} />
              引擎一致性
            </Text>
            <Text strong style={{ fontSize: 22, lineHeight: 1.15, color: nonInnodbCount > 0 ? token.colorWarning : token.colorSuccess }}>
              {nonInnodbCount}
            </Text>
            <Text type="secondary" style={{ fontSize: 12 }}>非 InnoDB 表数量</Text>
          </div>
        </div>
      )}

      <div style={{ ...compactPanelStyle, flex: 1, overflow: 'hidden' }}>
        <Table
          dataSource={filtered}
          columns={catColumns}
          rowKey="name"
          size="small"
          pagination={{ defaultPageSize: 1000, hideOnSinglePage: true, showSizeChanger: true, pageSizeOptions: ['100', '500', '1000', '2000'], showTotal: (t) => `共 ${t} 项` }}
          scroll={{ y: 'calc(100vh - 300px)' }}
          onRow={(record) => ({
            onClick: () => onSelectObject(record.name),
            style: { cursor: 'pointer' },
          })}
        />
      </div>
    </div>
  )
}

export const WorkbenchPage: React.FC = () => {
  const { token } = theme.useToken()
  const panelStyle = useMemo<CSSProperties>(() => ({
    background: 'var(--glass-panel)',
    backdropFilter: 'var(--glass-blur-sm)',
    border: '1px solid var(--glass-border)',
    borderRadius: token.borderRadiusLG,
    boxShadow: 'var(--glass-shadow), var(--glass-inner-glow)',
  }), [token.borderRadiusLG])
  const compactPanelStyle = useMemo<CSSProperties>(() => ({
    ...panelStyle,
    borderRadius: token.borderRadius,
    boxShadow: '0 8px 24px rgba(15, 23, 42, 0.08)',
  }), [panelStyle, token.borderRadius])
  const quietButtonStyle = useMemo<CSSProperties>(() => ({
    background: 'var(--glass-panel)',
    borderColor: 'var(--glass-border)',
    color: token.colorText,
    boxShadow: 'none',
  }), [token.colorText])

  // --- Store（持久化状态，路由切换不丢失）---
  const openConnections = useWorkbenchStore((s) => s.openConnections)
  const openConnectionDbTypes = useMemo(
    () => new Map(openConnections.map((connection) => [connection.id, connection.dbType])),
    [openConnections]
  )
  const addOpenConnection = useWorkbenchStore((s) => s.addOpenConnection)
  const removeOpenConnection = useWorkbenchStore((s) => s.removeOpenConnection)

  const databasesMap = useWorkbenchStore((s) => s.databasesMap)
  const setDatabasesMap = useWorkbenchStore((s) => s.setDatabasesMap)
  const objectsMap = useWorkbenchStore((s) => s.objectsMap)
  const setObjectsMap = useWorkbenchStore((s) => s.setObjectsMap)
  const expandedKeys = useWorkbenchStore((s) => s.treeExpandedKeys)
  const setExpandedKeys = useWorkbenchStore((s) => s.setTreeExpandedKeys)
  const selectedCtx = useWorkbenchStore((s) => s.selectedCtx)
  const setSelectedCtx = useWorkbenchStore((s) => s.setSelectedCtx)
  const activeConnectionId = useWorkbenchStore((s) => s.activeConnectionId)
  const activeConnectionName = useWorkbenchStore((s) => s.activeConnectionName)
  const activeDatabase = useWorkbenchStore((s) => s.activeDatabase)

  const connections = useConnectionStore((s) => s.connections)
  const setConnections = useConnectionStore((s) => s.setConnections)
  const updateConnection = useConnectionStore((s) => s.updateConnection)

  // --- Local state（页面级临时状态）---
  const [loadingConns, setLoadingConns] = useState<Set<string>>(new Set())
  const [searchText, setSearchText] = useState('')
  const deferredSearch = useDeferredValue(searchText)
  const [childTablesMap, setChildTablesMap] = useState<Record<string, ChildTableTreeState>>({})
  const childTableLoadSeqRef = useRef<Record<string, number>>({})

  // --- 树组件虚拟列表高度 ---
  const [treeHeight, setTreeHeight] = useState(600)
  const treeContainerRef = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    if (!treeContainerRef.current) return
    const ro = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setTreeHeight(entry.contentRect.height)
      }
    })
    ro.observe(treeContainerRef.current)
    return () => ro.disconnect()
  }, [])

  // --- 多 Tab 状态（持久化到 Store，路由切换不丢失）---
  const openTableTabs = useWorkbenchStore((s) => s.openTableTabs)
  const setOpenTableTabs = useWorkbenchStore((s) => s.setOpenTableTabs)
  const activeTableTabKey = useWorkbenchStore((s) => s.activeTableTabKey)
  const setActiveTableTabKey = useWorkbenchStore((s) => s.setActiveTableTabKey)
  const batchUpdate = useWorkbenchStore((s) => s.batchUpdate)
  const [paneState, setPaneState] = useState<WorkbenchPaneRenderState>({
    keepAliveKeys: [],
    dirtyKeys: [],
  })
  const mountedPaneKeys = useMemo(() => {
    const openKeys = new Set(Object.keys(openTableTabs))
    const keys = paneState.keepAliveKeys.filter((key) => openKeys.has(key))
    if (activeTableTabKey && openKeys.has(activeTableTabKey) && !keys.includes(activeTableTabKey)) {
      return [...keys, activeTableTabKey]
    }
    return keys
  }, [activeTableTabKey, openTableTabs, paneState.keepAliveKeys])

  const pruneKeepAliveKeys = useCallback((
    nextKeys: string[],
    nextDirtyKeys: string[],
    openKeys: Set<string>,
    activeKey: string | null,
  ) => {
    const dirtySet = new Set(nextDirtyKeys)
    const normalized = nextKeys.filter((key, index) => openKeys.has(key) && nextKeys.indexOf(key) === index)
    const active = activeKey && openKeys.has(activeKey) ? activeKey : null
    const pinned = normalized.filter((key) => key === active || dirtySet.has(key))
    const recent = normalized.filter((key) => !pinned.includes(key))
    const room = Math.max(0, WORKBENCH_KEEP_ALIVE_LIMIT - pinned.length)
    return [...pinned, ...recent.slice(-room)]
  }, [])

  const markPaneVisited = useCallback((key: string | null) => {
    if (!key) return
    setPaneState((prev) => {
      const openKeys = new Set(Object.keys(useWorkbenchStore.getState().openTableTabs))
      if (!openKeys.has(key)) return prev
      const withoutKey = prev.keepAliveKeys.filter((item) => item !== key)
      const dirtyKeys = prev.dirtyKeys.filter((item) => openKeys.has(item))
      return {
        keepAliveKeys: pruneKeepAliveKeys([...withoutKey, key], dirtyKeys, openKeys, key),
        dirtyKeys,
      }
    })
  }, [pruneKeepAliveKeys])

  const setPaneDirty = useCallback((key: string, dirty: boolean) => {
    setPaneState((prev) => {
      const openKeys = new Set(Object.keys(useWorkbenchStore.getState().openTableTabs))
      const dirtySet = new Set(prev.dirtyKeys.filter((item) => openKeys.has(item)))
      const wasDirty = dirtySet.has(key)
      if (wasDirty === dirty && prev.dirtyKeys.every((item) => openKeys.has(item))) {
        return prev
      }
      if (dirty) dirtySet.add(key)
      else dirtySet.delete(key)
      const dirtyKeys = Array.from(dirtySet)
      return {
        keepAliveKeys: pruneKeepAliveKeys(prev.keepAliveKeys, dirtyKeys, openKeys, activeTableTabKey),
        dirtyKeys,
      }
    })
  }, [activeTableTabKey, pruneKeepAliveKeys])

  const removePaneKeys = useCallback((keys: Iterable<string>) => {
    const removing = new Set(keys)
    setPaneState((prev) => ({
      keepAliveKeys: prev.keepAliveKeys.filter((key) => !removing.has(key)),
      dirtyKeys: prev.dirtyKeys.filter((key) => !removing.has(key)),
    }))
  }, [])

  useEffect(() => {
    if (activeTableTabKey) markPaneVisited(activeTableTabKey)
  }, [activeTableTabKey, markPaneVisited])

  useEffect(() => {
    const openKeys = new Set(Object.keys(openTableTabs))
    setPaneState((prev) => {
      const dirtyKeys = prev.dirtyKeys.filter((key) => openKeys.has(key))
      const keepAliveKeys = pruneKeepAliveKeys(prev.keepAliveKeys, dirtyKeys, openKeys, activeTableTabKey)
      if (
        dirtyKeys.length === prev.dirtyKeys.length &&
        keepAliveKeys.length === prev.keepAliveKeys.length &&
        dirtyKeys.every((key, index) => key === prev.dirtyKeys[index]) &&
        keepAliveKeys.every((key, index) => key === prev.keepAliveKeys[index])
      ) {
        return prev
      }
      return { keepAliveKeys, dirtyKeys }
    })
  }, [activeTableTabKey, openTableTabs, pruneKeepAliveKeys])

  // --- 请求竞态控制 ---
  const loadSeqRef = useRef<Record<string, number>>({})
  useEffect(() => {
    return () => { loadSeqRef.current = {} }
  }, [])

  // --- Tab 右键菜单 ---
  const [tabCtxMenu, setTabCtxMenu] = useState<{ x: number; y: number; tabKey: string } | null>(null)
  const [renamingQueryTab, setRenamingQueryTab] = useState<{ tabKey: string; value: string } | null>(null)
  const renameQueryTabInputRef = useRef<InputRef>(null)
  const renamingQueryTabKey = renamingQueryTab?.tabKey

  useEffect(() => {
    if (!renamingQueryTabKey) return undefined
    const frame = window.requestAnimationFrame(() => {
      renameQueryTabInputRef.current?.focus({ cursor: 'all' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [renamingQueryTabKey])

  // --- 树节点右键菜单 ---
  const [treeCtxMenu, setTreeCtxMenu] = useState<{ x: number; y: number; nodeKey: string } | null>(null)

  // ESC 键关闭树节点右键菜单
  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setTreeCtxMenu(null)
    }
    if (treeCtxMenu) {
      document.addEventListener('keydown', handleEsc)
      return () => document.removeEventListener('keydown', handleEsc)
    }
  }, [treeCtxMenu])

  // --- 新建数据库弹窗状态 ---
  const [createDbModal, setCreateDbModal] = useState<{ connectionId: string; connectionName: string; dbType: ConnectionConfig['dbType'] } | null>(null)

  // --- 编辑数据库弹窗状态 ---
  const [editDbModal, setEditDbModal] = useState<{ connectionId: string; databaseName: string } | null>(null)


  // --- SQL 文件导入弹窗状态 ---
  const [importSqlModal, setImportSqlModal] = useState<{ connectionId: string; connectionName: string; database?: string; dbType: ConnectionConfig['dbType'] } | null>(null)
  const [exportModal, setExportModal] = useState<{ connectionId: string; connectionName: string; database: string; dbType: ConnectionConfig['dbType'] } | null>(null)
  const [backupModal, setBackupModal] = useState<{ connectionId: string; connectionName: string; database: string; dbType: ConnectionConfig['dbType'] } | null>(null)
  const [restoreModal, setRestoreModal] = useState<{ connectionId: string; connectionName: string; database: string; dbType: ConnectionConfig['dbType'] } | null>(null)
  const [renameTableTarget, setRenameTableTarget] = useState<{ connectionId: string; database: string; tableName: string } | null>(null)
  const [renameTableInput, setRenameTableInput] = useState('')
  const [renamingTable, setRenamingTable] = useState(false)

  const [showShortcuts, setShowShortcuts] = useState(false)
  const [callProcedureTarget, setCallProcedureTarget] = useState<CallProcedureTarget | null>(null)

  const storeAddTab = useSqlEditorStore((s) => s.addTab)
  const consumePendingSql = useSqlEditorStore((s) => s.consumePendingSql)

  // --- 消费 pendingSql（来自结构对比等外部入口）---
  // 挂载时消费一次，填入新 SQL Tab，不自动执行
  useEffect(() => {
    const pending = consumePendingSql()
    if (!pending?.sql) return
    const queryId = storeAddTab(pending.connectionId, pending.database)
    const tabKey = `sql:${queryId}`
    const connId = pending.connectionId
    const connName = openConnections.find(c => c.id === connId)?.name || 'SQL 工作区'
    batchUpdate({
      openTableTabs: {
        ...useWorkbenchStore.getState().openTableTabs,
        [tabKey]: {
          type: 'sql-query',
          connectionId: connId || '',
          connectionName: connName,
          database: pending.database,
          queryId,
          label: 'SQL 工作区',
        }
      },
      activeTableTabKey: tabKey,
    })
    useSqlEditorStore.getState().updateTab(queryId, {
      sql: pending.sql,
      connectionId: connId,
      database: pending.database,
    })
    toast.success('SQL 已加载到工作区，请审核后手动执行')
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // --- 自动加载连接列表 ---
  useEffect(() => {
    if (connections.length === 0) {
      connectionApi.list().then((list) => {
        setConnections(list as ConnectionConfig[])
      }).catch(() => {})
    }
  }, [connections.length, setConnections])

  // --- 设置自动重连回调（API 层 NOT_CONNECTED 处理）---
  useEffect(() => {
    setReconnectCallback((connectionId: string) => {
      updateConnection(connectionId, { status: 'connected' })
    })
    return () => setReconnectCallback(null)
  }, [updateConnection])

  // --- 加载某个连接的数据库列表 ---
  // 确保连接已打开（自动重连）
  const ensureConnected = useCallback(async (connId: string): Promise<boolean> => {
    const conn = connections.find((c) => c.id === connId)
    if (!conn) return false
    if (conn.status === 'connected') return true
    try {
      const hide = toast.loading(`正在连接「${conn.name}」...`)
      await connectionApi.open(conn.id)
      updateConnection(conn.id, { status: 'connected' })
      hide()
      toast.success(`已连接到「${conn.name}」`)
      return true
    } catch (e) {
      handleApiError(e, '自动连接失败')
      return false
    }
  }, [connections, updateConnection])

  const loadDatabases = useCallback(async (connId: string) => {
    if (!(await ensureConnected(connId))) return
    setLoadingConns((prev) => new Set(prev).add(connId))
    try {
      const dbs = await metadataApi.databases(connId) as DatabaseInfo[]
      setDatabasesMap((prev) => ({ ...prev, [connId]: dbs }))
    } catch (e) {
      handleApiError(e, '加载数据库列表失败')
    } finally {
      setLoadingConns((prev) => {
        const next = new Set(prev)
        next.delete(connId)
        return next
      })
    }
  }, [ensureConnected, setDatabasesMap])

  // 当 openConnections 变化时，自动加载新增连接的数据库列表
  useEffect(() => {
    for (const conn of openConnections) {
      if (!databasesMap[conn.id]) {
        loadDatabases(conn.id)
      }
    }
  }, [openConnections, databasesMap, loadDatabases])

  // --- 加载某库下的对象列表 ---
  const loadTables = useCallback(async (connId: string, dbName: string) => {
    if (!(await ensureConnected(connId))) return
    const key = `${connId}::${dbName}`
    try {
      const tbls = await metadataApi.objects(connId, dbName) as TableInfo[]
      setObjectsMap((prev) => ({ ...prev, [key]: tbls }))
    } catch (e) {
      handleApiError(e, '加载对象列表失败')
    }
  }, [ensureConnected, setObjectsMap])

  const loadChildTables = useCallback(async (
    connId: string,
    dbName: string,
    stableName: string,
    options?: { append?: boolean; search?: string },
  ) => {
    if (!(await ensureConnected(connId))) return
    const key = `${connId}::${dbName}::${stableName}`
    const current = childTablesMap[key]
    const search = options?.search ?? current?.search ?? ''
    const append = options?.append === true && search === current?.search
    const offset = append ? current.items.length : 0
    const seq = (childTableLoadSeqRef.current[key] ?? 0) + 1
    childTableLoadSeqRef.current[key] = seq
    setChildTablesMap((prev) => ({
      ...prev,
      [key]: {
        items: append ? prev[key]?.items ?? [] : [],
        offset,
        limit: prev[key]?.limit ?? 100,
        hasMore: prev[key]?.hasMore ?? false,
        loading: true,
        search,
      },
    }))
    try {
      const page = await metadataApi.timeSeriesChildren(connId, dbName, stableName, {
        offset,
        limit: 100,
        search: search || undefined,
      }) as TimeSeriesChildTablePage
      if (childTableLoadSeqRef.current[key] !== seq) return
      setChildTablesMap((prev) => ({
        ...prev,
        [key]: {
          ...page,
          items: append ? [...(prev[key]?.items ?? []), ...page.items] : page.items,
          loading: false,
          search,
        },
      }))
    } catch (error) {
      if (childTableLoadSeqRef.current[key] !== seq) return
      setChildTablesMap((prev) => ({
        ...prev,
        [key]: { ...(prev[key] ?? { items: [], offset: 0, limit: 100, hasMore: false, search }), loading: false },
      }))
      handleApiError(error, '加载 TDengine 子表失败')
    }
  }, [childTablesMap, ensureConnected])

  // --- 多 Tab 数据加载（懒加载 + Tab 内独立缓存）---
  const updateTabState = useCallback((tabKey: string, updater: (prev: TableTabState) => Partial<TableTabState>) => {
    setOpenTableTabs(prev => {
      const tab = prev[tabKey]
      if (!tab || tab.type !== 'table') return prev
      return { ...prev, [tabKey]: { ...tab, ...updater(tab) } as TableTabState }
    })
  }, [setOpenTableTabs])

  const refreshTableRows = useCallback(async (tabKey: string, query?: TableDataQuery, errorMessage = '刷新数据失败') => {
    const requestKey = `${tabKey}::data`
    const seq = (loadSeqRef.current[requestKey] ?? 0) + 1
    loadSeqRef.current[requestKey] = seq
    const isCurrentRequest = () => loadSeqRef.current[requestKey] === seq
    const stateTab = useWorkbenchStore.getState().openTableTabs[tabKey]
    if (!stateTab || stateTab.type !== 'table') return

    const dataQuery = query ? { ...(stateTab.dataQuery ?? {}), ...query } : stateTab.dataQuery ?? {}
    const hasQuery = Boolean(dataQuery.where || dataQuery.orderBy)

    updateTabState(tabKey, (prev) => ({
      dataQuery,
      loadingMoreRows: false,
      loadingTabs: addUnique(prev.loadingTabs, 'data'),
    }))

    try {
      const rows = await metadataApi.previewRows(
        stateTab.connectionId,
        stateTab.database,
        stateTab.tableName,
        { ...dataQuery, limit: PREVIEW_PAGE_SIZE }
      ) as Record<string, unknown>[]
      if (!isCurrentRequest()) return
      updateTabState(tabKey, (prev) => ({
        previewRows: rows,
        dataQuery,
        hasMoreRows: !hasQuery && rows.length >= PREVIEW_PAGE_SIZE,
        loadedTabs: addUnique(prev.loadedTabs, 'data'),
      }))
    } catch (e) {
      if (!isCurrentRequest()) return
      handleApiError(e, errorMessage)
    } finally {
      if (isCurrentRequest()) {
        updateTabState(tabKey, (prev) => ({
          loadingTabs: removeItems(prev.loadingTabs, ['data']),
        }))
      }
    }
  }, [updateTabState])

  const loadTabDataForTab = useCallback(async (tabKey: string, connId: string, dbName: string, tableName: string, tab: TableDetailLoadTarget) => {
    const requestKey = `${tabKey}::${tab}`
    const seq = (loadSeqRef.current[requestKey] ?? 0) + 1
    loadSeqRef.current[requestKey] = seq
    const isCurrentRequest = () => loadSeqRef.current[requestKey] === seq
    const startedTabs: string[] = []
    try {
      const promises: Promise<void>[] = []
      // currentTab 可能为 undefined（新 Tab 刚创建，state 还未 commit）
      const currentTab = useWorkbenchStore.getState().openTableTabs[tabKey] ?? openTableTabs[tabKey]
      if (currentTab && currentTab.type !== 'table') return
      const tableTab = currentTab as TableTabState | undefined
      const { loadColumns: needColumns, loadTab: needTab } = tableDetailLoadPlan(
        tab,
        tableTab?.loadedTabs ?? [],
      )
      if (!needColumns && !needTab) return
      if (needColumns) startedTabs.push('columns')
      if (needTab) startedTabs.push(tab)
      updateTabState(tabKey, (prev) => ({
        loadingTabs: startedTabs.reduce((next, item) => addUnique(next, item), prev.loadingTabs),
      }))
      if (needColumns) {
        promises.push(
          metadataApi.columns(connId, dbName, tableName).then((columns: unknown) => {
            if (!isCurrentRequest()) return
            updateTabState(tabKey, (prev) => ({
              columns: (columns as ColumnInfo[]) || [],
              loadedTabs: addUnique(prev.loadedTabs, 'columns'),
            }))
          })
        )
      }
      if (needTab) {
        if (tab === 'data') {
          const dataQuery = tableTab?.dataQuery ?? {}
          const hasQuery = Boolean(dataQuery.where || dataQuery.orderBy)
          promises.push(
            metadataApi.previewRows(connId, dbName, tableName, { ...dataQuery, limit: PREVIEW_PAGE_SIZE }).then((rows: unknown) => {
              if (!isCurrentRequest()) return
              const rowArr = rows as Record<string, unknown>[]
              updateTabState(tabKey, (prev) => ({
                previewRows: rowArr,
                hasMoreRows: !hasQuery && rowArr.length >= PREVIEW_PAGE_SIZE,
                loadedTabs: addUnique(prev.loadedTabs, 'data'),
              }))
            })
          )
        } else if (tab === 'design') {
          // TableDesigner handles its own data loading internally, so nothing is strictly required here except marking it loaded.
          promises.push(Promise.resolve().then(() => {
              if (!isCurrentRequest()) return
              updateTabState(tabKey, (prev) => ({
                loadedTabs: addUnique(prev.loadedTabs, 'design'),
              }))
          }))
        } else if (tab === 'ddl') {
          promises.push(
            metadataApi.ddl(connId, dbName, tableName).then((ddlStr: unknown) => {
              if (!isCurrentRequest()) return
              updateTabState(tabKey, (prev) => ({
                ddl: ddlStr as string,
                loadedTabs: addUnique(prev.loadedTabs, 'ddl'),
              }))
            })
          )
        } else if (tab === 'tags' && tableTab?.tableKind) {
          const tagRequest = tableTab.tableKind === 'SUPER_TABLE'
            ? metadataApi.timeSeriesTagDefinitions(connId, dbName, tableName)
            : metadataApi.timeSeriesTagValues(connId, dbName, tableName)
          promises.push(
            tagRequest.then((tags: unknown) => {
              if (!isCurrentRequest()) return
              updateTabState(tabKey, (prev) => ({
                tagDefinitions: prev.tableKind === 'SUPER_TABLE' ? tags as TimeSeriesTagDefinition[] : [],
                tagValues: prev.tableKind === 'CHILD_TABLE' ? tags as TimeSeriesTagValue[] : [],
                loadedTabs: addUnique(prev.loadedTabs, 'tags'),
              }))
            })
          )
        }
      }
      await Promise.all(promises)
    } catch (e) {
      if (!isCurrentRequest()) return
      handleApiError(e, '加载表详情失败')
    } finally {
      if (isCurrentRequest() && startedTabs.length > 0) {
        updateTabState(tabKey, (prev) => ({
          loadingTabs: removeItems(prev.loadingTabs, startedTabs),
        }))
      }
    }
  }, [openTableTabs, updateTabState])

  const applyRenamedTableState = useCallback((connId: string, dbName: string, oldName: string, newName: string) => {
    const oldTabKey = `table:${connId}::${dbName}::${oldName}`
    const newTabKey = `table:${connId}::${dbName}::${newName}`
    const state = useWorkbenchStore.getState()
    const nextTabs: Record<string, WorkbenchTab> = { ...state.openTableTabs }
    const oldTab = nextTabs[oldTabKey]
    let nextActiveTableTabKey = state.activeTableTabKey
    let reloadTabKey: string | null = null
    let reloadDetailTab: TableTabState['detailTab'] | null = null

    if (oldTab?.type === 'table') {
      delete nextTabs[oldTabKey]
      nextTabs[newTabKey] = {
        ...oldTab,
        tableName: newName,
        columns: [],
        indexes: [],
        ddl: '',
        previewRows: [],
        dataQuery: {},
        hasMoreRows: false,
        loadingMoreRows: false,
        loadedTabs: [],
        loadingTabs: [],
      }
      if (state.activeTableTabKey === oldTabKey) nextActiveTableTabKey = newTabKey
      reloadTabKey = newTabKey
      reloadDetailTab = oldTab.detailTab
    }

    for (const [tabKey, tab] of Object.entries(nextTabs)) {
      if (
        tab.type === 'table-designer' &&
        tab.mode === 'edit' &&
        tab.connectionId === connId &&
        tab.database === dbName &&
        tab.tableName === oldName
      ) {
        nextTabs[tabKey] = { ...tab, tableName: newName }
      }
    }

    setObjectsMap((prev) => {
      const objectKey = `${connId}::${dbName}`
      const objects = prev[objectKey]
      if (!objects) return prev
      return {
        ...prev,
        [objectKey]: objects.map((item) => item.name === oldName ? { ...item, name: newName } : item),
      }
    })

    const selected = state.selectedCtx
    const nextSelectedCtx =
      selected?.connectionId === connId && selected.database === dbName && selected.table === oldName
        ? { ...selected, table: newName }
        : selected
    const nextActiveTable =
      state.activeConnectionId === connId && state.activeDatabase === dbName && state.activeTable === oldName
        ? newName
        : state.activeTable

    batchUpdate({
      openTableTabs: nextTabs,
      activeTableTabKey: nextActiveTableTabKey,
      selectedCtx: nextSelectedCtx,
      activeTable: nextActiveTable,
    })

    return { tabKey: reloadTabKey, detailTab: reloadDetailTab }
  }, [batchUpdate, setObjectsMap])

  const closeRenameTableModal = useCallback(() => {
    if (renamingTable) return
    setRenameTableTarget(null)
    setRenameTableInput('')
  }, [renamingTable])

  const handleRenameTableSubmit = useCallback(async () => {
    if (!renameTableTarget || renamingTable) return

    const targetName = renameTableInput.trim()
    const { connectionId, database, tableName } = renameTableTarget
    if (!targetName || targetName === tableName) return

    setRenamingTable(true)
    try {
      await metadataApi.renameTable(connectionId, database, tableName, targetName)
      const renamedTab = applyRenamedTableState(connectionId, database, tableName, targetName)
      toast.success(`表已重命名为「${targetName}」`)
      setRenameTableTarget(null)
      setRenameTableInput('')
      loadTables(connectionId, database)
      if (renamedTab.tabKey && renamedTab.detailTab) {
        loadTabDataForTab(renamedTab.tabKey, connectionId, database, targetName, renamedTab.detailTab)
      }
    } catch (e) {
      handleApiError(e, '重命名表失败')
    } finally {
      setRenamingTable(false)
    }
  }, [applyRenamedTableState, loadTables, loadTabDataForTab, renameTableInput, renameTableTarget, renamingTable])

  // 打开或激活一个表 Tab
  const openOrActivateTab = useCallback((
    connId: string,
    connName: string,
    dbName: string,
    tableName: string,
    defaultTab: 'data' | 'ddl' | 'design' | 'tags' = 'data',
    objectType: 'table' | 'view' | 'procedure' | 'function' | 'trigger' = 'table',
    tableKind?: TableKind,
    stableName?: string,
  ) => {
    const tabKey = `table:${connId}::${dbName}::${tableName}`
    const existing = openTableTabs[tabKey]
    if (!existing) {
      const newTab: TableTabState = {
        type: 'table',
        connectionId: connId,
        connectionName: connName,
        database: dbName,
        tableName,
        objectType,
        tableKind,
        stableName,
        tagDefinitions: [],
        tagValues: [],
        columns: [],
        indexes: [],
        ddl: '',
        previewRows: [],
        dataQuery: {},
        hasMoreRows: false,
        loadingMoreRows: false,
        detailTab: defaultTab,
        loadedTabs: [],
        loadingTabs: [],
      }
      batchUpdate({
        openTableTabs: { ...openTableTabs, [tabKey]: newTab },
        activeTableTabKey: tabKey,
        selectedCtx: { connectionId: connId, database: dbName, table: tableName },
        activeConnectionId: connId,
        activeConnectionName: connName,
        activeDatabase: dbName,
        activeTable: tableName,
      })
      loadTabDataForTab(tabKey, connId, dbName, tableName, defaultTab)
    } else {
      batchUpdate({
        activeTableTabKey: tabKey,
        selectedCtx: { connectionId: connId, database: dbName, table: tableName },
        activeConnectionId: connId,
        activeConnectionName: connName,
        activeDatabase: dbName,
        activeTable: tableName,
      })
    }
  }, [openTableTabs, batchUpdate, loadTabDataForTab])

  const handleOpenObjectDetailFromQuery = useCallback((request: OpenObjectDetailRequest) => {
    const connection = connections.find((item) => item.id === request.connectionId)
    if (!connection) {
      toast.warning('无法识别对象所属连接，请重新选择连接后再试')
      return
    }

    if (!openConnections.some((item) => item.id === connection.id)) {
      addOpenConnection(connection.id, connection.name, connection.dbType)
    }

    const defaultTab = (request.objectType === 'table' || request.objectType === 'view') ? 'data' : 'ddl'
    openOrActivateTab(connection.id, connection.name, request.database, request.name, defaultTab, request.objectType)
  }, [addOpenConnection, connections, openConnections, openOrActivateTab])

  const handleQueryContextChange = useCallback((context: {
    queryId: string
    connectionId?: string
    database?: string
  }) => {
    const tabKey = `sql:${context.queryId}`
    setOpenTableTabs((current) => {
      const tab = current[tabKey]
      if (!tab || tab.type !== 'sql-query') return current
      const connection = context.connectionId
        ? connections.find((item) => item.id === context.connectionId)
        : undefined
      const connectionId = context.connectionId ?? tab.connectionId
      const connectionName = connection?.name ?? tab.connectionName
      if (
        tab.connectionId === connectionId &&
        tab.connectionName === connectionName &&
        tab.database === context.database
      ) return current
      return {
        ...current,
        [tabKey]: {
          ...tab,
          connectionId,
          connectionName,
          database: context.database,
        },
      }
    })
  }, [connections, setOpenTableTabs])

  // 打开或激活数据库概览 Tab
  const openOrActivateDbOverview = useCallback((connId: string, connName: string, dbName: string) => {
    const tabKey = `db:${connId}::${dbName}`
    const existing = openTableTabs[tabKey]
    if (!existing) {
      batchUpdate({
        openTableTabs: {
          ...openTableTabs,
          [tabKey]: { type: 'db-overview' as const, connectionId: connId, connectionName: connName, database: dbName },
        },
        activeTableTabKey: tabKey,
        selectedCtx: { connectionId: connId, database: dbName },
        activeConnectionId: connId,
        activeConnectionName: connName,
        activeDatabase: dbName,
      })
    } else {
      batchUpdate({
        activeTableTabKey: tabKey,
        selectedCtx: { connectionId: connId, database: dbName },
        activeConnectionId: connId,
        activeConnectionName: connName,
        activeDatabase: dbName,
      })
    }
    loadTables(connId, dbName)
  }, [openTableTabs, batchUpdate, loadTables])

  // 打开或激活分类列表 Tab
  const openOrActivateCategoryTab = useCallback((connId: string, connName: string, dbName: string, catKey: string) => {
    const tabKey = `cat:${connId}::${dbName}::${catKey}`
    const existing = openTableTabs[tabKey]
    if (!existing) {
      batchUpdate({
        openTableTabs: {
          ...openTableTabs,
          [tabKey]: { type: 'category-list' as const, connectionId: connId, connectionName: connName, database: dbName, category: catKey },
        },
        activeTableTabKey: tabKey,
        selectedCtx: { connectionId: connId, database: dbName, category: catKey },
        activeConnectionId: connId,
        activeConnectionName: connName,
        activeDatabase: dbName,
      })
    } else {
      batchUpdate({
        activeTableTabKey: tabKey,
        selectedCtx: { connectionId: connId, database: dbName, category: catKey },
        activeConnectionId: connId,
        activeConnectionName: connName,
        activeDatabase: dbName,
      })
    }
    loadTables(connId, dbName)
  }, [openTableTabs, batchUpdate, loadTables])

  // 关闭一个表 Tab
  const closeTableTab = useCallback((tabKey: string) => {
    setRenamingQueryTab((current) => current?.tabKey === tabKey ? null : current)
    removePaneKeys([tabKey])
    setOpenTableTabs(prev => {
      const next = { ...prev }
      delete next[tabKey]
      // 如果关闭的是当前活动 Tab，切换到另一个
      if (activeTableTabKey === tabKey) {
        const remaining = Object.keys(next)
        setActiveTableTabKey(remaining.length > 0 ? remaining[remaining.length - 1] : null)
      }
      return next
    })
  }, [activeTableTabKey, removePaneKeys, setOpenTableTabs, setActiveTableTabKey])

  const beginRenameQueryTab = useCallback((tabKey: string) => {
    const tab = openTableTabs[tabKey]
    if (tab?.type !== 'sql-query') return
    setTabCtxMenu(null)
    setActiveTableTabKey(tabKey)
    setRenamingQueryTab({ tabKey, value: tab.label || '查询' })
  }, [openTableTabs, setActiveTableTabKey])

  const handleTabBarContextMenu = useCallback((event: React.MouseEvent<HTMLElement>) => {
    event.preventDefault()
    const tabKey = resolveWorkbenchTabKey(event.target)
    if (!tabKey || !openTableTabs[tabKey]) return
    event.stopPropagation()
    setTabCtxMenu({ x: event.clientX, y: event.clientY, tabKey })
  }, [openTableTabs])

  const handleTabBarDoubleClick = useCallback((event: React.MouseEvent<HTMLElement>) => {
    const target = event.target
    if (target instanceof Element && target.closest('.ant-tabs-tab-remove, input, textarea')) return

    const tabKey = resolveWorkbenchTabKey(target)
    if (!tabKey || openTableTabs[tabKey]?.type !== 'sql-query') return
    event.preventDefault()
    event.stopPropagation()
    beginRenameQueryTab(tabKey)
  }, [beginRenameQueryTab, openTableTabs])

  const finishRenameQueryTab = useCallback((tabKey: string, value: string) => {
    const title = normalizeQueryTabTitle(value)
    if (title) {
      const target = useWorkbenchStore.getState().openTableTabs[tabKey]
      if (target?.type === 'sql-query') {
        setOpenTableTabs((current) => ({
          ...current,
          [tabKey]: { ...target, label: title },
        }))
        useSqlEditorStore.getState().updateTab(target.queryId, { title })
      }
    }
    setRenamingQueryTab(null)
  }, [setOpenTableTabs])

  useEffect(() => {
    if (renamingQueryTab && !openTableTabs[renamingQueryTab.tabKey]) {
      setRenamingQueryTab(null)
    }
  }, [openTableTabs, renamingQueryTab])

  // --- 添加连接到工作台 ---
  const [connectingId, setConnectingId] = useState<string | null>(null)
  const handleAddConnection = useCallback(async (connId: string) => {
    const conn = connections.find((c) => c.id === connId)
    if (!conn) return

    // 未连接的自动连接
    if (conn.status !== 'connected') {
      setConnectingId(connId)
      const hide = toast.loading(`正在连接「${conn.name}」...`)
      try {
        await connectionApi.open(conn.id)
        updateConnection(conn.id, { status: 'connected' })
        hide()
        toast.success(`已连接到「${conn.name}」`)
      } catch (e) {
        hide()
        handleApiError(e, '连接失败')
        return
      } finally {
        setConnectingId(null)
      }
    }
    addOpenConnection(conn.id, conn.name, conn.dbType)
  }, [connections, updateConnection, addOpenConnection])

  // --- 从工作台移除连接 ---
  const handleRemoveConnection = useCallback((connId: string) => {
    const wasCurrent = selectedCtx?.connectionId === connId
    removeOpenConnection(connId)
    // 清理该连接下的所有已打开 Tab
    setOpenTableTabs(prev => {
      const next = { ...prev }
      for (const key of Object.keys(next)) {
        if (key.startsWith(`${connId}::`)) delete next[key]
      }
      return next
    })
    if (wasCurrent) {
      setActiveTableTabKey(null)
    }
  }, [removeOpenConnection, selectedCtx, setActiveTableTabKey, setOpenTableTabs])

  // --- 打开 SQL 编辑器 ---
  const openSqlEditor = useCallback(() => {
    const connId = selectedCtx?.connectionId ?? activeConnectionId ?? openConnections[0]?.id
    const db = selectedCtx?.database ?? activeDatabase ?? undefined

    // 创建 SQL Editor Store 级别的 Tab
    const queryId = storeAddTab(connId, db)

    // 映射到 Workbench 级别的 Tab
    const tabKey = `sql:${queryId}`
    batchUpdate({
      openTableTabs: {
        ...openTableTabs,
        [tabKey]: {
          type: 'sql-query',
          connectionId: connId,
          connectionName: openConnections.find(c => c.id === connId)?.name || 'Unknown',
          database: db,
          queryId,
          label: `查询 ${queryId.replace('tab-', '')}`
        }
      },
      activeTableTabKey: tabKey
    })
  }, [selectedCtx, activeConnectionId, activeDatabase, openConnections, storeAddTab, openTableTabs, batchUpdate])

  // --- 打开表设计器 ---
  const openTableDesignerTab = useCallback((connId: string, connName: string, db: string, tableName?: string) => {
    const dbType = openConnections.find((connection) => connection.id === connId)?.dbType ?? null
    if (!getDbCapabilities(dbType).workbench.tableDesigner) {
      toast.warning('当前数据库类型不支持通用表设计器')
      return
    }
    if (tableName) {
      // Edit mode: open the unified table tab and focus directly on 'design'
      const key = `table:${connId}::${db}::${tableName}`
      const existing = openTableTabs[key]
      if (!existing) {
        openOrActivateTab(connId, connName, db, tableName, 'design', 'table')
        // Set the active tab to design right after initialization
        setTimeout(() => {
          updateTabState(key, () => ({ detailTab: 'design' }))
          loadTabDataForTab(key, connId, db, tableName, 'design')
        }, 50)
      } else {
        batchUpdate({ activeTableTabKey: key, selectedCtx: { connectionId: connId, database: db, table: tableName } })
        updateTabState(key, () => ({ detailTab: 'design' }))
        loadTabDataForTab(key, connId, db, tableName, 'design')
      }
      return
    }

    // Create mode: Open standalone TableDesigner tab
    const uniqueId = `design:${connId}:${db}:new_${Date.now()}`

    batchUpdate({
      openTableTabs: {
        ...openTableTabs,
        [uniqueId]: {
          type: 'table-designer',
          connectionId: connId,
          connectionName: connName,
          database: db,
          mode: 'create',
        }
      },
      activeTableTabKey: uniqueId
    })
  }, [openConnections, openTableTabs, batchUpdate, openOrActivateTab, updateTabState, loadTabDataForTab])

  // --- 对象分类 ---
  const objectCategories = useMemo(() => [
    { key: 'tables', label: '表', types: ['table'], icon: <Table2 size={16} /> },
    { key: 'views', label: '视图', types: ['view'], icon: <Eye size={16} /> },
    { key: 'procedures', label: '存储过程', types: ['procedure'], icon: <Cog size={16} /> },
    { key: 'functions', label: '函数', types: ['function'], icon: <FunctionSquare size={16} /> },
    { key: 'triggers', label: '触发器', types: ['trigger'], icon: <Zap size={16} /> },
  ], [])
  // --- 高性能图标缓存 ---
  const iconTable = useMemo(() => <Table2 size={14} color={token.colorPrimary} />, [token.colorPrimary])
  const iconView = useMemo(() => <Eye size={14} color="#3B82F6" />, [])
  const iconProcedure = useMemo(() => <Cog size={14} color="#8B5CF6" />, [])
  const iconFunction = useMemo(() => <FunctionSquare size={14} color="#EC4899" />, [])
  const iconTrigger = useMemo(() => <Zap size={14} color="#F59E0B" />, [])
  const iconDb = useMemo(() => <Database size={16} color={token.colorTextSecondary} />, [token.colorTextSecondary])
  const iconConn = useMemo(() => <Activity size={16} color={token.colorPrimary} />, [token.colorPrimary])

  // --- 收藏脚本 --------------------------------------------
  const [savedScripts, setSavedScripts] = useState<SavedScript[]>([])
  const lastSavedScriptsFocusLoadAtRef = useRef(0)
  const loadSavedScripts = useCallback(async () => {
    try {
      const res = await scriptApi.list()
      setSavedScripts(res as SavedScript[])
    } catch { /* ignore */ }
  }, [])
  useEffect(() => { loadSavedScripts() }, [loadSavedScripts])
  // 暴露给外部调用（如保存弹窗结束后想刷新）可以利用发布订阅，或简单定时、或重新挂载
  useEffect(() => {
    const handleFocus = () => {
      const now = Date.now()
      if (now - lastSavedScriptsFocusLoadAtRef.current < 5000) return
      lastSavedScriptsFocusLoadAtRef.current = now
      loadSavedScripts()
    }
    window.addEventListener('focus', handleFocus)
    return () => window.removeEventListener('focus', handleFocus)
  }, [loadSavedScripts])

  // --- 注册收藏脚本及帮助中心到 Command Palette ----------------------
  const registerCommand = useCommandStore(s => s.registerCommand)
  const unregisterCommand = useCommandStore(s => s.unregisterCommand)

  useEffect(() => {
    // 快捷键聚合帮助
    registerCommand({
      id: 'show-shortcuts',
      title: '键盘快捷键大全',
      category: '帮助',
      icon: <FileText size={16} />,
      action: () => setShowShortcuts(true)
    })

    savedScripts.forEach(script => {
      registerCommand({
        id: `script-${script.id}`,
        title: `脚本: ${script.name}`,
        category: '收藏的脚本',
        icon: <StarOutlined style={{ color: token.colorWarning }} />,
        action: () => {
          const queryId = storeAddTab(script.database ? undefined : undefined)
          const tabKey = `sql:${queryId}`
          batchUpdate({
            openTableTabs: {
              ...useWorkbenchStore.getState().openTableTabs,
              [tabKey]: {
                type: 'sql-query',
                queryId,
                label: script.name,
                connectionId: script.database ? 'saved' : '',
                connectionName: 'Saved Script'
              }
            },
            activeTableTabKey: tabKey,
          })
          useSqlEditorStore.getState().updateTab(queryId, { sql: script.content, database: script.database })
        }
      })
    })
    return () => {
      savedScripts.forEach(script => {
        unregisterCommand(`script-${script.id}`)
      })
    }
  }, [savedScripts, registerCommand, unregisterCommand, storeAddTab, batchUpdate, token.colorWarning])

  // --- 构建多连接对象树 ---
  // key 编码规则：
  //   连接节点: "conn:{connId}"
  //   数据库节点: "db:{connId}:{dbName}"
  //   分类节点: "cat:{connId}:{dbName}:{catKey}"
  //   对象节点: "obj:{connId}:{dbName}:{objName}"

  // --- 解决切换工作台路由时的海量节点遍历卡顿 ---
  const [deferTree, setDeferTree] = useState(true)
  useEffect(() => {
    // 延迟 50ms 计算树，让页面骨架先秒切渲染，解决点击"工作台"即卡死的性能瓶颈
    const timer = setTimeout(() => setDeferTree(false), 50)
    return () => clearTimeout(timer)
  }, [])

  const treeData: TreeDataNode[] = useMemo(() => {
    if (deferTree) return [] // 初次渲染不计算

    const scriptsNode: TreeDataNode = {
      key: 'saved-scripts',
      'data-node-key': 'saved-scripts',
      title: <span style={{ fontWeight: 600 }}>📚 收藏脚本</span>,
      children: savedScripts.map(s => ({
        key: `script:${s.id}`,
        'data-node-key': `script:${s.id}`,
        title: s.name,
        icon: <StarOutlined style={{ color: token.colorWarning }} />,
        isLeaf: true,
      }))
    }

    const connNodes: TreeDataNode[] = openConnections.map((conn) => {
    const connDbs = databasesMap[conn.id] || []
    const isLoading = loadingConns.has(conn.id)

    const dbChildren: DataNode[] = connDbs
      .map((db) => {
        const objKey = `${conn.id}::${db.name}`
        const dbObjects = objectsMap[objKey] || []
        const lowerSearch = deferredSearch.toLowerCase()
        const dbNameMatches = !deferredSearch || db.name.toLowerCase().includes(lowerSearch)
        // 数据库内是否有匹配的对象
        const hasMatchingObjects = deferredSearch && dbObjects.some(
          (t) => t.name.toLowerCase().includes(lowerSearch)
        )

        // 数据库名不匹配且子对象也不匹配 → 过滤掉
        if (deferredSearch && !dbNameMatches && !hasMatchingObjects) return null

        const categoryChildren: TreeDataNode[] = conn.dbType === 'tdengine'
          ? [
              {
                key: `cat:${conn.id}:${db.name}:stables`,
                'data-node-key': `cat:${conn.id}:${db.name}:stables`,
                title: `超级表 (${dbObjects.filter((item) => item.tableKind === 'SUPER_TABLE').length})`,
                icon: <Activity size={15} />,
                children: dbObjects
                  .filter((item) => item.tableKind === 'SUPER_TABLE')
                  .map((stable) => {
                    const childKey = `${conn.id}::${db.name}::${stable.name}`
                    const childState = childTablesMap[childKey]
                    return {
                      key: `tsstable:${conn.id}:${db.name}:${stable.name}`,
                      'data-node-key': `tsstable:${conn.id}:${db.name}:${stable.name}`,
                      title: <Space size={6}><span>{stable.name}</span><Tag style={{ margin: 0 }}>STABLE</Tag></Space>,
                      icon: <Activity size={14} color={token.colorPrimary} />,
                      children: [
                        {
                          key: `tssearch:${conn.id}:${db.name}:${stable.name}`,
                          'data-node-key': `tssearch:${conn.id}:${db.name}:${stable.name}`,
                          selectable: false,
                          isLeaf: true,
                          title: (
                            <Input.Search
                              size="small"
                              allowClear
                              placeholder="搜索子表"
                              defaultValue={childState?.search}
                              onClick={(event) => event.stopPropagation()}
                              onSearch={(value) => loadChildTables(conn.id, db.name, stable.name, { search: value })}
                              style={{ width: 170 }}
                            />
                          ),
                        },
                        ...(childState?.items ?? []).map((child) => ({
                          key: `tschild:${conn.id}:${db.name}:${stable.name}:${child.name}`,
                          'data-node-key': `tschild:${conn.id}:${db.name}:${stable.name}:${child.name}`,
                          title: <Space size={6}><span>{child.name}</span><Tag style={{ margin: 0 }}>子表</Tag></Space>,
                          icon: iconTable,
                          isLeaf: true,
                        })),
                        ...(childState?.loading ? [{
                          key: `tsloading:${conn.id}:${db.name}:${stable.name}`,
                          title: '正在加载子表...',
                          selectable: false,
                          isLeaf: true,
                        }] : []),
                        ...(childState?.hasMore ? [{
                          key: `tsmore:${conn.id}:${db.name}:${stable.name}`,
                          selectable: false,
                          isLeaf: true,
                          title: (
                            <Button
                              type="link"
                              size="small"
                              loading={childState.loading}
                              onClick={(event) => {
                                event.stopPropagation()
                                loadChildTables(conn.id, db.name, stable.name, { append: true })
                              }}
                            >
                              加载更多
                            </Button>
                          ),
                        }] : []),
                      ],
                    } as TreeDataNode
                  }),
              },
              {
                key: `cat:${conn.id}:${db.name}:tables`,
                'data-node-key': `cat:${conn.id}:${db.name}:tables`,
                title: `普通表 (${dbObjects.filter((item) => item.tableKind === 'BASIC_TABLE').length})`,
                icon: <Table2 size={16} />,
                children: dbObjects
                  .filter((item) => item.tableKind === 'BASIC_TABLE')
                  .map((table) => ({
                    key: `obj:${conn.id}:${db.name}:${table.name}`,
                    'data-node-key': `obj:${conn.id}:${db.name}:${table.name}`,
                    title: <Space size={6}><span>{table.name}</span><Tag style={{ margin: 0 }}>普通表</Tag></Space>,
                    icon: iconTable,
                    isLeaf: true,
                  })),
              },
            ]
          : objectCategories.map((cat) => {
            const items = dbObjects.filter(
              (t) => cat.types.includes(t.type)
                && (!deferredSearch || dbNameMatches || t.name.toLowerCase().includes(lowerSearch))
            )
            return {
              key: `cat:${conn.id}:${db.name}:${cat.key}`,
              'data-node-key': `cat:${conn.id}:${db.name}:${cat.key}`,
              title: `${cat.label} (${items.length})`,
              icon: cat.icon,
              children: items.map((t) => ({
                key: `obj:${conn.id}:${db.name}:${t.name}`,
                'data-node-key': `obj:${conn.id}:${db.name}:${t.name}`,
                title: t.name,
                icon: t.type === 'view' ? iconView : t.type === 'trigger' ? iconTrigger : t.type === 'procedure' ? iconProcedure : t.type === 'function' ? iconFunction : iconTable,
                isLeaf: true,
              })),
            } as TreeDataNode
          }).filter((cat) => !deferredSearch || (cat.children && cat.children.length > 0))

        return {
          key: `db:${conn.id}:${db.name}`,
          'data-node-key': `db:${conn.id}:${db.name}`,
          title: db.name,
          icon: iconDb,
          children: categoryChildren,
        } as TreeDataNode
      })
      .filter(Boolean) as TreeDataNode[]

    return {
      key: `conn:${conn.id}`,
      'data-node-key': `conn:${conn.id}`,
      title: (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', paddingRight: 4 }}>
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
            {conn.name}
          </span>
          <Tooltip title="从工作台移除">
            <CloseOutlined
              style={{ fontSize: 11, color: token.colorTextQuaternary, flexShrink: 0, marginLeft: 4 }}
              onClick={(e) => {
                e.stopPropagation()
                handleRemoveConnection(conn.id)
              }}
            />
          </Tooltip>
        </div>
      ),
      icon: iconConn,
      children: isLoading ? [{ key: `loading:${conn.id}`, 'data-node-key': `loading:${conn.id}`, title: '加载中...', isLeaf: true, selectable: false }] : dbChildren,
    } as TreeDataNode
  })

  return [scriptsNode, ...connNodes]
  }, [deferTree, openConnections, databasesMap, objectsMap, loadingConns, deferredSearch, objectCategories, token, handleRemoveConnection, iconConn, iconDb, iconTable, iconTrigger, iconView, iconProcedure, iconFunction, savedScripts, childTablesMap, loadChildTables])

  // --- 搜索时自动展开所有匹配的节点 ---
  const prevExpandedRef = useRef<React.Key[]>([])
  useEffect(() => {
    if (deferredSearch) {
      // 保存搜索前的展开状态
      if (prevExpandedRef.current.length === 0) {
        prevExpandedRef.current = [...expandedKeys]
      }
      // 展开所有有子节点的节点
      const allKeys: React.Key[] = []
      const collectKeys = (nodes: DataNode[]) => {
        for (const node of nodes) {
          if (node.children && node.children.length > 0) {
            allKeys.push(node.key)
            collectKeys(node.children)
          }
        }
      }
      collectKeys(treeData)
      setExpandedKeys(allKeys)
    } else if (prevExpandedRef.current.length > 0) {
      // 搜索清空时恢复之前的展开状态
      setExpandedKeys(prevExpandedRef.current)
      prevExpandedRef.current = []
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deferredSearch])

  // --- 树节点选中 ---
  const handleTreeSelect = (_: React.Key[], info: { node: DataNode }) => {
    const key = String(info.node.key)

    if (key.startsWith('conn:')) {
      const connId = key.slice(5)
      const conn = openConnections.find((c) => c.id === connId)
      batchUpdate({
        selectedCtx: { connectionId: connId },
        activeConnectionId: connId,
        activeConnectionName: conn?.name ?? null,
      })
    } else if (key.startsWith('db:')) {
      const parts = key.slice(3).split(':')
      const connId = parts[0]
      const dbName = parts.slice(1).join(':')
      const conn = openConnections.find((c) => c.id === connId)
      openOrActivateDbOverview(connId, conn?.name ?? '', dbName)
    } else if (key.startsWith('tsstable:') || key.startsWith('tschild:')) {
      const prefix = key.startsWith('tsstable:') ? 'tsstable:' : 'tschild:'
      const parts = key.slice(prefix.length).split(':')
      const connId = parts[0]
      const dbName = parts[1]
      const objectName = key.startsWith('tsstable:') ? parts.slice(2).join(':') : parts.slice(3).join(':')
      const conn = openConnections.find((item) => item.id === connId)
      const tabKey = `table:${connId}::${dbName}::${objectName}`
      batchUpdate({
        selectedCtx: { connectionId: connId, database: dbName, table: objectName },
        activeConnectionId: connId,
        activeConnectionName: conn?.name ?? null,
        activeDatabase: dbName,
        activeTable: objectName,
        ...(openTableTabs[tabKey] ? { activeTableTabKey: tabKey } : {}),
      })
    } else if (key.startsWith('obj:')) {
      const parts = key.slice(4).split(':')
      const connId = parts[0]
      const dbName = parts[1]
      const objName = parts.slice(2).join(':')
      const conn = openConnections.find((c) => c.id === connId)
      const tabKey = `table:${connId}::${dbName}::${objName}`
      const hasTab = !!openTableTabs[tabKey]
      batchUpdate({
        selectedCtx: { connectionId: connId, database: dbName, table: objName },
        activeConnectionId: connId,
        activeConnectionName: conn?.name ?? null,
        activeDatabase: dbName,
        activeTable: objName,
        ...(hasTab ? { activeTableTabKey: tabKey } : {}),
      })
    } else if (key.startsWith('cat:')) {
      const parts = key.slice(4).split(':')
      const connId = parts[0]
      const dbName = parts[1]
      const catKey = parts.slice(2).join(':')
      const conn = openConnections.find((c) => c.id === connId)
      openOrActivateCategoryTab(connId, conn?.name ?? '', dbName, catKey)
    }
  }

  // --- 树节点展开 ---
  const handleTreeExpand = (keys: React.Key[], info: { node: DataNode; expanded: boolean }) => {
    setExpandedKeys(keys)
    const key = String(info.node.key)
    if (info.expanded) {
      if (key.startsWith('conn:')) {
        // 展开连接节点时加载数据库列表
        const connId = key.slice(5)
        if (!databasesMap[connId]) {
          loadDatabases(connId)
        }
      } else if (key.startsWith('db:')) {
        // 展开数据库节点时加载表列表
        const parts = key.slice(3).split(':')
        const connId = parts[0]
        const dbName = parts.slice(1).join(':')
        const objKey = `${connId}::${dbName}`
        if (!objectsMap[objKey]) {
          loadTables(connId, dbName)
        }
      } else if (key.startsWith('tsstable:')) {
        const parts = key.slice('tsstable:'.length).split(':')
        const connId = parts[0]
        const dbName = parts[1]
        const stableName = parts.slice(2).join(':')
        const childKey = `${connId}::${dbName}::${stableName}`
        if (!childTablesMap[childKey]) {
          loadChildTables(connId, dbName, stableName)
        }
      }
    }
  }

  // --- 右键导出表数据 ---
  const handleTableExport = useCallback(async (
    connId: string, dbName: string, tableName: string, format: 'csv' | 'json' | 'sql',
  ) => {
    try {
      toast.info('正在获取数据...')
      const [columns, rows] = await Promise.all([
        metadataApi.columns(connId, dbName, tableName) as Promise<ColumnInfo[]>,
        metadataApi.previewRows(connId, dbName, tableName) as Promise<Record<string, unknown>[]>,
      ])
      const colNames = (columns || []).map(c => c.name)
      if (format === 'sql') {
        const dbType = openConnectionDbTypes.get(connId)
        if (!dbType) {
          toast.warning('无法识别当前连接的数据库类型')
          return
        }
        confirmDataExport({
          columns: colNames,
          rows,
          format,
          filenameBase: tableName,
          tableName,
          dbType,
          loadedOnly: true,
        })
      } else {
        confirmDataExport({ columns: colNames, rows, format, filenameBase: tableName, tableName, loadedOnly: true })
      }
    } catch (e) {
      handleApiError(e, '导出数据失败')
    }
  }, [openConnectionDbTypes])

  // --- 右键菜单 ---
  const getContextMenuItems = useCallback((nodeKey: string): MenuProps['items'] => {
    // 连接节点：新建数据库 / 刷新
    if (nodeKey.startsWith('conn:')) {
      const connId = nodeKey.slice(5)
      const conn = openConnections.find((c) => c.id === connId)
      const cap = getDbCapabilities(conn?.dbType ?? null)
      const items: MenuProps['items'] = []
      if (cap.metadata.schemaCreation) {
        items.push({
          key: 'create-db',
          icon: <PlusOutlined />,
          label: conn?.dbType === 'dameng' ? '新建 Schema' : '新建数据库',
          onClick: () => setCreateDbModal({ connectionId: connId, connectionName: conn?.name ?? '', dbType: conn?.dbType ?? 'mysql' }),
        })
      }
      items.push(
        {
          key: 'refresh-conn',
          icon: <ReloadOutlined />,
          label: '刷新',
          onClick: () => loadDatabases(connId),
        },
      )
      return items
    }
    // 数据库节点：刷新 / 删除数据库
    if (nodeKey.startsWith('db:')) {
      const parts = nodeKey.slice(3).split(':')
      const connId = parts[0]
      const dbName = parts.slice(1).join(':')
      const conn = openConnections.find((c) => c.id === connId)
      const dbType = conn?.dbType ?? 'mysql'
      const cap = getDbCapabilities(dbType)
      const namespaceLabel = databaseNamespaceLabel(dbType)
      const items: MenuProps['items'] = [
        {
          key: 'refresh-db',
          icon: <ReloadOutlined />,
          label: '刷新',
          onClick: () => loadTables(connId, dbName),
        },
        { type: 'divider' },
      ]
      if (cap.metadata.schemaAlterCharset) {
        items.unshift({
          key: 'edit-db',
          icon: <EditOutlined />,
          label: '编辑数据库',
          onClick: () => setEditDbModal({ connectionId: connId, databaseName: dbName }),
        })
      }
      if (cap.workbench.backup) {
        items.push({
          key: 'backup-db',
          icon: <DatabaseOutlined />,
          label: `备份${namespaceLabel}...`,
          onClick: () => setBackupModal({ connectionId: connId, connectionName: conn?.name ?? '', database: dbName, dbType }),
        })
      }
      if (cap.workbench.restore) {
        items.push({
          key: 'restore-db',
          icon: <ReloadOutlined />,
          label: `恢复${namespaceLabel}...`,
          onClick: () => setRestoreModal({ connectionId: connId, connectionName: conn?.name ?? '', database: dbName, dbType }),
        })
      }
      if (cap.workbench.exportData) {
        items.push({
          key: 'export-db',
          icon: <ExportOutlined />,
          label: `导出${namespaceLabel}...`,
          onClick: () => setExportModal({ connectionId: connId, connectionName: conn?.name ?? '', database: dbName, dbType }),
        })
      }
      if (cap.workbench.importSql) {
        items.push({
          key: 'import-sql',
          icon: <UploadOutlined />,
          label: '执行 SQL 文件',
          onClick: () => setImportSqlModal({ connectionId: connId, connectionName: conn?.name ?? '', database: dbName, dbType }),
        })
      }
      if (cap.metadata.schemaManagement) {
        items.push(
          { type: 'divider' },
          {
            key: 'drop-db',
            icon: <DeleteOutlined />,
            label: `删除${namespaceLabel}`,
            danger: true,
            onClick: () => {
              Modal.confirm({
                title: `确认删除${namespaceLabel}「${dbName}」？`,
                content: `此操作不可恢复，${namespaceLabel} 中的所有数据将被永久删除。`,
                okText: '删除',
                okType: 'danger',
                cancelText: '取消',
                onOk: async () => {
                  try {
                    await metadataApi.dropDatabase(connId, dbName)
                    toast.success(`${namespaceLabel}「${dbName}」已删除`)
                    loadDatabases(connId)
                    if (selectedCtx?.connectionId === connId && selectedCtx?.database === dbName) {
                      setSelectedCtx({ connectionId: connId })
                    }
                  } catch (e) {
                    handleApiError(e, `删除${namespaceLabel}失败`)
                  }
                },
              })
            },
          },
        )
      }
      return items
    }
    // 分类节点（表/视图/触发器）
    if (nodeKey.startsWith('cat:')) {
      const parts = nodeKey.slice(4).split(':')
      const connId = parts[0]
      const dbName = parts[1]
      const catKey = parts.slice(2).join(':')
      const conn = openConnections.find((c) => c.id === connId)
      const cap = getDbCapabilities(conn?.dbType ?? null)
      const items: MenuProps['items'] = []
      if (catKey === 'tables' && cap.workbench.tableDesigner) {
        items.push({
          key: 'create-table',
          icon: <PlusOutlined />,
          label: '新建表',
          onClick: () => {
            const conn = openConnections.find((c) => c.id === connId)
            openTableDesignerTab(connId, conn?.name ?? '', dbName)
          },
        })
      }
      items.push({
        key: 'refresh-cat',
        icon: <ReloadOutlined />,
        label: '刷新',
        onClick: () => loadTables(connId, dbName),
      })
      return items
    }
    // 表/对象节点
    if (nodeKey.startsWith('obj:')) {
      const parts = nodeKey.slice(4).split(':')
      const connId = parts[0]
      const dbName = parts[1]
      const objName = parts.slice(2).join(':')

      // 查找对象类型
      const objKey = `${connId}::${dbName}`
      const dbObjects = objectsMap[objKey] || []
      const objInfo = dbObjects.find(o => o.name === objName)
      const objType = objInfo?.type ?? 'table'

      // 非表对象（视图/存储过程/函数/触发器）
      if (objType !== 'table') {
        const isProcOrFunc = objType === 'procedure' || objType === 'function'
        const menuItems: MenuProps['items'] = []

        // 存储过程/函数：执行入口（排在第一位）
        if (isProcOrFunc) {
          menuItems.push({
            key: 'call-procedure',
            icon: <span style={{ fontSize: 14 }}>{objType === 'function' ? '⨍' : '⚙'}</span>,
            label: objType === 'function' ? '调用函数...' : '执行存储过程...',
            onClick: () => {
              setCallProcedureTarget({
                connectionId: connId,
                database: dbName,
                name: objName,
                type: objType === 'function' ? 'FUNCTION' : 'PROCEDURE',
              })
            },
          })
          menuItems.push({ type: 'divider' } as const)
        }

        menuItems.push(
          {
            key: 'view-ddl',
            icon: <FileText size={14} />,
            label: '查看定义 (DDL)',
            onClick: () => {
              const conn = openConnections.find((c) => c.id === connId)
              openOrActivateTab(connId, conn?.name ?? '', dbName, objName, 'ddl', objType as 'table' | 'view' | 'procedure' | 'function' | 'trigger')
            },
          },
          {
            key: 'refresh-obj',
            icon: <ReloadOutlined />,
            label: '刷新',
            onClick: () => loadTables(connId, dbName),
          },
        )
        return menuItems
      }

      // 表对象：完整菜单
      const conn = openConnections.find((item) => item.id === connId)
      const cap = getDbCapabilities(conn?.dbType ?? null)
      const tableItems: MenuProps['items'] = []
      if (cap.workbench.tableDesigner) tableItems.push({
          key: 'design-table',
          icon: <EditOutlined />,
          label: '设计表',
          onClick: () => {
            openTableDesignerTab(connId, conn?.name ?? '', dbName, objName)
          },
        })
      if (cap.workbench.tableRename) tableItems.push({
          key: 'rename-table',
          icon: <EditOutlined />,
          label: '重命名',
          onClick: () => {
            setRenameTableTarget({ connectionId: connId, database: dbName, tableName: objName })
            setRenameTableInput(objName)
          },
        })
      if (cap.workbench.exportData) tableItems.push(
        {
          key: 'export-csv',
          icon: <DownloadOutlined />,
          label: '导出为 CSV',
          onClick: () => handleTableExport(connId, dbName, objName, 'csv'),
        },
        {
          key: 'export-json',
          icon: <DownloadOutlined />,
          label: '导出为 JSON',
          onClick: () => handleTableExport(connId, dbName, objName, 'json'),
        },
        {
          key: 'export-sql',
          icon: <DownloadOutlined />,
          label: '导出为 SQL INSERT',
          onClick: () => handleTableExport(connId, dbName, objName, 'sql'),
        },
      )
      if (cap.workbench.tableTruncate) tableItems.push(
        { type: 'divider' },
        {
          key: 'truncate-table',
          icon: <DeleteOutlined />,
          label: '清空表',
          onClick: () => {
            Modal.confirm({
              title: `确认清空表「${objName}」？`,
              content: '此操作将删除表中所有数据，但保留表结构。操作不可恢复。',
              okText: '清空',
              okType: 'danger',
              cancelText: '取消',
              onOk: async () => {
                try {
                  await metadataApi.truncateTable(connId, dbName, objName)
                  toast.success(`表「${objName}」已清空`)
                } catch (e) {
                  handleApiError(e, '清空表失败')
                }
              },
            })
          },
        },
      )
      if (cap.workbench.tableDrop) tableItems.push(
        { type: 'divider' },
        {
          key: 'drop-table',
          icon: <DeleteOutlined />,
          label: '删除表',
          danger: true,
          onClick: () => {
            Modal.confirm({
              title: `确认删除表「${objName}」？`,
              content: '此操作将永久删除该表及其所有数据，不可恢复。',
              okText: '删除',
              okType: 'danger',
              cancelText: '取消',
              onOk: async () => {
                try {
                  await metadataApi.dropTable(connId, dbName, objName)
                  toast.success(`表「${objName}」已删除`)
                  loadTables(connId, dbName)
                  if (selectedCtx?.connectionId === connId && selectedCtx?.database === dbName && selectedCtx?.table === objName) {
                    setSelectedCtx({ connectionId: connId, database: dbName })
                  }
                } catch (e) {
                  handleApiError(e, '删除表失败')
                }
              },
            })
          },
        },
      )
      tableItems.push({
        key: 'refresh-table',
        icon: <ReloadOutlined />,
        label: '刷新',
        onClick: () => loadTables(connId, dbName),
      })
      return tableItems
    }
    return []
  }, [openConnections, loadDatabases, loadTables, selectedCtx, setSelectedCtx, handleTableExport, setCreateDbModal, setEditDbModal, setImportSqlModal, setExportModal, openTableDesignerTab, objectsMap, openOrActivateTab])

  // 延迟计算菜单项：仅在右键触发瞬间计算 1 次（而非 titleRender 中 N 次）
  const treeCtxMenuItems = useMemo(
    () => (treeCtxMenu ? getContextMenuItems(treeCtxMenu.nodeKey) : []),
    [treeCtxMenu, getContextMenuItems]
  )
  // 可添加的连接列表（排除已在工作台中的）
  const availableConnections = connections.filter(
    (c) => !openConnections.some((o) => o.id === c.id)
  )

  // 当前选中节点的 tree key
  const selectedTreeKeys: React.Key[] = selectedCtx
    ? (selectedCtx.table && selectedCtx.database
      ? [`obj:${selectedCtx.connectionId}:${selectedCtx.database}:${selectedCtx.table}`]
      : selectedCtx.category && selectedCtx.database
        ? [`cat:${selectedCtx.connectionId}:${selectedCtx.database}:${selectedCtx.category}`]
        : selectedCtx.database
          ? [`db:${selectedCtx.connectionId}:${selectedCtx.database}`]
          : [`conn:${selectedCtx.connectionId}`]
    )
    : []

  return (
    <>
      <Layout style={{ height: '100%' }}>
      {/* 左侧对象树 */}
      <Sider
        width={280}
        style={{
          background: 'var(--glass-panel)',
          backdropFilter: 'var(--glass-blur-sm)',
          borderRight: '1px solid var(--glass-border)',
          boxShadow: 'none',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
        <style>{`
          .workbench-object-tree .ant-tree-node-content-wrapper.ant-tree-node-selected {
            background: ${token.controlItemBgActive} !important;
            color: ${token.colorText} !important;
            border: none !important;
            box-shadow: inset 2px 0 0 ${token.colorPrimary};
            border-radius: ${token.borderRadiusSM}px;
          }
          .workbench-object-tree .ant-tree-title {
            display: inline-block;
            vertical-align: middle;
            max-width: calc(100% - 24px);
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }
          .workbench-object-tree .ant-tree-node-content-wrapper {
            display: inline-flex;
            align-items: center;
            width: 100%;
            overflow: hidden;
            border-radius: ${token.borderRadiusSM}px;
            transition: background 200ms ease;
            padding-right: 4px;
          }
          .workbench-object-tree .ant-tree-node-content-wrapper:hover {
            background: ${token.controlItemBgHover} !important;
          }
          .workbench-object-tree .ant-tree-switcher {
            color: ${token.colorTextTertiary};
          }
        `}</style>
        <div style={{ padding: '16px', borderBottom: '1px solid var(--glass-border)' }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
            <div style={{ minWidth: 0 }}>
              <Text strong style={{ fontSize: 15, color: token.colorText }}>
                资源浏览器
              </Text>
              <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
                {openConnections.length > 0 ? `${openConnections.length} 个连接已载入工作台` : '添加连接后即可浏览数据库对象'}
              </Text>
            </div>
            <Dropdown
              trigger={['click']}
              placement="bottomRight"
              menu={{
                items: availableConnections.length > 0
                  ? availableConnections.map((c) => ({
                      key: c.id,
                      label: (
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', minWidth: 160 }}>
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.name}</span>
                          {c.status !== 'connected' && (
                            <span style={{ fontSize: 11, color: token.colorTextQuaternary, marginLeft: 8, flexShrink: 0 }}>未连接</span>
                          )}
                        </div>
                      ),
                      onClick: () => handleAddConnection(c.id),
                    }))
                  : [{ key: 'empty', label: <span style={{ color: token.colorTextQuaternary }}>全体连接已载入</span>, disabled: true }],
              }}
            >
              <Tooltip title="添加连接" placement="bottom">
                <Button
                  loading={!!connectingId}
                  size="small"
                  icon={!connectingId && <Plus size={14} />}
                  style={{ ...quietButtonStyle, minWidth: 92 }}
                >
                  添加连接
                </Button>
              </Tooltip>
            </Dropdown>
          </div>
          <Space size={8} wrap style={{ marginTop: 12 }}>
            <Tag variant="filled" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
              连接 {openConnections.length}
            </Tag>
            <Tag variant="filled" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
              脚本 {savedScripts.length}
            </Tag>
            {activeConnectionName && (
              <Tag variant="filled" color="processing" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
                当前: {activeConnectionName}
              </Tag>
            )}
          </Space>
        </div>

        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--glass-border)' }}>
          <Input
            placeholder="筛选数据库、表、视图或脚本"
            prefix={<Search size={14} color={token.colorTextQuaternary} style={{ marginRight: 4 }} />}
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            allowClear
            style={{ borderRadius: 10, fontSize: 12 }}
          />
        </div>
        <div
          ref={treeContainerRef}
          style={{ flex: 1, overflow: 'hidden' }}
          onContextMenu={(e) => {
            // 事件委托：从冒泡路径中查找 data-tree-key，作为 onRightClick 的可靠 fallback
            const el = (e.target as HTMLElement).closest('[data-tree-key]')
            if (!el) return
            const nodeKey = el.getAttribute('data-tree-key')
            if (!nodeKey) return
            const menuItems = getContextMenuItems(nodeKey)
            if (!menuItems || menuItems.length === 0) return
            e.preventDefault()
            setTreeCtxMenu({ x: e.clientX, y: e.clientY, nodeKey })
          }}
        >
          {openConnections.length === 0 && savedScripts.length === 0 ? (
            <div style={{ padding: '24px 12px', textAlign: 'center' }}>
              <Text type="secondary" style={{ fontSize: 13 }}>
                从上方下拉框添加连接
              </Text>
            </div>
          ) : (
            <>
              <Tree
                height={treeHeight}
                className="workbench-object-tree"
                treeData={treeData}
                showIcon
                blockNode
                titleRender={
                  // eslint-disable-next-line @typescript-eslint/no-explicit-any
                  (nodeData: any) => {
                  const key = String(nodeData.key)
                  const title = nodeData.title as React.ReactNode
                  // 叶子对象节点保留 Tooltip（名称可能被截断）
                  if (nodeData.isLeaf && key.startsWith('obj:')) {
                    return (
                      <Tooltip title={String(nodeData.title)} mouseEnterDelay={0.5} placement="right">
                        <span data-tree-key={key} style={{ display: 'contents' }}>{title}</span>
                      </Tooltip>
                    )
                  }
                  return <span data-tree-key={key} style={{ display: 'contents' }}>{title}</span>
                }}
                onRightClick={({ event, node }) => {
                  const nodeKey = String(node.key)
                  const menuItems = getContextMenuItems(nodeKey)
                  if (menuItems && menuItems.length > 0) {
                    setTreeCtxMenu({ x: event.clientX, y: event.clientY, nodeKey })
                  }
                }}
                onSelect={handleTreeSelect}
                selectedKeys={selectedTreeKeys}
                expandedKeys={expandedKeys}
                onExpand={handleTreeExpand}
                onDoubleClick={(_e, node) => {
                  const key = String(node.key)
                  if (key.startsWith('tsstable:')) {
                    const parts = key.slice('tsstable:'.length).split(':')
                    const connId = parts[0]
                    const dbName = parts[1]
                    const stableName = parts.slice(2).join(':')
                    const conn = openConnections.find((item) => item.id === connId)
                    openOrActivateTab(connId, conn?.name ?? '', dbName, stableName, 'data', 'table', 'SUPER_TABLE')
                  } else if (key.startsWith('tschild:')) {
                    const parts = key.slice('tschild:'.length).split(':')
                    const connId = parts[0]
                    const dbName = parts[1]
                    const stableName = parts[2]
                    const childName = parts.slice(3).join(':')
                    const conn = openConnections.find((item) => item.id === connId)
                    openOrActivateTab(connId, conn?.name ?? '', dbName, childName, 'data', 'table', 'CHILD_TABLE', stableName)
                  } else if (key.startsWith('obj:')) {
                    const parts = key.slice(4).split(':')
                    const connId = parts[0]
                    const dbName = parts[1]
                    const objName = parts.slice(2).join(':')
                    const conn = openConnections.find((c) => c.id === connId)

                    // 检查对象类型
                    const objKey = `${connId}::${dbName}`
                    const dbObjects = objectsMap[objKey] || []
                    const objInfo = dbObjects.find(o => o.name === objName)
                    const objType = objInfo?.type ?? 'table'

                    if (objType === 'table' || objType === 'view' || objType === 'stable') {
                      // 表和视图：打开数据预览
                      openOrActivateTab(
                        connId,
                        conn?.name ?? '',
                        dbName,
                        objName,
                        'data',
                        objType === 'view' ? 'view' : 'table',
                        objInfo?.tableKind,
                      )
                    } else {
                      // 存储过程/函数/触发器：打开 DDL 标签页
                      openOrActivateTab(connId, conn?.name ?? '', dbName, objName, 'ddl', objType as 'procedure' | 'function' | 'trigger')
                    }
                  } else if (key.startsWith('script:')) {
                    const scriptId = key.slice(7)
                    const s = savedScripts.find(x => x.id === scriptId)
                    if (s) {
                       const queryId = storeAddTab(activeConnectionId || undefined, s.database || activeDatabase || undefined)

                       const tabKey = `sql:${queryId}`
                       batchUpdate({
                         openTableTabs: {
                           ...openTableTabs,
                           [tabKey]: {
                             type: 'sql-query',
                             queryId,
                             label: s.name,
                             connectionId: activeConnectionId || '',
                             connectionName: activeConnectionName || 'Saved Script'
                           }
                         },
                         activeTableTabKey: tabKey,
                       })
                       useSqlEditorStore.getState().updateTab(queryId, { sql: s.content, database: s.database || activeDatabase || '' })
                    }
                  }
                }}
              />
            </>
          )}
        </div>
        </div>
      </Sider>

      {/* 右侧详情区 */}
      <Content style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', background: 'transparent' }}>
        {openConnections.length === 0 && Object.keys(openTableTabs).length === 0 ? (
          <EmptyState
            icon={<DatabaseOutlined style={{ fontSize: 48, color: token.colorTextQuaternary }} />}
            description={
              <div style={{ textAlign: 'center' }}>
                <div style={{ marginBottom: 16, color: token.colorTextSecondary }}>工作台当前没有可浏览的连接</div>
                <div style={{ fontSize: 13, color: token.colorTextQuaternary }}>先从左侧添加连接，或直接打开收藏脚本开始查询</div>
                <div style={{ ...compactPanelStyle, marginTop: 24, padding: '8px 16px', display: 'inline-block' }}>
                  <kbd style={{ fontFamily: 'monospace', background: token.colorFillSecondary, padding: '2px 4px', borderRadius: 4 }}>Cmd/Ctrl</kbd> + <kbd style={{ fontFamily: 'monospace', background: token.colorFillSecondary, padding: '2px 4px', borderRadius: 4 }}>K</kbd> 唤起命令面板
                </div>
              </div>
            }
          />
        ) : activeTableTabKey && openTableTabs[activeTableTabKey] ? (
          <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
            <style>{`
              .workbench-detail-tabs.ant-tabs {
                height: 100%;
                min-height: 0;
              }
              .workbench-detail-tabs .ant-tabs-content-holder {
                flex: 1;
                min-height: 0;
                overflow: hidden;
              }
              .workbench-detail-tabs .ant-tabs-content {
                height: 100%;
                min-height: 0;
              }
              .workbench-detail-tabs .ant-tabs-tabpane,
              .workbench-detail-tabs .ant-tabs-tabpane-active {
                height: 100%;
                min-height: 0;
                overflow: hidden;
              }
              .workbench-main-tabs.ant-tabs-card > .ant-tabs-nav {
                margin: 0 !important;
                background: var(--glass-panel);
                border-bottom: 1px solid var(--glass-border) !important;
              }
              .workbench-main-tabs.ant-tabs-card > .ant-tabs-nav::before {
                border-bottom: none !important;
              }
              .workbench-main-tabs.ant-tabs-card > .ant-tabs-nav .ant-tabs-tab {
                border: none !important;
                background: transparent !important;
                border-radius: 0 !important;
                padding: 10px 14px !important;
                margin: 0 !important;
                border-bottom: 2px solid transparent !important;
                transition: all 0.2s;
                color: ${token.colorTextSecondary};
              }
              .workbench-main-tabs.ant-tabs-card > .ant-tabs-nav .ant-tabs-tab:hover {
                background: ${token.controlItemBgHover} !important;
                color: ${token.colorText};
              }
              .workbench-main-tabs.ant-tabs-card > .ant-tabs-nav .ant-tabs-tab-active {
                background: var(--glass-panel) !important;
                border-bottom: 2px solid ${token.colorPrimary} !important;
                color: ${token.colorText} !important;
              }
              .workbench-main-tabs .ant-tabs-tab-remove {
                opacity: 0;
                transition: opacity 0.2s;
                margin-left: 8px !important;
              }
              .workbench-main-tabs .ant-tabs-tab-active .ant-tabs-tab-remove,
              .workbench-main-tabs .ant-tabs-tab:hover .ant-tabs-tab-remove {
                opacity: 1;
              }
            `}</style>
            {/* 表 Tab 栏 */}
            {(() => {
              const tabKeys = Object.keys(openTableTabs)
              return (
                <>
                  <Tabs
                    className="workbench-main-tabs"
                    type="editable-card"
                    size="small"
                    hideAdd
                    onContextMenu={handleTabBarContextMenu}
                    onDoubleClick={handleTabBarDoubleClick}
                    activeKey={activeTableTabKey ?? undefined}
                    onChange={(key) => {
                      const tab = openTableTabs[key]
                      if (tab) {
                        batchUpdate({
                          activeTableTabKey: key,
                          selectedCtx: tab.type === 'table'
                            ? { connectionId: tab.connectionId, database: tab.database, table: tab.tableName }
                            : tab.type === 'category-list'
                              ? { connectionId: tab.connectionId, database: tab.database, category: tab.category }
                              : tab.type === 'sql-query'
                                ? { connectionId: tab.connectionId, database: tab.database }
                                : { connectionId: tab.connectionId, database: tab.database },
                          activeConnectionId: tab.connectionId,
                          activeConnectionName: tab.connectionName,
                          activeDatabase: tab.database,
                          activeTable: tab.type === 'table' ? tab.tableName : null,
                        })
                      } else {
                        setActiveTableTabKey(key)
                      }
                    }}
                    onEdit={(targetKey, action) => {
                      if (action === 'remove' && typeof targetKey === 'string') {
                        closeTableTab(targetKey)
                      }
                    }}
                    style={{ flexShrink: 0, padding: '0 16px' }}
                    tabBarStyle={{ marginBottom: 0 }}
                    tabBarExtraContent={{
                      right: (
                        <Tooltip title="新建 SQL 查询" placement="bottom">
                          <Button
                            size="small"
                            icon={<PlusOutlined />}
                            style={{ ...quietButtonStyle, marginRight: 4 }}
                            onClick={() => openSqlEditor()}
                          >
                            新建查询
                          </Button>
                        </Tooltip>
                      )
                    }}
                    items={tabKeys.map((key) => {
                      const tab = openTableTabs[key]
                      const tabLabel = tab.type === 'table'
                        ? tab.tableName
                        : tab.type === 'db-overview'
                          ? tab.database
                          : tab.type === 'category-list'
                            ? `${tab.database} / ${tab.category === 'tables' ? '表' : tab.category === 'views' ? '视图' : tab.category === 'triggers' ? '触发器' : tab.category}`
                            : tab.type === 'sql-query'
                              ? tab.label || `查询`
                              : ''
                      const tabIcon = tab.type === 'table'
                        ? <Table2 size={14} style={{ marginRight: 6 }} />
                        : tab.type === 'db-overview'
                          ? <Database size={14} style={{ marginRight: 6 }} />
                          : tab.type === 'sql-query'
                            ? <CodeOutlined style={{ marginRight: 6 }} />
                            : <KeyRound size={16} style={{ marginRight: 6 }} /> // Assuming this is the intended insertion point for KeyRound
                      const tabTitle = tab.type === 'table'
                        ? `${tab.database}.${tab.tableName}`
                        : tab.type === 'sql-query'
                          ? `SQL 查询`
                          : tab.database
                      const isRenaming = tab.type === 'sql-query' && renamingQueryTab?.tabKey === key
                      return {
                        key,
                        label: isRenaming ? (
                          <span
                            style={{ display: 'inline-flex', alignItems: 'center' }}
                            onMouseDown={(event) => event.stopPropagation()}
                            onClick={(event) => event.stopPropagation()}
                          >
                            {tabIcon}
                            <Input
                              ref={renameQueryTabInputRef}
                              aria-label="查询页签名称"
                              size="small"
                              value={renamingQueryTab.value}
                              maxLength={MAX_QUERY_TAB_TITLE_LENGTH}
                              onChange={(event) => setRenamingQueryTab({ tabKey: key, value: event.target.value })}
                              onBlur={() => finishRenameQueryTab(key, renamingQueryTab.value)}
                              onKeyDown={(event) => {
                                event.stopPropagation()
                                if (event.key === 'Enter') {
                                  event.preventDefault()
                                  finishRenameQueryTab(key, renamingQueryTab.value)
                                } else if (event.key === 'Escape') {
                                  event.preventDefault()
                                  setRenamingQueryTab(null)
                                }
                              }}
                              style={{ width: 168, height: 24 }}
                            />
                          </span>
                        ) : (
                          <span
                            title={tab.type === 'sql-query' ? `${tabLabel}（双击重命名）` : tabTitle}
                            style={tab.type === 'sql-query' ? { userSelect: 'none' } : undefined}
                          >
                            {tabIcon}{tabLabel}
                          </span>
                        ),
                        closable: true,
                      }
                    })}
                  />
                  {/* Tab 右键菜单 */}
                  {tabCtxMenu && (
                    <>
                      <div
                        style={{ position: 'fixed', inset: 0, zIndex: 999 }}
                        onClick={() => setTabCtxMenu(null)}
                        onContextMenu={(e) => { e.preventDefault(); setTabCtxMenu(null) }}
                      />
                      <div style={{
                        position: 'fixed', left: tabCtxMenu.x, top: tabCtxMenu.y, zIndex: 1000,
                        background: 'var(--glass-popup)',
                        backdropFilter: 'var(--glass-blur-heavy)',
                        WebkitBackdropFilter: 'var(--glass-blur-heavy)',
                        borderRadius: token.borderRadiusLG,
                        border: '1px solid var(--glass-border)',
                        boxShadow: 'var(--glass-shadow-lg), var(--glass-inner-glow)',
                        padding: '6px 0', minWidth: 140,
                      }}>
                        {[
                          ...(openTableTabs[tabCtxMenu.tabKey]?.type === 'sql-query'
                            ? [{ label: '重命名', onClick: () => beginRenameQueryTab(tabCtxMenu.tabKey) }]
                            : []),
                          { label: '关闭', onClick: () => closeTableTab(tabCtxMenu.tabKey) },
                          { label: '关闭其他', onClick: () => {
                            removePaneKeys(tabKeys.filter((key) => key !== tabCtxMenu.tabKey))
                            setOpenTableTabs(prev => {
                              const keep = prev[tabCtxMenu.tabKey]
                              return keep ? { [tabCtxMenu.tabKey]: keep } : {}
                            })
                            setActiveTableTabKey(tabCtxMenu.tabKey)
                          }},
                          { label: '关闭左侧', onClick: () => {
                            const idx = tabKeys.indexOf(tabCtxMenu.tabKey)
                            const toRemove = new Set(tabKeys.slice(0, idx))
                            removePaneKeys(toRemove)
                            setOpenTableTabs(prev => {
                              const next = { ...prev }
                              for (const k of toRemove) delete next[k]
                              return next
                            })
                            if (activeTableTabKey && toRemove.has(activeTableTabKey)) setActiveTableTabKey(tabCtxMenu.tabKey)
                          }},
                          { label: '关闭右侧', onClick: () => {
                            const idx = tabKeys.indexOf(tabCtxMenu.tabKey)
                            const toRemove = new Set(tabKeys.slice(idx + 1))
                            removePaneKeys(toRemove)
                            setOpenTableTabs(prev => {
                              const next = { ...prev }
                              for (const k of toRemove) delete next[k]
                              return next
                            })
                            if (activeTableTabKey && toRemove.has(activeTableTabKey)) setActiveTableTabKey(tabCtxMenu.tabKey)
                          }},
                          { label: '关闭全部', onClick: () => {
                            removePaneKeys(tabKeys)
                            setOpenTableTabs({})
                            setActiveTableTabKey(null)
                          }},
                        ].map((item) => (
                          <div
                            key={item.label}
                            style={{ padding: '5px 12px', cursor: 'pointer', fontSize: 13, color: 'var(--edb-text-primary)', transition: 'background 0.15s' }}
                            onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'var(--glass-panel-hover)' }}
                            onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                            onClick={() => { item.onClick(); setTabCtxMenu(null) }}
                          >
                            {item.label}
                          </div>
                        ))}
                      </div>
                    </>
                  )}
                </>
              )
            })()}
            {/* Tab 内容区域 — 最近使用的 pane 保持挂载，避免表格切换重建 */}
            <div style={{ flex: 1, minHeight: 0, overflow: 'hidden', position: 'relative' }}>
              {mountedPaneKeys.map((paneKey) => {
                const paneTab = openTableTabs[paneKey]
                if (!paneTab) return null
                const isActivePane = paneKey === activeTableTabKey
                const tabKey = paneKey
                const activeTab = paneTab

                return (
                  <div
                    key={paneKey}
                    aria-hidden={!isActivePane}
                    style={{
                      display: isActivePane ? 'flex' : 'none',
                      flexDirection: 'column',
                      height: '100%',
                      minHeight: 0,
                      overflow: 'hidden',
                    }}
                  >
                    {(() => {

              // ===== 表详情 Tab =====
              if (activeTab.type === 'table') {
                const t = activeTab
                const paneDbType = openConnectionDbTypes.get(t.connectionId)
                const paneCapabilities = getDbCapabilities(paneDbType ?? null)
                const tableKindLabel = t.tableKind === 'SUPER_TABLE'
                  ? '超级表'
                  : t.tableKind === 'CHILD_TABLE'
                    ? '子表'
                    : t.tableKind === 'BASIC_TABLE'
                      ? '普通表'
                      : t.objectType === 'view'
                        ? '视图'
                        : '表'
                const isDataLoading = t.detailTab === 'data' && (
                  t.loadingTabs.includes('data') ||
                  t.loadingTabs.includes('columns')
                )
                const isDdlLoading = t.detailTab === 'ddl' && (
                  t.loadingTabs.includes('ddl')
                )
                return (
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                    <div style={{ padding: '12px 16px 0 16px', flexShrink: 0 }}>
                      <div style={{ ...panelStyle, padding: '14px 16px', marginBottom: 12 }}>
                        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
                          <div style={{ minWidth: 0 }}>
                            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
                              对象详情
                            </Text>
                            <Breadcrumb
                              separator="/"
                              items={[
                                {
                                  title: (
                                    <span style={{ cursor: 'pointer', color: token.colorTextSecondary }} onClick={() => openOrActivateDbOverview(t.connectionId, t.connectionName, t.database)}>
                                      <DatabaseOutlined style={{ marginRight: 4 }} />{t.database}
                                    </span>
                                  ),
                                },
                                {
                                  title: <Text strong style={{ fontSize: 13 }}>{t.tableName}</Text>,
                                },
                              ]}
                            />
                            <Space size={8} wrap style={{ marginTop: 10 }}>
                              <Text strong style={{ fontSize: 18, color: token.colorText }}>{t.tableName}</Text>
                              <Tag variant="filled" color={t.objectType === 'view' ? 'processing' : 'default'} style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
                                {tableKindLabel}
                              </Tag>
                              {t.tableKind === 'CHILD_TABLE' && t.stableName && (
                                <Tag variant="filled" color="blue" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
                                  所属超级表：{t.stableName}
                                </Tag>
                              )}
                              <Tag variant="filled" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
                                {t.connectionName}
                              </Tag>
                            </Space>
                          </div>
                          <Space size={8} wrap>
                            <Button size="small" style={quietButtonStyle} onClick={() => openOrActivateDbOverview(t.connectionId, t.connectionName, t.database)}>
                              返回库概览
                            </Button>
                            <Button size="small" icon={<CodeOutlined />} onClick={() => openSqlEditor()}>
                              新建查询
                            </Button>
                          </Space>
                        </div>
                      </div>
                    </div>
                    <Tabs
                      className="workbench-detail-tabs"
                      size="small"
                      activeKey={t.detailTab}
                      onChange={(key) => {
                        const nextTab = key as 'data' | 'design' | 'tags' | 'ddl'
                        updateTabState(tabKey, () => ({ detailTab: nextTab }))
                        loadTabDataForTab(tabKey, t.connectionId, t.database, t.tableName, nextTab)
                      }}
                      style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', padding: '0 16px 16px' }}
                      tabBarStyle={{ flexShrink: 0, marginBottom: 0 }}
                      items={[
                        (t.objectType === 'table' || t.objectType === 'view') ? {
                          key: 'data',
                          label: `数据${t.previewRows.length > 0 ? ` (${t.previewRows.length})` : ''}`,
                          children: (
                            <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, overflow: 'hidden' }}>
                              <div style={{ ...compactPanelStyle, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 10, padding: '10px 12px', flexShrink: 0 }}>
                                <Text type="secondary" style={{ fontSize: 12, flex: 1 }}>
                                  {t.objectType === 'view'
                                    ? '视图数据为只读模式'
                                    : !paneCapabilities.workbench.rowEdit
                                      ? `${tableKindLabel}数据为只读模式`
                                    : isDataLoading
                                      ? '正在加载表数据...'
                                      : t.columns.length > 0 && t.columns.some((c: ColumnInfo) => c.isPrimaryKey)
                                      ? `可编辑数据 · 主键：${t.columns.filter((c: ColumnInfo) => c.isPrimaryKey).map((c: ColumnInfo) => c.name).join(', ')}`
                                      : '当前表缺少主键，数据编辑功能不可用'}
                                </Text>
                                <Space size={8}>
                                  <Text type="secondary" style={{ fontSize: 12 }}>
                                    {t.previewRows.length > 0 ? `共 ${t.previewRows.length} 行` : ''}
                                  </Text>
                                  <Tooltip title="刷新当前数据">
                                    <Button
                                      size="small"
                                      icon={<ReloadOutlined />}
                                      loading={isDataLoading}
                                      style={quietButtonStyle}
                                      onClick={() => refreshTableRows(tabKey)}
                                    >
                                      刷新
                                    </Button>
                                  </Tooltip>
                                  {t.previewRows.length > 0 && paneCapabilities.workbench.exportData && (
                                    <Dropdown menu={{
                                      items: [
                                        { key: 'csv', label: '导出为 CSV', onClick: () => confirmDataExport({ columns: t.columns.map((c: ColumnInfo) => c.name), rows: t.previewRows, format: 'csv', filenameBase: t.tableName, tableName: t.tableName, loadedOnly: t.hasMoreRows }) },
                                        { key: 'json', label: '导出为 JSON', onClick: () => confirmDataExport({ columns: t.columns.map((c: ColumnInfo) => c.name), rows: t.previewRows, format: 'json', filenameBase: t.tableName, tableName: t.tableName, loadedOnly: t.hasMoreRows }) },
                                        ...(openConnectionDbTypes.get(t.connectionId) ? [{
                                          key: 'sql',
                                          label: '导出为 SQL INSERT',
                                          onClick: () => confirmDataExport({
                                            columns: t.columns.map((c: ColumnInfo) => c.name),
                                            rows: t.previewRows,
                                            format: 'sql',
                                            filenameBase: t.tableName,
                                            tableName: t.tableName,
                                            dbType: openConnectionDbTypes.get(t.connectionId)!,
                                            loadedOnly: t.hasMoreRows,
                                          }),
                                        }] : []),
                                      ],
                                    }}>
                                      <Button size="small" icon={<DownloadOutlined />} style={quietButtonStyle}>导出</Button>
                                    </Dropdown>
                                  )}
                                </Space>
                              </div>
                              <div style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
                                <EditableDataTable
                                  connectionId={t.connectionId}
                                  dbType={openConnectionDbTypes.get(t.connectionId)}
                                  database={t.database}
                                  tableName={t.tableName}
                                  columns={t.columns}
                                  dataSource={t.previewRows}
                                  hasMore={t.hasMoreRows}
                                  loadingMore={t.loadingMoreRows}
                                  loading={isDataLoading}
                                  active={isActivePane && t.detailTab === 'data'}
                                  readOnly={!paneCapabilities.workbench.rowEdit}
                                  allowExport={paneCapabilities.workbench.exportData}
                                  allowSqlExport={paneCapabilities.workbench.exportData}
                                  onDirtyChange={(dirty) => setPaneDirty(tabKey, dirty)}
                                  filterState={{
                                    whereDraft: t.dataQuery?.whereDraft ?? t.dataQuery?.where ?? '',
                                    appliedWhere: t.dataQuery?.appliedWhere ?? t.dataQuery?.where ?? '',
                                    sortColumn: t.dataQuery?.sortColumn ?? null,
                                    sortDirection: t.dataQuery?.sortDirection ?? null,
                                  }}
                                  onFilterStateChange={(patch) => {
                                    updateTabState(tabKey, (prev) => ({
                                      dataQuery: { ...(prev.dataQuery ?? {}), ...patch },
                                    }))
                                  }}
                                  onLoadMore={async () => {
                                    // 防止并发
                                    if (t.loadingMoreRows || !t.hasMoreRows) return
                                    const requestKey = `${tabKey}::data`
                                    const seq = (loadSeqRef.current[requestKey] ?? 0) + 1
                                    loadSeqRef.current[requestKey] = seq
                                    const isCurrentRequest = () => loadSeqRef.current[requestKey] === seq
                                    updateTabState(tabKey, () => ({ loadingMoreRows: true }))
                                    try {
                                      const rows = await metadataApi.previewRows(
                                        t.connectionId, t.database, t.tableName,
                                        { ...(t.dataQuery ?? {}), limit: PREVIEW_PAGE_SIZE, offset: t.previewRows.length }
                                      ) as Record<string, unknown>[]
                                      if (!isCurrentRequest()) return
                                      const hasQuery = Boolean(t.dataQuery?.where || t.dataQuery?.orderBy)
                                      updateTabState(tabKey, (prev) => ({
                                        previewRows: [...prev.previewRows, ...rows],
                                        hasMoreRows: !hasQuery && rows.length >= PREVIEW_PAGE_SIZE,
                                      }))
                                    } catch (e) {
                                      if (!isCurrentRequest()) return
                                      handleApiError(e, '加载更多数据失败')
                                    } finally {
                                      if (isCurrentRequest()) {
                                        updateTabState(tabKey, () => ({ loadingMoreRows: false }))
                                      }
                                    }
                                  }}
                                  onRefresh={() => refreshTableRows(tabKey)}
                                  onFilter={(params) => refreshTableRows(tabKey, params, '筛选数据失败')}
                                />
                              </div>
                            </div>
                          ),
                        } : null,
                        t.objectType === 'table' && paneCapabilities.workbench.tableDesigner ? {
                          key: 'design',
                          label: '设计',
                          children: (
                            <div style={{ height: '100%', overflow: 'hidden' }}>
                              <TableDesigner
                                connectionId={t.connectionId}
                                connectionName={t.connectionName}
                                database={t.database}
                                editTableName={t.tableName}
                                dbType={openConnections.find(c => c.id === t.connectionId)?.dbType}
                                onSuccess={(result: TableDesignerSaveResult) => {
                                  if (result.renamed && result.previousTableName) {
                                    const renamedTab = applyRenamedTableState(
                                      t.connectionId,
                                      t.database,
                                      result.previousTableName,
                                      result.tableName,
                                    )
                                    loadTables(t.connectionId, t.database)
                                    if (renamedTab.tabKey && renamedTab.detailTab) {
                                      loadTabDataForTab(
                                        renamedTab.tabKey,
                                        t.connectionId,
                                        t.database,
                                        result.tableName,
                                        renamedTab.detailTab,
                                      )
                                    }
                                    return
                                  }
                                  updateTabState(tabKey, (prev) => ({
                                    loadedTabs: prev.loadedTabs.filter(k => k !== 'columns'),
                                  }))
                                  loadTabDataForTab(tabKey, t.connectionId, t.database, t.tableName, 'columns')
                                }}
                                onCancel={() => {
                                  updateTabState(tabKey, () => ({ detailTab: 'data' }))
                                }}
                              />
                            </div>
                          ),
                        } : null,
                        (t.tableKind === 'SUPER_TABLE' || t.tableKind === 'CHILD_TABLE') ? {
                          key: 'tags',
                          label: `Tags (${t.tableKind === 'SUPER_TABLE' ? t.tagDefinitions.length : t.tagValues.length})`,
                          children: (
                            <div style={{ ...panelStyle, height: '100%', overflow: 'auto', padding: 16 }}>
                              {t.tableKind === 'CHILD_TABLE' && t.stableName && (
                                <div style={{ ...compactPanelStyle, padding: '10px 12px', marginBottom: 12 }}>
                                  <Text type="secondary" style={{ fontSize: 12 }}>所属超级表</Text>
                                  <Text strong style={{ display: 'block', marginTop: 4 }}>{t.stableName}</Text>
                                </div>
                              )}
                              <Table<TimeSeriesTagDefinition | TimeSeriesTagValue>
                                size="small"
                                rowKey="name"
                                loading={t.loadingTabs.includes('tags')}
                                pagination={false}
                                dataSource={t.tableKind === 'SUPER_TABLE' ? t.tagDefinitions : t.tagValues}
                                columns={[
                                  { title: 'Tag 名称', dataIndex: 'name', key: 'name', width: 240 },
                                  { title: '类型', dataIndex: 'type', key: 'type', width: 200 },
                                  ...(t.tableKind === 'CHILD_TABLE' ? [{
                                    title: '值',
                                    dataIndex: 'value',
                                    key: 'value',
                                    render: (value: string | null | undefined) => value == null ? <Text type="secondary">NULL</Text> : value,
                                  }] : []),
                                ]}
                                locale={{ emptyText: t.loadingTabs.includes('tags') ? '正在加载 Tags...' : '暂无 Tag 信息' }}
                              />
                            </div>
                          ),
                        } : null,
                        {
                          key: 'ddl',
                          label: 'DDL',
                          children: (
                            <div style={{ ...panelStyle, height: '100%', overflow: 'hidden', padding: 0 }}>
                              <DdlViewer ddl={t.ddl} loading={isDdlLoading} />
                            </div>
                          ),
                        },
                      ].filter(Boolean) as NonNullable<typeof Tabs.prototype>[]}
                    />
                  </div>
                )
              }

              // ===== 数据库概览 Tab =====
              if (activeTab.type === 'db-overview') {
                const objKey = `${activeTab.connectionId}::${activeTab.database}`
                const dbObjects = objectsMap[objKey] || []
                const overviewCapabilities = getDbCapabilities(openConnectionDbTypes.get(activeTab.connectionId) ?? null)
                const totalTableBytes = dbObjects
                  .filter((item) => item.type === 'table')
                  .reduce((acc, item) => acc + (item.dataLength || 0) + (item.indexLength || 0), 0)
                const formatBytes = (bytes: number) => {
                  if (!bytes) return '0 B'
                  const units = ['B', 'KB', 'MB', 'GB', 'TB']
                  const order = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
                  return `${(bytes / (1024 ** order)).toFixed(order === 0 ? 0 : 1)} ${units[order]}`
                }
                const objectSummary = [
                  { key: 'tables', label: '表', value: dbObjects.filter((t) => t.type === 'table').length, icon: <Table2 size={16} color={token.colorPrimary} /> },
                  { key: 'views', label: '视图', value: dbObjects.filter((t) => t.type === 'view').length, icon: <Eye size={16} color="#3B82F6" /> },
                  { key: 'procedures', label: '存储过程', value: dbObjects.filter((t) => t.type === 'procedure').length, icon: <Cog size={16} color="#8B5CF6" /> },
                  { key: 'functions', label: '函数', value: dbObjects.filter((t) => t.type === 'function').length, icon: <FunctionSquare size={16} color="#EC4899" /> },
                  { key: 'triggers', label: '触发器', value: dbObjects.filter((t) => t.type === 'trigger').length, icon: <Zap size={16} color="#F59E0B" /> },
                ] as const

                return (
                  <div style={{ flex: 1, padding: '24px', overflow: 'auto', background: 'transparent' }}>
                    <div style={{ maxWidth: 1180, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
                      <div style={{ ...panelStyle, padding: '18px 20px' }}>
                        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
                          <div>
                            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                              数据库概览
                            </Text>
                            <Text strong style={{ fontSize: 24, display: 'flex', alignItems: 'center', gap: 10, color: token.colorText }}>
                              <Database size={22} color={token.colorPrimary} />
                              {activeTab.database}
                            </Text>
                            <Space size={8} wrap style={{ marginTop: 10 }}>
                              <Tag variant="filled" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
                                {activeTab.connectionName}
                              </Tag>
                              <Tag variant="filled" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
                                对象 {dbObjects.length}
                              </Tag>
                              <Tag variant="filled" style={{ marginInlineEnd: 0, borderRadius: 999, paddingInline: 10 }}>
                                表空间 {formatBytes(totalTableBytes)}
                              </Tag>
                            </Space>
                          </div>
                          <Space size={8} wrap>
                            <Button icon={<ReloadOutlined />} style={quietButtonStyle} onClick={() => loadTables(activeTab.connectionId, activeTab.database)}>
                              刷新对象
                            </Button>
                            {overviewCapabilities.workbench.tableCreate && (
                              <Button icon={<PlusOutlined />} style={quietButtonStyle} onClick={() => openTableDesignerTab(activeTab.connectionId, activeTab.connectionName, activeTab.database)}>
                                新建表
                              </Button>
                            )}
                            <Button type="primary" icon={<CodeOutlined />} onClick={() => openSqlEditor()}>
                              新建查询
                            </Button>
                          </Space>
                        </div>
                      </div>

                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, minmax(0, 1fr))', gap: 12 }}>
                        {objectSummary.map((item) => (
                          <div key={item.key} style={{ ...compactPanelStyle, padding: '16px 18px' }}>
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
                              <Text type="secondary" style={{ fontSize: 12 }}>{item.label}</Text>
                              {item.icon}
                            </div>
                            <Text strong style={{ fontSize: 26, lineHeight: 1 }}>{item.value}</Text>
                          </div>
                        ))}
                      </div>

                      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1.3fr) minmax(320px, 0.9fr)', gap: 16 }}>
                        <div style={{ ...panelStyle, padding: '16px 18px' }}>
                          <Text strong style={{ fontSize: 14, display: 'block', marginBottom: 14 }}>对象分类</Text>
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                            {objectSummary.map((item) => (
                              <div key={item.key} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '10px 12px', borderRadius: token.borderRadius, background: token.colorFillQuaternary }}>
                                <Space size={10}>
                                  {item.icon}
                                  <div>
                                    <Text style={{ display: 'block', fontSize: 13, color: token.colorText }}>{item.label}</Text>
                                    <Text type="secondary" style={{ fontSize: 12 }}>{item.value} 个对象</Text>
                                  </div>
                                </Space>
                                <Button size="small" style={quietButtonStyle} onClick={() => openOrActivateCategoryTab(activeTab.connectionId, activeTab.connectionName, activeTab.database, item.key)}>
                                  查看列表
                                </Button>
                              </div>
                            ))}
                          </div>
                        </div>

                        <div style={{ ...panelStyle, padding: '16px 18px' }}>
                          <Text strong style={{ fontSize: 14, display: 'block', marginBottom: 14 }}>常用操作</Text>
                          <Space direction="vertical" size={10} style={{ width: '100%' }}>
                            <Button block icon={<CodeOutlined />} style={{ ...quietButtonStyle, textAlign: 'left' }} onClick={() => openSqlEditor()}>
                              打开当前库查询窗口
                            </Button>
                            {overviewCapabilities.workbench.tableCreate && (
                              <Button block icon={<PlusOutlined />} style={{ ...quietButtonStyle, textAlign: 'left' }} onClick={() => openTableDesignerTab(activeTab.connectionId, activeTab.connectionName, activeTab.database)}>
                                新建数据表
                              </Button>
                            )}
                            <Button block icon={<ReloadOutlined />} style={{ ...quietButtonStyle, textAlign: 'left' }} onClick={() => loadTables(activeTab.connectionId, activeTab.database)}>
                              重新加载对象元数据
                            </Button>
                          </Space>
                          <div style={{ marginTop: 16, paddingTop: 16, borderTop: '1px solid var(--glass-border)' }}>
                            <Text type="secondary" style={{ fontSize: 12, lineHeight: 1.7 }}>
                              工作台页优先服务浏览、查询和结构维护，因此这里保留紧凑摘要和高频动作，不再使用展示感过强的发光卡片。
                            </Text>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                )
              }

              // ===== 分类列表 Tab =====
              if (activeTab.type === 'category-list') {
                return (
                  <div style={{ flex: 1, overflow: 'hidden' }}>
                    <CategoryListView
                      connectionId={activeTab.connectionId}
                      database={activeTab.database}
                      category={activeTab.category}
                      objects={objectsMap[`${activeTab.connectionId}::${activeTab.database}`] || []}
                      objectCategories={objectCategories}
                      search={activeTab.categorySearch || ''}
                      onSearchChange={(value) => {
                        const key = tabKey
                        setOpenTableTabs((prev) => ({
                          ...prev,
                          [key]: { ...prev[key], categorySearch: value } as WorkbenchTab,
                        }))
                      }}
                      onSelectObject={(name) => {
                        // 在概览页中查找对象类型
                        const oKey = `${activeTab.connectionId}::${activeTab.database}`
                        const oList = objectsMap[oKey] || []
                        const oInfo = oList.find(o => o.name === name)
                        const oType = (oInfo?.type ?? 'table') as 'table' | 'view' | 'procedure' | 'function' | 'trigger'
                        const oDefaultTab = (oType === 'table' || oType === 'view') ? 'data' : 'ddl'
                        openOrActivateTab(activeTab.connectionId, activeTab.connectionName, activeTab.database, name, oDefaultTab, oType)
                      }}
                    />
                  </div>
                )
              }

              // ===== SQL 编辑器 Tab =====
              if (activeTab.type === 'sql-query') {
                return (
                  <div style={{ flex: 1, overflow: 'hidden' }}>
                    <QueryEditorPane
                      queryId={activeTab.queryId}
                      active={isActivePane}
                      onOpenObjectDetail={handleOpenObjectDetailFromQuery}
                      onContextChange={handleQueryContextChange}
                    />
                  </div>
                )
              }

              // ===== 表设计器 Tab =====
              if (activeTab.type === 'table-designer') {
                return (
                  <div style={{ flex: 1, overflow: 'hidden' }}>
                    <TableDesigner
                      connectionId={activeTab.connectionId}
                      connectionName={activeTab.connectionName}
                      database={activeTab.database}
                      editTableName={activeTab.tableName}
                      dbType={openConnections.find(c => c.id === activeTab.connectionId)?.dbType}
                      onSuccess={(result: TableDesignerSaveResult) => {
                        if (result.renamed && result.previousTableName) {
                          applyRenamedTableState(
                            activeTab.connectionId,
                            activeTab.database,
                            result.previousTableName,
                            result.tableName,
                          )
                        }
                        loadTables(activeTab.connectionId, activeTab.database)
                        closeTableTab(tabKey)
                      }}
                      onCancel={() => closeTableTab(tabKey)}
                    />
                  </div>
                )
              }

              return null
                    })()}
                  </div>
                )
              })}
            </div>
          </div>
        ) : (
          <EmptyState
            description={
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16 }}>
                <div>{openConnections.length === 0 ? '从左侧添加连接开始浏览' : '从左侧选择数据库、对象或脚本开始工作'}</div>
                <div style={{
                  display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: token.colorTextSecondary
                }}>
                  或按 <kbd style={{ padding: '2px 6px', background: token.colorFillSecondary, border: '1px solid var(--glass-border)', borderRadius: 4, fontFamily: 'monospace', color: token.colorText }}>{formatHotkey(['Cmd', 'K'])}</kbd> 唤起全局命令
                </div>
                {openConnections.length > 0 && (
                  <Space size={16} style={{ marginTop: 8 }}>
                    <Button type="primary" icon={<CodeOutlined />} onClick={() => openSqlEditor()}>新建查询</Button>
                    <Button icon={<Search size={14} />} onClick={() => useCommandStore.getState().toggleOpen()}>全局搜索</Button>
                    <Button type="link" onClick={() => setShowShortcuts(true)}>查看快捷键</Button>
                  </Space>
                )}
              </div>
            }
          />
        )}
      </Content>

      <Modal
        open={!!renameTableTarget}
        title={renameTableTarget ? `重命名表「${renameTableTarget.tableName}」` : '重命名表'}
        okText="确定"
        cancelText="取消"
        width={420}
        centered
        confirmLoading={renamingTable}
        okButtonProps={{
          disabled: !renameTableTarget || !renameTableInput.trim() || renameTableInput.trim() === renameTableTarget.tableName,
        }}
        onOk={handleRenameTableSubmit}
        onCancel={closeRenameTableModal}
      >
        <Space direction="vertical" size={8} style={{ width: '100%', paddingTop: 4 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>新表名</Text>
          <Input
            value={renameTableInput}
            onChange={(e) => setRenameTableInput(e.target.value)}
            onPressEnter={handleRenameTableSubmit}
            placeholder="输入新表名"
            autoFocus
            disabled={renamingTable}
          />
        </Space>
      </Modal>

      {/* 新建数据库弹窗 */}
      {createDbModal && (
        <CreateDatabaseModal
          open={!!createDbModal}
          connectionId={createDbModal.connectionId}
          connectionName={createDbModal.connectionName}
          dbType={createDbModal.dbType}
          onClose={() => setCreateDbModal(null)}
          onSuccess={() => loadDatabases(createDbModal.connectionId)}
        />
      )}

      {/* 编辑数据库弹窗 */}
      {editDbModal && (
        <EditDatabaseModal
          open={!!editDbModal}
          connectionId={editDbModal.connectionId}
          databaseName={editDbModal.databaseName}
          onClose={() => setEditDbModal(null)}
          onSuccess={() => loadDatabases(editDbModal.connectionId)}
        />
      )}

      {/* SQL 文件导入弹窗 */}
      {importSqlModal && (
        <ImportSqlDialog
          open={!!importSqlModal}
          connectionId={importSqlModal.connectionId}
          connectionName={importSqlModal.connectionName}
          database={importSqlModal.database}
          dbType={importSqlModal.dbType}
          databases={(databasesMap[importSqlModal.connectionId] || []).map(d => d.name)}
          onClose={() => setImportSqlModal(null)}
        />
      )}
      {exportModal && (<ExportDatabaseModal
        open={true}
        onClose={() => setExportModal(null)}
        {...exportModal}
      />)}

      {backupModal && (
        <BackupDatabaseModal
          open={true}
          onClose={() => setBackupModal(null)}
          connectionId={backupModal.connectionId}
          connectionName={backupModal.connectionName}
          database={backupModal.database}
          dbType={backupModal.dbType}
        />
      )}

      {restoreModal && (
        <RestoreDatabaseModal
          open={true}
          onClose={() => setRestoreModal(null)}
          targetConnectionId={restoreModal.connectionId}
          targetConnectionName={restoreModal.connectionName}
          defaultTargetDatabase={restoreModal.database}
          dbType={restoreModal.dbType}
        />
      )}

      {/* 快捷键查看弹窗 */}
      <ShortcutsModal
        open={showShortcuts}
        onCancel={() => setShowShortcuts(false)}
      />

      {/* 存储过程/函数执行弹窗 */}
      {callProcedureTarget && (
        <CallProcedurePanel
          target={callProcedureTarget}
          onClose={() => setCallProcedureTarget(null)}
        />
      )}
    </Layout>

    {/* 树节点右键菜单 - 放在 Layout 外部确保 fixed 定位生效 */}
    {treeCtxMenu && (
      <>
        <div
          style={{ position: 'fixed', inset: 0, zIndex: 999 }}
          onClick={() => setTreeCtxMenu(null)}
          onContextMenu={(e) => { e.preventDefault(); setTreeCtxMenu(null) }}
        />
        <div style={{
          position: 'fixed', left: treeCtxMenu.x, top: treeCtxMenu.y, zIndex: 1000,
          background: 'var(--glass-popup)',
          backdropFilter: 'var(--glass-blur-heavy)',
          WebkitBackdropFilter: 'var(--glass-blur-heavy)',
          borderRadius: token.borderRadiusLG,
          border: '1px solid var(--glass-border)',
          boxShadow: 'var(--glass-shadow-lg), var(--glass-inner-glow)',
          padding: '6px 0', minWidth: 180,
        }}>
          {/* eslint-disable-next-line @typescript-eslint/no-explicit-any */}
          {(treeCtxMenuItems || []).map((item: any, idx: number) => {
            if (!item) return null
            if (item.type === 'divider') return <div key={`divider-${idx}`} style={{ height: 1, background: 'var(--glass-border)', margin: '4px 8px' }} />
            return (
              <div
                key={String(item.key)}
                style={{
                  padding: '5px 12px', cursor: 'pointer', fontSize: 13,
                  color: item.danger ? 'var(--edb-error)' : 'var(--edb-text-primary)',
                  display: 'flex', alignItems: 'center', gap: 8,
                  transition: 'background 0.15s',
                }}
                onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'var(--glass-panel-hover)' }}
                onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                onClick={() => {
                  if (item.onClick) {
                    item.onClick({
                      key: String(item.key),
                      keyPath: [String(item.key)],
                      domEvent: new MouseEvent('click'),
                    } as unknown as Parameters<NonNullable<typeof item.onClick>>[0])
                  }
                  setTreeCtxMenu(null)
                }}
              >
                {item.icon && <span style={{ display: 'flex', alignItems: 'center' }}>{item.icon}</span>}
                {item.label}
              </div>
            )
          })}
        </div>
      </>
    )}
  </>
  )
}
