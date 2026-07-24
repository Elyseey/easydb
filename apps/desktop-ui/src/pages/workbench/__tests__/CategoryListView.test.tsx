import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TableInfo } from '@/types'
import { CategoryListView } from '../index'
import type { PagedObjectState } from '../objectPaging'

type Deferred<T> = { promise: Promise<T>; resolve: (value: T) => void }

const deferred = <T,>(): Deferred<T> => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

type ObjectPageResult = {
  items: TableInfo[]
  total: number
  offset: number
  limit: number
  hasMore: boolean
}

describe('CategoryListView', () => {
  afterEach(() => vi.useRealTimers())

  it('virtualizes a large object set while keeping every object searchable', async () => {
    const objects: TableInfo[] = Array.from({ length: 2694 }, (_, index) => ({
      name: `device_${String(index).padStart(4, '0')}`,
      type: 'table',
      tableKind: 'BASIC_TABLE',
      engine: 'TDENGINE',
    }))

    const Harness = () => {
      const [search, setSearch] = useState('')
      return (
        <CategoryListView
          connectionId="connection-1"
          database="meters"
          category="tables"
          objects={objects}
          objectCategories={[{ key: 'tables', label: '表', types: ['table'], icon: null }]}
          onSelectObject={vi.fn()}
          search={search}
          onSearchChange={setSearch}
        />
      )
    }

    const { container } = render(<Harness />)

    await waitFor(() => {
      const renderedRows = container.querySelectorAll('.ant-table-row').length
      expect(renderedRows).toBeGreaterThan(0)
      expect(renderedRows).toBeLessThan(100)
    })
    expect(screen.getByText('2694 个对象')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('筛选对象名称或注释'), {
      target: { value: 'device_2693' },
    })
    await waitFor(() => expect(screen.getByText('device_2693')).toBeInTheDocument())
    expect(container.querySelectorAll('.ant-table-row')).toHaveLength(1)
  })

  it('loads and merges additional server pages for a TDengine category', async () => {
    const loadPage = vi.fn(async (_search: string, offset: number) => offset === 0
      ? { items: [{ name: 'Sensor', type: 'stable' as const, tableKind: 'SUPER_TABLE' as const }], total: 2, offset: 0, limit: 100, hasMore: true }
      : { items: [{ name: 'sensor', type: 'stable' as const, tableKind: 'SUPER_TABLE' as const }], total: 2, offset: 1, limit: 100, hasMore: false })

    render(
      <CategoryListView
        connectionId="connection-1"
        database="meters"
        category="stables"
        objects={[]}
        objectCategories={[{ key: 'tables', label: '表', types: ['table'], icon: null }]}
        onSelectObject={vi.fn()}
        search=""
        onSearchChange={vi.fn()}
        loadPage={loadPage}
      />,
    )

    await waitFor(() => expect(screen.getByText('Sensor')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    await waitFor(() => expect(screen.getByText('sensor')).toBeInTheDocument())
    expect(loadPage).toHaveBeenNthCalledWith(1, '', 0)
    expect(loadPage).toHaveBeenNthCalledWith(2, '', 1)
    expect(screen.getByText('2 个对象')).toBeInTheDocument()
  })

  it('debounces server search by exactly 250ms and ignores unrelated callback identities', async () => {
    vi.useFakeTimers()
    const loadPage = vi.fn(async (search: string, offset: number) => ({
      items: search ? [{ name: search, type: 'table' as const, tableKind: 'BASIC_TABLE' as const }] : [],
      total: search ? 1 : 0,
      offset,
      limit: 100,
      hasMore: false,
    }))

    const Harness = () => {
      const [search, setSearch] = useState('')
      const [unrelated, setUnrelated] = useState(0)
      return (
        <>
          <button onClick={() => setUnrelated((value) => value + 1)}>rerender {unrelated}</button>
          <CategoryListView
            connectionId="connection-1"
            database="meters"
            category="tables"
            objects={[]}
            objectCategories={[{ key: 'tables', label: '表', types: ['table'], icon: null }]}
            onSelectObject={vi.fn()}
            search={search}
            onSearchChange={setSearch}
            loadPage={(query, offset) => loadPage(query, offset)}
          />
        </>
      )
    }

    render(<Harness />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(loadPage).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /rerender/ }))
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(loadPage).toHaveBeenCalledTimes(1)

    fireEvent.change(screen.getByPlaceholderText('搜索对象名称'), { target: { value: 'meter' } })
    await act(async () => { await vi.advanceTimersByTimeAsync(249) })
    expect(loadPage).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(loadPage).toHaveBeenLastCalledWith('meter', 0)
  })

  it('does not let stale search responses overwrite the latest result or update after unmount', async () => {
    vi.useFakeTimers()
    const requests = new Map<string, Deferred<ObjectPageResult>>()
    const loadPage = vi.fn((search: string) => {
      const request = deferred<ObjectPageResult>()
      requests.set(search, request)
      return request.promise
    })
    const onPagedStateChange = vi.fn()

    const Harness = () => {
      const [search, setSearch] = useState('')
      return (
        <CategoryListView
          connectionId="connection-1"
          database="meters"
          category="tables"
          objects={[]}
          objectCategories={[{ key: 'tables', label: '表', types: ['table'], icon: null }]}
          onSelectObject={vi.fn()}
          search={search}
          onSearchChange={setSearch}
          loadPage={loadPage}
          onPagedStateChange={onPagedStateChange}
        />
      )
    }

    const rendered = render(<Harness />)
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    fireEvent.change(screen.getByPlaceholderText('搜索对象名称'), { target: { value: 'old' } })
    await act(async () => { await vi.advanceTimersByTimeAsync(250) })
    fireEvent.change(screen.getByPlaceholderText('搜索对象名称'), { target: { value: 'new' } })
    await act(async () => { await vi.advanceTimersByTimeAsync(250) })

    await act(async () => requests.get('new')!.resolve({
      items: [{ name: 'new_table', type: 'table', tableKind: 'BASIC_TABLE' }],
      total: 1,
      offset: 0,
      limit: 100,
      hasMore: false,
    }))
    expect(screen.getByText('new_table')).toBeInTheDocument()
    await act(async () => requests.get('old')!.resolve({
      items: [{ name: 'old_table', type: 'table', tableKind: 'BASIC_TABLE' }],
      total: 1,
      offset: 0,
      limit: 100,
      hasMore: false,
    }))
    expect(screen.queryByText('old_table')).not.toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('搜索对象名称'), { target: { value: 'pending' } })
    await act(async () => { await vi.advanceTimersByTimeAsync(250) })
    onPagedStateChange.mockClear()
    rendered.unmount()
    await act(async () => requests.get('pending')!.resolve({
      items: [{ name: 'late_table', type: 'table', tableKind: 'BASIC_TABLE' }],
      total: 1,
      offset: 0,
      limit: 100,
      hasMore: false,
    }))
    expect(onPagedStateChange).not.toHaveBeenCalled()
  })

  it('restores a lifted server page after the category pane is evicted and remounted', async () => {
    const loadPage = vi.fn(async () => ({
      items: [{ name: 'restored_table', type: 'table' as const, tableKind: 'BASIC_TABLE' as const }],
      total: 1,
      offset: 0,
      limit: 100,
      hasMore: false,
    }))

    const Harness = () => {
      const [visible, setVisible] = useState(true)
      const [page, setPage] = useState<PagedObjectState>()
      return (
        <>
          <button onClick={() => setVisible((value) => !value)}>toggle</button>
          {visible && (
            <CategoryListView
              connectionId="connection-1"
              database="meters"
              category="tables"
              objects={[]}
              objectCategories={[{ key: 'tables', label: '表', types: ['table'], icon: null }]}
              onSelectObject={vi.fn()}
              search=""
              onSearchChange={vi.fn()}
              loadPage={loadPage}
              pagedState={page}
              onPagedStateChange={setPage}
            />
          )}
        </>
      )
    }

    render(<Harness />)
    await waitFor(() => expect(screen.getByText('restored_table')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'toggle' }))
    fireEvent.click(screen.getByRole('button', { name: 'toggle' }))
    expect(screen.getByText('restored_table')).toBeInTheDocument()
    expect(loadPage).toHaveBeenCalledTimes(1)
  })
})
