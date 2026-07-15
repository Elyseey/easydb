import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import type { ConnectionConfig } from '@/types'
import { useConnectionStore } from '@/stores/connectionStore'
import { MigrationPage } from '..'

const apiMocks = vi.hoisted(() => ({
  listConnections: vi.fn(),
  openConnection: vi.fn(),
  listDatabases: vi.fn(),
  listObjects: vi.fn(),
  startMigration: vi.fn(),
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
  migrationApi: {
    start: apiMocks.startMigration,
  },
}))

vi.mock('@/utils/notification', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
  handleApiError: vi.fn(),
}))

const connection = (
  id: string,
  name: string,
  dbType: ConnectionConfig['dbType'],
): ConnectionConfig => ({
  id,
  name,
  dbType,
  host: '127.0.0.1',
  port: dbType === 'dameng' ? 5236 : 3306,
  username: 'tester',
  password: '',
  status: 'connected',
})

describe('MigrationPage connection selection', () => {
  beforeEach(() => {
    useConnectionStore.setState({
      connections: [
        connection('dm-1', '达梦生产库', 'dameng'),
        connection('mysql-1', 'MySQL 生产库', 'mysql'),
        connection('mysql-2', 'MySQL 测试库', 'mysql'),
      ],
    })
  })

  it('renders source connections in database type groups', async () => {
    render(
      <MemoryRouter>
        <MigrationPage />
      </MemoryRouter>,
    )

    fireEvent.mouseDown(screen.getAllByRole('combobox')[0])

    expect(await screen.findByText('MySQL (2)')).toBeInTheDocument()
    expect(screen.getByText('达梦 (1)')).toBeInTheDocument()
    expect(screen.getByText(/MySQL 生产库/)).toBeInTheDocument()
    expect(screen.getByText(/达梦生产库/)).toBeInTheDocument()
  })
})
