import type { MetadataPage, TableInfo } from '@/types'

export const OBJECT_PAGE_SIZE = 100

export type PagedObjectState = MetadataPage<TableInfo> & {
  nextOffset: number
  loaded: boolean
  loading: boolean
  error?: string
  search: string
}

export const emptyPagedObjectState = (search = ''): PagedObjectState => ({
  items: [],
  total: 0,
  offset: 0,
  limit: OBJECT_PAGE_SIZE,
  hasMore: false,
  nextOffset: 0,
  loaded: false,
  loading: false,
  search,
})

export const beginPagedObjectRequest = (
  current: PagedObjectState | undefined,
  search: string,
  append: boolean,
): PagedObjectState => ({
  ...(append && current?.search === search ? current : emptyPagedObjectState(search)),
  loading: true,
  error: undefined,
  search,
})

export const mergePagedObjects = (
  current: PagedObjectState | undefined,
  page: MetadataPage<TableInfo>,
  search: string,
  append: boolean,
): PagedObjectState => {
  const previous = append && current?.search === search ? current.items : []
  const seen = new Set(previous.map((item) => `${item.type}\u0000${item.name}`))
  const items = [...previous]
  for (const item of page.items) {
    const identity = `${item.type}\u0000${item.name}`
    if (!seen.has(identity)) {
      seen.add(identity)
      items.push(item)
    }
  }
  return {
    ...page,
    items,
    nextOffset: page.offset + page.items.length,
    loaded: true,
    loading: false,
    error: undefined,
    search,
  }
}

export const failPagedObjectRequest = (
  current: PagedObjectState | undefined,
  search: string,
  append: boolean,
  error: string,
): PagedObjectState => ({
  ...(append && current?.search === search ? current : emptyPagedObjectState(search)),
  loaded: true,
  loading: false,
  error,
  search,
})

export class MonotonicRequestIdentity {
  private current = 0

  next() {
    this.current += 1
    return this.current
  }

  isCurrent(identity: number) {
    return identity === this.current
  }

  value() {
    return this.current
  }
}

export const tdengineCategoryType = (category: string): TableInfo['type'] | undefined => {
  if (category === 'stables') return 'stable'
  if (category === 'tables') return 'table'
  return undefined
}
