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
import { Layout, Menu, Breadcrumb, Button, Tooltip } from 'antd'
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
  GithubOutlined,
} from '@ant-design/icons'
import { useWorkbenchStore } from '@/stores/workbenchStore'
import { useCommandStore } from '@/stores/commandStore'
import { useThemeStore } from '@/stores/themeStore'
import { CommandPalette } from '@/components/CommandPalette'
import { GITHUB_REPOSITORY_URL } from '@/utils/updater'
import { openExternalUrl } from '@/utils/openExternalUrl'

const { Sider, Content, Header } = Layout

interface NavigationItem {
  key: string
  commandId: string
  icon: React.ReactNode
  label: string
}

const allMenuItems: NavigationItem[] = [
  { key: '/connection', commandId: 'nav-conn', icon: <ApiOutlined />, label: '连接管理' },
  { key: '/workbench', commandId: 'nav-wb', icon: <DatabaseOutlined />, label: '工作台' },
  { key: '/migration', commandId: 'nav-mig', icon: <SwapOutlined />, label: '数据迁移' },
  { key: '/sync', commandId: 'nav-sync', icon: <SyncOutlined />, label: '数据同步' },
  { key: '/structure-compare', commandId: 'nav-comp', icon: <DiffOutlined />, label: '结构对比' },
  { key: '/task-center', commandId: 'nav-task', icon: <UnorderedListOutlined />, label: '任务中心' },
  { key: '/data-tracker', commandId: 'nav-tracker', icon: <ThunderboltOutlined />, label: '数据追踪' },
  { key: '/slow-query', commandId: 'nav-slow-query', icon: <SearchOutlined />, label: '慢查询分析' },
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

  const siderCollapsed = useWorkbenchStore((s) => s.siderCollapsed)
  const setSiderCollapsed = useWorkbenchStore((s) => s.setSiderCollapsed)

  const currentTitle = pageTitle[location.pathname] ?? ''

  const menuItems = allMenuItems.map(({ key, icon, label }) => ({ key, icon, label }))

  useEffect(() => {
    const navigationCommands = allMenuItems
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
  }, [navigate, themeMode, themeStyle, setThemeMode, setThemeStyle, registerCommand, unregisterCommand])

  const breadcrumbItems = [{ title: currentTitle }]

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
          style={{
            borderRight: 0,
            paddingTop: 8,
            paddingBottom: siderCollapsed ? 96 : 56,
          }}
          inlineCollapsed={siderCollapsed}
        />

        {/* 侧栏底部操作区 */}
        <div
          style={{
            position: 'absolute',
            bottom: 0,
            left: 0,
            right: 0,
            display: 'flex',
            flexDirection: siderCollapsed ? 'column' : 'row',
            alignItems: 'center',
            gap: 4,
            padding: 8,
            borderTop: '1px solid var(--edb-border-subtle)',
            background: 'var(--glass-panel)',
          }}
        >
          <Tooltip title="GitHub 开源仓库 · 欢迎 Star" placement="right">
            <Button
              type="text"
              icon={<GithubOutlined />}
              aria-label="打开 EasyDB GitHub 仓库"
              onClick={() => void openExternalUrl(GITHUB_REPOSITORY_URL)}
              style={{
                width: siderCollapsed ? 36 : 'auto',
                flex: siderCollapsed ? 'none' : 1,
                height: 36,
                justifyContent: siderCollapsed ? 'center' : 'flex-start',
                color: 'var(--edb-text-secondary)',
              }}
            >
              {siderCollapsed ? null : 'GitHub'}
            </Button>
          </Tooltip>

          <Tooltip title={siderCollapsed ? '展开侧栏' : '收起侧栏'} placement="right">
            <Button
              type="text"
              icon={siderCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              aria-label={siderCollapsed ? '展开侧栏' : '收起侧栏'}
              onClick={() => setSiderCollapsed(!siderCollapsed)}
              style={{
                width: 36,
                height: 36,
                flex: 'none',
                color: 'var(--edb-text-secondary)',
              }}
            />
          </Tooltip>
        </div>
      </Sider>

      <Layout>
        {/* 顶部页面标题栏 */}
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
            zIndex: 9,
          }}
        >
          <Breadcrumb items={breadcrumbItems} />
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
