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
import React, { useEffect } from 'react'
import { Layout, Menu, Typography, Space, Breadcrumb } from 'antd'
import { useNavigate, useLocation } from 'react-router-dom'
import {
  ApiOutlined,
  DatabaseOutlined,
  SwapOutlined,
  SyncOutlined,
  DiffOutlined,
  UnorderedListOutlined,
  SettingOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlayCircleOutlined,
  BgColorsOutlined,
  ThunderboltOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { useWorkbenchStore } from '@/stores/workbenchStore'
import { useCommandStore } from '@/stores/commandStore'
import { useThemeStore } from '@/stores/themeStore'
import { ConnectionStatusTag } from '@/components/StatusTag'
import { CommandPalette } from '@/components/CommandPalette'
import { supportsDatabaseNavigation } from '@/utils/databaseNavigation'
import type { DatabaseNavigationCapability } from '@/utils/databaseNavigation'

const { Sider, Content, Header } = Layout
const { Text } = Typography

interface NavigationItem {
  key: string
  commandId: string
  icon: React.ReactNode
  label: string
  capability?: DatabaseNavigationCapability
}

const allMenuItems: NavigationItem[] = [
  { key: '/connection', commandId: 'nav-conn', icon: <ApiOutlined />, label: '连接管理' },
  { key: '/workbench', commandId: 'nav-wb', icon: <DatabaseOutlined />, label: '工作台' },
  { key: '/migration', commandId: 'nav-mig', icon: <SwapOutlined />, label: '数据迁移', capability: 'migration' },
  { key: '/sync', commandId: 'nav-sync', icon: <SyncOutlined />, label: '数据同步', capability: 'sync' },
  { key: '/structure-compare', commandId: 'nav-comp', icon: <DiffOutlined />, label: '结构对比', capability: 'structureCompare' },
  { key: '/task-center', commandId: 'nav-task', icon: <UnorderedListOutlined />, label: '任务中心' },
  { key: '/data-tracker', commandId: 'nav-tracker', icon: <ThunderboltOutlined />, label: '数据追踪', capability: 'dataTracker' },
  { key: '/slow-query', commandId: 'nav-slow-query', icon: <SearchOutlined />, label: '慢查询分析', capability: 'slowQuery' },
  { key: '/settings', commandId: 'nav-sett', icon: <SettingOutlined />, label: '设置' },
]

const pageTitle: Record<string, string> = {
  '/connection': '连接管理',
  '/workbench': '工作台',
  '/migration': '数据迁移',
  '/sync': '数据同步',
  '/structure-compare': '结构对比',
  '/task-center': '任务中心',
  '/data-tracker': '数据追踪',
  '/slow-query': '慢查询分析',
  '/settings': '设置',
}

interface MainLayoutProps {
  children: React.ReactNode
}

export const MainLayout: React.FC<MainLayoutProps> = ({ children }) => {
  const navigate = useNavigate()
  const location = useLocation()

  const themeMode = useThemeStore((s) => s.themeMode)
  const setThemeMode = useThemeStore((s) => s.setThemeMode)
  const themeStyle = useThemeStore((s) => s.themeStyle)
  const setThemeStyle = useThemeStore((s) => s.setThemeStyle)
  const registerCommand = useCommandStore((s) => s.registerCommand)
  const unregisterCommand = useCommandStore((s) => s.unregisterCommand)

  const activeConnectionName = useWorkbenchStore((s) => s.activeConnectionName)
  const activeDbType = useWorkbenchStore((s) => s.activeDbType)
  const activeDatabase = useWorkbenchStore((s) => s.activeDatabase)
  const siderCollapsed = useWorkbenchStore((s) => s.siderCollapsed)
  const setSiderCollapsed = useWorkbenchStore((s) => s.setSiderCollapsed)

  const currentTitle = pageTitle[location.pathname] ?? ''

  const availableNavigationItems = allMenuItems.filter(
    item => !item.capability || supportsDatabaseNavigation(activeDbType, item.capability)
  )
  const menuItems = availableNavigationItems
    .map(({ key, icon, label }) => ({ key, icon, label }))

  useEffect(() => {
    const navigationCommands = allMenuItems
      .filter(item => !item.capability || supportsDatabaseNavigation(activeDbType, item.capability))
      .map(item => ({
        id: item.commandId,
        title: `前往 ${item.label}`,
        category: 'Navigation',
        icon: item.icon,
        action: () => navigate(item.key),
      }))
    const defaultCommands = [
      ...navigationCommands,
      { id: 'theme-toggle', title: '切换 深色/浅色 主题', category: 'Preferences', icon: <BgColorsOutlined />, action: () => setThemeMode(themeMode === 'dark' ? 'light' : 'dark') },
      { id: 'theme-style-toggle', title: '切换 简约专业/流光 风格', category: 'Preferences', icon: <BgColorsOutlined />, action: () => setThemeStyle(themeStyle === 'professional' ? 'glass' : 'professional') },
      { id: 'run-sql', title: '执行选中的 SQL (如果可用)', category: 'Editor', icon: <PlayCircleOutlined />, shortcut: ['Cmd', 'Enter'], action: () => {
          document.dispatchEvent(new CustomEvent('easydb-run-sql'))
      }}
    ]

    defaultCommands.forEach(registerCommand)
    
    return () => {
      defaultCommands.forEach(c => unregisterCommand(c.id))
    }
  }, [activeDbType, navigate, themeMode, themeStyle, setThemeMode, setThemeStyle, registerCommand, unregisterCommand])

  // 面包屑项
  const breadcrumbItems = [
    { title: currentTitle },
    ...(activeConnectionName ? [{ title: activeConnectionName }] : []),
    ...(activeDatabase ? [{ title: activeDatabase }] : []),
  ]

  return (
    <Layout style={{ height: '100vh', overflow: 'hidden' }}>
      {/* 左侧导航 */}
      <Sider
        width={200}
        collapsedWidth={56}
        collapsed={siderCollapsed}
        style={{
          background: 'var(--glass-panel)',
          backdropFilter: 'var(--glass-blur)',
          WebkitBackdropFilter: 'var(--glass-blur)',
          borderRight: '1px solid var(--glass-border)',
          boxShadow: 'var(--glass-shadow), var(--glass-inner-glow)',
          zIndex: 10,
        }}
      >
        {/* Logo */}
        <div
          style={{
            height: 52,
            display: 'flex',
            alignItems: 'center',
            justifyContent: siderCollapsed ? 'center' : 'flex-start',
            paddingLeft: siderCollapsed ? 0 : 20,
            margin: '10px 10px 6px',
            borderRadius: 'var(--edb-radius-md)',
            background: 'var(--glass-panel)',
            border: '1px solid var(--glass-border)',
            boxShadow: 'var(--glass-inner-glow)',
            fontWeight: 700,
            fontSize: siderCollapsed ? 14 : 16,
            color: 'var(--edb-accent)',
            letterSpacing: 1.5,
            cursor: 'pointer',
            transition: 'all var(--edb-transition-normal)',
            textShadow: '0 0 20px var(--edb-accent-muted)',
          }}
          onClick={() => navigate('/connection')}
        >
          {siderCollapsed ? 'E' : 'EasyDB'}
        </div>

        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ borderRight: 0, paddingTop: 8 }}
          inlineCollapsed={siderCollapsed}
        />

        {/* 折叠按钮 */}
        <div
          style={{
            position: 'absolute',
            bottom: 16,
            left: '50%',
            transform: 'translateX(-50%)',
            width: 36,
            height: 36,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: 'pointer',
            color: 'var(--edb-text-muted)',
            borderRadius: 'var(--edb-radius-md)',
            background: 'var(--glass-panel)',
            border: '1px solid var(--glass-border)',
            boxShadow: 'var(--glass-inner-glow)',
            backdropFilter: 'var(--glass-blur-sm)',
            transition: 'all var(--edb-transition-fast)',
          }}
          onClick={() => setSiderCollapsed(!siderCollapsed)}
          onMouseEnter={(e) => {
            e.currentTarget.style.background = 'var(--glass-panel-hover)'
            e.currentTarget.style.color = 'var(--edb-text-primary)'
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.background = 'var(--glass-panel)'
            e.currentTarget.style.color = 'var(--edb-text-muted)'
          }}
        >
          {siderCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
        </div>
      </Sider>

      <Layout>
        {/* 顶部上下文栏 */}
        <Header
          style={{
            height: 48,
            lineHeight: '48px',
            background: 'var(--glass-panel)',
            backdropFilter: 'var(--glass-blur)',
            WebkitBackdropFilter: 'var(--glass-blur)',
            borderBottom: '1px solid var(--glass-border)',
            boxShadow: 'var(--glass-inner-glow)',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            zIndex: 9,
          }}
        >
          <Breadcrumb items={breadcrumbItems} />

          {/* 右侧：当前连接状态 */}
          <Space size={12}>
            {activeConnectionName ? (
              <>
                <Text type="secondary" style={{ fontSize: 13 }}>
                  <DatabaseOutlined style={{ marginRight: 4 }} />
                  {activeConnectionName}
                  {activeDatabase && ` / ${activeDatabase}`}
                </Text>
                <ConnectionStatusTag status="connected" />
              </>
            ) : (
              <Text type="secondary" style={{ fontSize: 13 }}>
                未连接
              </Text>
            )}
          </Space>
        </Header>

        {/* 主内容区 */}
        <Content
          style={{
            height: '100%',
            overflow: 'auto',
            background: 'var(--edb-bg-base)',
          }}
        >
          {children}
        </Content>
      </Layout>
      <CommandPalette />
    </Layout>
  )
}
