import { useCallback, useEffect, useState } from 'react'
import type { DatabaseInfo } from '@/types'
import { metadataApi } from '@/services/api'

interface DatabaseLoadResult {
  requestKey: string
  databases: string[]
  error: Error | null
}

export interface ConnectionDatabasesState {
  databases: string[]
  loading: boolean
  error: Error | null
  reload: () => void
}

const normalizeError = (cause: unknown): Error =>
  cause instanceof Error ? cause : new Error('加载数据库列表失败')

/**
 * Load the database list for one connection without allowing an older request
 * to overwrite the result of a newer connection selection.
 */
export function useConnectionDatabases(connectionId?: string): ConnectionDatabasesState {
  const [reloadToken, setReloadToken] = useState(0)
  const [result, setResult] = useState<DatabaseLoadResult>({
    requestKey: '',
    databases: [],
    error: null,
  })
  const requestKey = connectionId ? `${connectionId}:${reloadToken}` : ''

  useEffect(() => {
    if (!connectionId) return

    let active = true
    const loadDatabases = async () => {
      try {
        const response = await metadataApi.databases(connectionId) as DatabaseInfo[]
        if (!active) return
        setResult({
          requestKey,
          databases: response.map((database) => database.name),
          error: null,
        })
      } catch (cause) {
        if (!active) return
        setResult({
          requestKey,
          databases: [],
          error: normalizeError(cause),
        })
      }
    }

    void loadDatabases()
    return () => {
      active = false
    }
  }, [connectionId, requestKey])

  const reload = useCallback(() => {
    if (connectionId) setReloadToken((token) => token + 1)
  }, [connectionId])

  const isCurrentResult = Boolean(connectionId) && result.requestKey === requestKey
  return {
    databases: isCurrentResult ? result.databases : [],
    loading: Boolean(connectionId) && !isCurrentResult,
    error: isCurrentResult ? result.error : null,
    reload,
  }
}
