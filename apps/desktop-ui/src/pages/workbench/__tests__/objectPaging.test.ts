import { describe, expect, it } from 'vitest'
import type { MetadataPage, TableInfo } from '@/types'
import {
  beginPagedObjectRequest,
  failPagedObjectRequest,
  mergePagedObjects,
  MonotonicRequestIdentity,
  tdengineCategoryType,
} from '../objectPaging'

const item = (name: string, type: TableInfo['type'] = 'table'): TableInfo => ({ name, type })
const page = (items: TableInfo[], offset: number, total: number): MetadataPage<TableInfo> => ({
  items,
  offset,
  total,
  limit: 100,
  hasMore: offset + items.length < total,
})

describe('workbench object paging', () => {
  it('merges load-more pages by exact type and catalog name identity', () => {
    const first = mergePagedObjects(undefined, page([item('Sensor'), item('sensor')], 0, 3), '', false)
    const next = mergePagedObjects(first, page([item('sensor'), item('第三表')], 2, 3), '', true)

    expect(next.items.map((value) => value.name)).toEqual(['Sensor', 'sensor', '第三表'])
    expect(next.hasMore).toBe(false)
    expect(next.nextOffset).toBe(4)
  })

  it('resets items when search changes or an initial request fails', () => {
    const loaded = mergePagedObjects(undefined, page([item('old')], 0, 1), 'old', false)
    expect(beginPagedObjectRequest(loaded, 'new', false).items).toEqual([])
    expect(failPagedObjectRequest(loaded, 'new', false, 'boom')).toMatchObject({
      items: [], total: 0, hasMore: false, loading: false, error: 'boom', search: 'new',
    })
  })

  it('preserves loaded items and the server cursor when a load-more request fails', () => {
    const loaded = mergePagedObjects(undefined, page([item('first')], 0, 2), '', false)
    const failed = failPagedObjectRequest(loaded, '', true, 'boom')
    const retrying = beginPagedObjectRequest(failed, '', true)

    expect(failed.items.map((value) => value.name)).toEqual(['first'])
    expect(failed.nextOffset).toBe(1)
    expect(retrying).toMatchObject({ items: failed.items, nextOffset: 1, loading: true, error: undefined })
  })

  it('rejects stale response identities monotonically', () => {
    const guard = new MonotonicRequestIdentity()
    const older = guard.next()
    const newer = guard.next()
    expect(guard.isCurrent(older)).toBe(false)
    expect(guard.isCurrent(newer)).toBe(true)
  })

  it('maps both TDengine workbench categories to server object types', () => {
    expect(tdengineCategoryType('stables')).toBe('stable')
    expect(tdengineCategoryType('tables')).toBe('table')
  })
})
