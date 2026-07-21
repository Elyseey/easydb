import type { SavedScript, TableInfo } from '@/types'

type NamedTreeItem = {
  name: string
}

export type FilteredSuperTable<TChild extends NamedTreeItem> = {
  table: TableInfo
  children: readonly TChild[]
}

export type FilteredTdengineTree<TChild extends NamedTreeItem> = {
  superTables: FilteredSuperTable<TChild>[]
  basicTables: TableInfo[]
}

const normalizeTreeSearch = (value: string) => value.trim().toLowerCase()

export const matchesTreeSearch = (
  query: string,
  ...values: Array<string | undefined>
) => {
  const normalizedQuery = normalizeTreeSearch(query)
  if (!normalizedQuery) return true
  return values.some((value) => value?.toLowerCase().includes(normalizedQuery))
}

export const filterTreeItemsByName = <T extends NamedTreeItem>(
  items: readonly T[],
  query: string,
) => items.filter((item) => matchesTreeSearch(query, item.name))

export const filterSavedScriptsForTree = (
  scripts: readonly SavedScript[],
  query: string,
) => scripts.filter((script) => matchesTreeSearch(query, script.name, script.content))

export const filterTdengineTreeObjects = <TChild extends NamedTreeItem>(
  objects: readonly TableInfo[],
  childTablesByStable: Readonly<Record<string, readonly TChild[] | undefined>>,
  query: string,
): FilteredTdengineTree<TChild> => {
  const searching = normalizeTreeSearch(query).length > 0
  const superTables = objects
    .filter((object) => object.tableKind === 'SUPER_TABLE')
    .map((table) => {
      const loadedChildren = childTablesByStable[table.name] ?? []
      const children = searching
        ? filterTreeItemsByName(loadedChildren, query)
        : loadedChildren
      return { table, children }
    })
    .filter(({ table, children }) => (
      !searching || matchesTreeSearch(query, table.name) || children.length > 0
    ))

  const basicTables = filterTreeItemsByName(
    objects.filter((object) => object.tableKind === 'BASIC_TABLE'),
    query,
  )

  return { superTables, basicTables }
}
