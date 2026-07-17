import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import type { ConnectionConfig } from '@/types'
import { useConnectionStore } from '@/stores/connectionStore'
import { StructureComparePage } from '.'

vi.mock('@/services/api', () => ({
  connectionApi: {
    list: vi.fn(),
    open: vi.fn(),
  },
  metadataApi: {
    databases: vi.fn(),
  },
  compareApi: {
    execute: vi.fn(),
  },
}))

vi.mock('@/utils/notification', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
  handleApiError: vi.fn(),
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
  status: 'connected',
})

describe('StructureComparePage connection selection', () => {
  beforeEach(() => {
    useConnectionStore.setState({
      connections: [
        connection('mysql-1', 'mysql'),
        connection('mysql-2', 'mysql'),
        connection('dameng-1', 'dameng'),
      ],
    })
  })

  it('groups supported source connections after pair-capability filtering', async () => {
    render(
      <MemoryRouter>
        <StructureComparePage />
      </MemoryRouter>,
    )

    fireEvent.mouseDown(screen.getAllByRole('combobox')[0])

    expect(await screen.findByText('MySQL (2)')).toBeInTheDocument()
    expect(screen.getByText('达梦 (1)')).toBeInTheDocument()
  })
})
