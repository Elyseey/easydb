import { useCallback, useMemo } from 'react'
import type { DbType } from '@/types'
import { confirmDataExport } from '@/components/confirmDataExport'
import { rowsToSqlInsert, type ExportFormat } from '@/utils/exportUtils'
import { handleApiError, toast } from '@/utils/notification'

interface UseSelectedRowsExportActionsOptions {
  columns: string[]
  rows: Record<string, unknown>[]
  filenameBase: string
  tableName?: string
  dbType?: DbType
  hasUnloadedRows?: boolean
  excludesUnsavedChanges?: boolean
}

export function useSelectedRowsExportActions(options: UseSelectedRowsExportActionsOptions) {
  const {
    columns,
    rows,
    filenameBase,
    tableName,
    dbType,
    hasUnloadedRows = false,
    excludesUnsavedChanges = false,
  } = options
  const canUseSql = Boolean(tableName && dbType)

  const exportSelected = useCallback((format: ExportFormat) => {
    if (rows.length === 0) return
    if (format === 'sql') {
      if (!tableName || !dbType) return
      confirmDataExport({
        columns,
        rows,
        format,
        filenameBase,
        tableName,
        dbType,
        scope: 'selected',
        loadedOnly: hasUnloadedRows,
        excludesUnsavedChanges,
      })
      return
    }
    confirmDataExport({
      columns,
      rows,
      format,
      filenameBase,
      tableName,
      scope: 'selected',
      loadedOnly: hasUnloadedRows,
      excludesUnsavedChanges,
    })
  }, [columns, dbType, excludesUnsavedChanges, filenameBase, hasUnloadedRows, rows, tableName])

  const copySelectedInsert = useCallback(async () => {
    if (!tableName || !dbType || rows.length === 0) return
    try {
      const sql = rowsToSqlInsert(tableName, columns, rows, dbType)
      await navigator.clipboard.writeText(sql)
      toast.success(`已复制所选 ${rows.length} 行 INSERT`)
    } catch (error) {
      handleApiError(error, '复制所选 INSERT 失败')
    }
  }, [columns, dbType, rows, tableName])

  return useMemo(() => ({
    canUseSql,
    exportSelected,
    copySelectedInsert,
  }), [canUseSql, copySelectedInsert, exportSelected])
}
