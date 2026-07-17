import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ConnectionDatabaseSelect } from '../ConnectionDatabaseSelect'

const hookMocks = vi.hoisted(() => ({
  useConnectionDatabases: vi.fn(),
  reload: vi.fn(),
}))

vi.mock('@/hooks/useConnectionDatabases', () => ({
  useConnectionDatabases: hookMocks.useConnectionDatabases,
}))

describe('ConnectionDatabaseSelect', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    hookMocks.useConnectionDatabases.mockReturnValue({
      databases: ['ENERGY', 'SYSTEM'],
      loading: false,
      error: null,
      reload: hookMocks.reload,
    })
  })

  it('renders the databases loaded for the current connection', () => {
    render(
      <ConnectionDatabaseSelect
        connectionId="connection-a"
        onChange={vi.fn()}
      />,
    )

    fireEvent.mouseDown(screen.getByRole('combobox'))
    expect(screen.getByText('ENERGY')).toBeInTheDocument()
    expect(screen.getByText('SYSTEM')).toBeInTheDocument()
  })

  it('shows an explicit retry action after loading fails', () => {
    hookMocks.useConnectionDatabases.mockReturnValue({
      databases: [],
      loading: false,
      error: new Error('request failed'),
      reload: hookMocks.reload,
    })
    render(
      <ConnectionDatabaseSelect
        connectionId="connection-a"
        onChange={vi.fn()}
      />,
    )

    fireEvent.mouseDown(screen.getByRole('combobox'))
    fireEvent.click(screen.getByRole('button', { name: /加载失败，点击重试/ }))
    expect(hookMocks.reload).toHaveBeenCalledTimes(1)
  })
})
