import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionConfig } from '@/types'
import { SlowQueryPage } from '.'

const apiMocks = vi.hoisted(() => ({
  listConnections: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  connectionApi: {
    list: apiMocks.listConnections,
  },
  metadataApi: {
    databases: vi.fn(),
  },
}))

vi.mock('@/services/slowQueryApi', () => ({
  slowQueryApi: {},
}))

const connection = (
  id: string,
  dbType: ConnectionConfig['dbType'],
): ConnectionConfig => ({
  id,
  name: `${dbType === 'dameng' ? '达梦' : 'MySQL'} ${id}`,
  dbType,
  host: '127.0.0.1',
  port: dbType === 'dameng' ? 5236 : 3306,
  username: 'tester',
  password: '',
  status: 'disconnected',
})

describe('SlowQueryPage connection capabilities', () => {
  beforeEach(() => {
    apiMocks.listConnections.mockResolvedValue([
      connection('mysql-1', 'mysql'),
      connection('dameng-1', 'dameng'),
    ])
  })

  it('lists only connections with the slow-query diagnostic capability', async () => {
    render(<SlowQueryPage />)

    await waitFor(() => expect(apiMocks.listConnections).toHaveBeenCalled())
    fireEvent.mouseDown(screen.getAllByRole('combobox')[0])

    expect(await screen.findByText('MySQL mysql-1')).toBeInTheDocument()
    expect(screen.queryByText('达梦 dameng-1')).not.toBeInTheDocument()
  })
})
