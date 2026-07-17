import { useCallback, useMemo, useState } from 'react'
import type { Key } from 'react'

function normalizeRowKeys(keys: readonly Key[], rowCount: number): number[] {
  return [...new Set(
    keys
      .map(Number)
      .filter((key) => Number.isInteger(key) && key >= 0 && key < rowCount),
  )].sort((left, right) => left - right)
}

export function selectionForContextMenu(selectedRowKeys: readonly number[], rowIndex: number): number[] {
  return selectedRowKeys.includes(rowIndex) ? [...selectedRowKeys] : [rowIndex]
}

export function rowsForSelection<T>(rows: readonly T[], selectedRowKeys: readonly number[]): T[] {
  return [...new Set(selectedRowKeys)].sort((left, right) => left - right).flatMap((rowIndex) => {
    const row = rows[rowIndex]
    return row === undefined ? [] : [row]
  })
}

export function useResultRowSelection(rowCount: number) {
  const [storedRowKeys, setStoredRowKeys] = useState<number[]>([])
  const selectedRowKeys = useMemo(
    () => normalizeRowKeys(storedRowKeys, rowCount),
    [rowCount, storedRowKeys],
  )

  const setSelectedRowKeys = useCallback((keys: readonly Key[]) => {
    setStoredRowKeys(normalizeRowKeys(keys, rowCount))
  }, [rowCount])

  const clearSelection = useCallback(() => setStoredRowKeys([]), [])

  const selectForContextMenu = useCallback((rowIndex: number) => {
    setStoredRowKeys((current) => selectionForContextMenu(
      normalizeRowKeys(current, rowCount),
      rowIndex,
    ))
  }, [rowCount])

  return {
    selectedRowKeys,
    setSelectedRowKeys,
    clearSelection,
    selectForContextMenu,
  }
}
