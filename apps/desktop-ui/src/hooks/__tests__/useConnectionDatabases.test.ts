import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useConnectionDatabases } from '../useConnectionDatabases'

const apiMocks = vi.hoisted(() => ({
  databases: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  metadataApi: {
    databases: apiMocks.databases,
  },
}))

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

const deferred = <T,>(): Deferred<T> => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

describe('useConnectionDatabases', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('loads database names for the selected connection', async () => {
    apiMocks.databases.mockResolvedValue([{ name: 'ENERGY' }, { name: 'SYSTEM' }])
    const { result } = renderHook(() => useConnectionDatabases('connection-a'))

    expect(result.current.loading).toBe(true)
    expect(result.current.databases).toEqual([])

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.databases).toEqual(['ENERGY', 'SYSTEM'])
    expect(result.current.error).toBeNull()
  })

  it('reports a failed load and supports retrying it', async () => {
    const error = new Error('network unavailable')
    apiMocks.databases
      .mockRejectedValueOnce(error)
      .mockResolvedValueOnce([{ name: 'RECOVERED' }])
    const { result } = renderHook(() => useConnectionDatabases('connection-a'))

    await waitFor(() => expect(result.current.error).toBe(error))
    expect(result.current.databases).toEqual([])

    act(() => result.current.reload())
    expect(result.current.loading).toBe(true)
    expect(result.current.error).toBeNull()

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.databases).toEqual(['RECOVERED'])
    expect(apiMocks.databases).toHaveBeenCalledTimes(2)
  })

  it('does not let an older connection request overwrite the current list', async () => {
    const first = deferred<Array<{ name: string }>>()
    const second = deferred<Array<{ name: string }>>()
    apiMocks.databases.mockImplementation((connectionId: string) =>
      connectionId === 'connection-a' ? first.promise : second.promise,
    )

    const { result, rerender } = renderHook(
      ({ connectionId }: { connectionId?: string }) => useConnectionDatabases(connectionId),
      { initialProps: { connectionId: 'connection-a' } },
    )

    rerender({ connectionId: 'connection-b' })
    expect(result.current.loading).toBe(true)
    expect(result.current.databases).toEqual([])

    await act(async () => {
      second.resolve([{ name: 'CURRENT_DB' }])
      await second.promise
    })
    expect(result.current.databases).toEqual(['CURRENT_DB'])

    await act(async () => {
      first.resolve([{ name: 'STALE_DB' }])
      await first.promise
    })
    expect(result.current.databases).toEqual(['CURRENT_DB'])
  })
})
