import { useState } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { TableInfo } from '@/types'
import { CategoryListView } from '../index'

describe('CategoryListView', () => {
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
})
