import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { useWorkbenchStore } from '@/stores/workbenchStore'
import { MainLayout } from './MainLayout'

describe('MainLayout header', () => {
  beforeEach(() => {
    useWorkbenchStore.setState({
      activeConnectionId: 'stale-connection',
      activeConnectionName: '历史连接',
      activeDbType: 'mysql',
      activeDatabase: 'legacy_database',
      siderCollapsed: false,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows only the page title and does not leak workbench connection state', () => {
    render(
      <MemoryRouter initialEntries={['/settings']}>
        <MainLayout>
          <div>设置内容</div>
        </MainLayout>
      </MemoryRouter>,
    )

    const header = screen.getByRole('banner')
    expect(within(header).getByText('设置')).toBeInTheDocument()
    expect(within(header).queryByText('历史连接')).not.toBeInTheDocument()
    expect(within(header).queryByText('legacy_database')).not.toBeInTheDocument()
    expect(within(header).queryByText('已连接')).not.toBeInTheDocument()
    expect(within(header).queryByText('未连接')).not.toBeInTheDocument()
  })

  it('opens the official GitHub repository from the sidebar shortcut', () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)

    render(
      <MemoryRouter initialEntries={['/settings']}>
        <MainLayout>
          <div>设置内容</div>
        </MainLayout>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '打开 EasyDB GitHub 仓库' }))

    expect(openSpy).toHaveBeenCalledWith(
      'https://github.com/qingwz1994/easydb',
      '_blank',
      'noopener,noreferrer',
    )
  })

  it('keeps global database tools visible when the workbench database is Dameng', () => {
    useWorkbenchStore.setState({ activeDbType: 'dameng' })

    render(
      <MemoryRouter initialEntries={['/settings']}>
        <MainLayout>
          <div>设置内容</div>
        </MainLayout>
      </MemoryRouter>,
    )

    expect(screen.getByText('数据迁移')).toBeInTheDocument()
    expect(screen.getByText('数据同步')).toBeInTheDocument()
    expect(screen.getByText('结构对比')).toBeInTheDocument()
    expect(screen.getByText('数据追踪')).toBeInTheDocument()
    expect(screen.getByText('慢查询分析')).toBeInTheDocument()
  })

  it('exposes the sidebar collapse action as an accessible button', () => {
    render(
      <MemoryRouter initialEntries={['/settings']}>
        <MainLayout>
          <div>设置内容</div>
        </MainLayout>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '收起侧栏' }))

    expect(useWorkbenchStore.getState().siderCollapsed).toBe(true)
    expect(screen.getByRole('button', { name: '展开侧栏' })).toBeInTheDocument()
  })
})
