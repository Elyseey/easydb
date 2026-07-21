import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionConfig } from '@/types'
import { DataTrackerPage } from '.'

const apiMocks = vi.hoisted(() => ({
  listConnections: vi.fn(),
  serverCheck: vi.fn(),
  start: vi.fn(),
  history: vi.fn(),
  createEventSource: vi.fn(),
  status: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  connectionApi: {
    list: apiMocks.listConnections,
  },
}))

vi.mock('@/services/trackerApi', () => ({
  trackerApi: {
    serverCheck: apiMocks.serverCheck,
    start: apiMocks.start,
    history: apiMocks.history,
    createEventSource: apiMocks.createEventSource,
    status: apiMocks.status,
  },
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
    vi.clearAllMocks()
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

  it('automatically loads the first page only once for each tracker session', async () => {
    const eventSource = {
      onmessage: null as ((event: MessageEvent<string>) => void) | null,
      onerror: null as (() => void) | null,
      close: vi.fn(),
    }
    apiMocks.serverCheck.mockResolvedValue({
      compatible: true,
      binlogEnabled: true,
      binlogFormat: 'ROW',
      binlogRowImage: 'FULL',
      hasReplicationPrivilege: true,
      currentFile: 'mysql-bin.000001',
      currentPosition: 4,
      issues: [],
    })
    apiMocks.start.mockResolvedValue({ sessionId: 'session-1' })
    apiMocks.createEventSource.mockResolvedValue(eventSource)
    apiMocks.history.mockResolvedValue({
      items: [],
      total: 2,
      page: 0,
      pageSize: 50,
      stats: {
        insertCount: 2,
        updateCount: 0,
        deleteCount: 0,
        ddlCount: 0,
        tables: ['orders'],
        timeRange: [1, 2],
      },
    })

    const { unmount } = render(<DataTrackerPage />)
    await waitFor(() => expect(apiMocks.listConnections).toHaveBeenCalled())

    fireEvent.mouseDown(screen.getAllByRole('combobox')[0])
    fireEvent.click(await screen.findByText('MySQL mysql-1 (127.0.0.1:3306)'))
    await waitFor(() => expect(apiMocks.serverCheck).toHaveBeenCalledWith('mysql-1'))

    fireEvent.click(screen.getByRole('button', { name: /开始追踪/ }))
    await waitFor(() => expect(apiMocks.createEventSource).toHaveBeenCalledWith('session-1'))

    await act(async () => {
      eventSource.onmessage?.({
        data: JSON.stringify({ type: 'tick', totalCount: 1, rate: 1 }),
      } as MessageEvent<string>)
    })
    await waitFor(() => expect(apiMocks.history).toHaveBeenCalledTimes(1))

    await act(async () => {
      eventSource.onmessage?.({
        data: JSON.stringify({ type: 'tick', totalCount: 2, rate: 2 }),
      } as MessageEvent<string>)
    })
    expect(apiMocks.history).toHaveBeenCalledTimes(1)

    unmount()
  })
})
