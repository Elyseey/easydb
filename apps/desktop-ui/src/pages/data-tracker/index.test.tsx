import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionConfig } from '@/types'
import { DataTrackerPage } from '.'

const apiMocks = vi.hoisted(() => ({
  listConnections: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  connectionApi: {
    list: apiMocks.listConnections,
  },
}))

vi.mock('@/services/trackerApi', () => ({
  trackerApi: {},
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

describe('DataTrackerPage connection capabilities', () => {
  beforeEach(() => {
    apiMocks.listConnections.mockResolvedValue([
      connection('mysql-1', 'mysql'),
      connection('dameng-1', 'dameng'),
    ])
  })

  it('lists only connections with the data-tracker diagnostic capability', async () => {
    render(<DataTrackerPage />)

    await waitFor(() => expect(apiMocks.listConnections).toHaveBeenCalled())
    fireEvent.mouseDown(screen.getAllByRole('combobox')[0])

    expect(await screen.findByText('MySQL mysql-1 (127.0.0.1:3306)')).toBeInTheDocument()
    expect(screen.queryByText('达梦 dameng-1 (127.0.0.1:5236)')).not.toBeInTheDocument()
  })
})
