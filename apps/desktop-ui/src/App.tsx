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
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ConfigProvider, theme, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { MainLayout } from '@/layouts/MainLayout'
import { ConnectionPage } from '@/pages/connection'
import { WorkbenchPage } from '@/pages/workbench'
import { MigrationPage } from '@/pages/migration'
import { SyncPage } from '@/pages/sync'
import { TaskCenterPage } from '@/pages/task-center'
import { SettingsPage } from '@/pages/settings'
import { StructureComparePage } from '@/pages/structure-compare'
import { DataTrackerPage } from '@/pages/data-tracker'
import { SlowQueryPage } from '@/pages/slow-query'
import { checkForUpdate, getAutoCheckEnabled } from '@/utils/updater'
import { useThemeStore } from '@/stores/themeStore'
import { getEasyDbThemeConfig } from '@/theme/themeConfig'
import { configureFeedbackApis } from '@/utils/notification'
import { openExternalUrl } from '@/utils/openExternalUrl'

const AppContent: React.FC = () => {
  const { message, notification, modal } = AntApp.useApp()

  configureFeedbackApis({ message, notification })

  // 启动时自动检查更新
  useEffect(() => {
    if (!getAutoCheckEnabled()) return
    // 延迟 3 秒检查，避免阻塞启动
    const timer = setTimeout(async () => {
      try {
        const info = await checkForUpdate()
        if (info.hasUpdate) {
          modal.confirm({
            title: `发现新版本 v${info.latestVersion}`,
            content: (
              <div>
                <p>当前版本：v{info.currentVersion}</p>
                {info.releaseNotes && (
                  <p style={{ fontSize: 12, color: 'var(--edb-text-secondary)', maxHeight: 120, overflow: 'auto' }}>
                    {info.releaseNotes.slice(0, 300)}
                  </p>
                )}
              </div>
            ),
            okText: '前往下载',
            cancelText: '稍后再说',
            onOk: () => {
              void openExternalUrl(info.downloadUrl)
            },
          })
        }
      } catch {
        // 静默失败，不打扰用户
      }
    }, 3000)
    return () => clearTimeout(timer)
  }, [modal])

  return (
    <BrowserRouter>
      <MainLayout>
        <Routes>
          <Route path="/" element={<Navigate to="/connection" replace />} />
          <Route path="/connection" element={<ConnectionPage />} />
          <Route path="/workbench" element={<WorkbenchPage />} />
          <Route path="/sql-editor" element={<Navigate to="/workbench" replace />} />
          <Route path="/migration" element={<MigrationPage />} />
          <Route path="/sync" element={<SyncPage />} />
          <Route path="/task-center" element={<TaskCenterPage />} />
          <Route path="/structure-compare" element={<StructureComparePage />} />
          <Route path="/data-tracker" element={<DataTrackerPage />} />
          <Route path="/slow-query" element={<SlowQueryPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </MainLayout>
    </BrowserRouter>
  )
}

const App: React.FC = () => {
  const effectiveTheme = useThemeStore((s) => s.effectiveTheme)
  const themeStyle = useThemeStore((s) => s.themeStyle)
  const isDark = effectiveTheme === 'dark'
  const antdTheme = getEasyDbThemeConfig(
    themeStyle,
    effectiveTheme,
    isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
  )

  return (
    <ConfigProvider
      locale={zhCN}
      theme={antdTheme}>
      <AntApp>
        <AppContent />
      </AntApp>
    </ConfigProvider>
  )
}

export default App
