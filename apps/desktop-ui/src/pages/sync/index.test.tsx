import { render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import type { ConnectionConfig } from '@/types'
import { useConnectionStore } from '@/stores/connectionStore'
import { SyncPage } from '.'

const apiMocks = vi.hoisted(() => ({
  listConnections: vi.fn(),
  openConnection: vi.fn(),
  listDatabases: vi.fn(),
  listObjects: vi.fn(),
  startSync: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  connectionApi: {
    list: apiMocks.listConnections,
    open: apiMocks.openConnection,
  },
  metadataApi: {
    databases: apiMocks.listDatabases,
    objects: apiMocks.listObjects,
  },
  syncApi: {
    start: apiMocks.startSync,
  },
}))

vi.mock('@/utils/notification', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
  handleApiError: vi.fn(),
}))

const connection = (id: string): ConnectionConfig => ({
  id,
  name: `MySQL ${id}`,
  dbType: 'mysql',
  host: '127.0.0.1',
  port: 3306,
  username: 'tester',
  password: '',
  status: 'connected',
})

describe('SyncPage theme surfaces', () => {
  beforeEach(() => {
    useConnectionStore.setState({
      connections: [connection('source'), connection('target')],
    })
  })

  it('uses the stable shared database task surfaces', () => {
    const { container } = render(
      <MemoryRouter>
        <SyncPage />
      </MemoryRouter>,
    )

    expect(container.querySelector('.database-task-page')).toBeInTheDocument()
    expect(container.querySelectorAll('.database-task-endpoint-card')).toHaveLength(2)
    expect(container.querySelector('.database-task-connector')).toBeInTheDocument()
    expect(container.querySelector('.database-task-page__footer')).toBeInTheDocument()
    expect(container.querySelector('.ant-card-hoverable')).not.toBeInTheDocument()
  })
})
