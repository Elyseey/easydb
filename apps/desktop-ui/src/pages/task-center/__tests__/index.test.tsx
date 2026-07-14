import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaskInfo } from '@/types'
import { useTaskStore } from '@/stores/taskStore'
import { TaskCenterPage } from '..'

const taskApiMock = vi.hoisted(() => ({
  list: vi.fn(),
  logs: vi.fn(),
  cancel: vi.fn(),
  delete: vi.fn(),
  clearCompleted: vi.fn(),
  downloadLog: vi.fn(),
}))

vi.mock('@/services/api', () => ({ taskApi: taskApiMock }))

const migrationTask: TaskInfo = {
  id: 'migration-1',
  name: '迁移 energy → ENERGY',
  type: 'migration',
  status: 'failed',
  progress: 100,
  sourceEndpoint: {
    connectionId: 'source-id',
    connectionName: '生产 MySQL',
    dbType: 'mysql',
    host: '10.0.0.8',
    port: 3306,
    database: 'energy',
  },
  targetEndpoint: {
    connectionId: 'target-id',
    connectionName: '达梦测试',
    dbType: 'dameng',
    host: '10.0.0.9',
    port: 5236,
    database: 'ENERGY',
  },
}

const legacyTask: TaskInfo = {
  id: 'legacy-1',
  name: '迁移 legacy → legacy_copy',
  type: 'migration',
  status: 'completed',
  progress: 100,
}

describe('TaskCenterPage', () => {
  beforeEach(() => {
    taskApiMock.list.mockResolvedValue([migrationTask, legacyTask])
    useTaskStore.setState({
      tasks: [migrationTask, legacyTask],
      selectedTaskId: migrationTask.id,
      taskLogs: {},
      taskSteps: {},
    })
  })

  it('shows endpoint snapshots and allows the details panel to close', async () => {
    render(<TaskCenterPage />)

    expect(screen.getAllByText('任务名称').length).toBeGreaterThan(0)
    expect(screen.getByText('energy → ENERGY')).toBeInTheDocument()
    expect(screen.getByText('生产 MySQL → 达梦测试')).toBeInTheDocument()
    expect(screen.getAllByText(/10\.0\.0\.8:3306/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/10\.0\.0\.9:5236/).length).toBeGreaterThan(0)
    expect(screen.getByText('迁移 legacy → legacy_copy')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '关闭任务详情' }))

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '关闭任务详情' })).not.toBeInTheDocument()
    })
    expect(screen.getByText('energy → ENERGY')).toBeInTheDocument()
    expect(screen.getByText('生产 MySQL → 达梦测试')).toBeInTheDocument()
  })

  it('searches tasks by endpoint host', async () => {
    render(<TaskCenterPage />)
    fireEvent.change(screen.getByPlaceholderText('搜索任务...'), { target: { value: '10.0.0.9' } })

    await waitFor(() => {
      expect(screen.queryByText('迁移 legacy → legacy_copy')).not.toBeInTheDocument()
      expect(screen.getAllByText(/10\.0\.0\.9:5236/).length).toBeGreaterThan(0)
    })
  })
})
