/**
 * 数据追踪 API 服务
 *
 * 架构: 后端全量存储 + SSE 轻量通知 + 前端按需分页拉取
 */
import type {
  TrackerSessionConfig, TrackerSessionStatus,
  TrackerServerCheck, RollbackSqlRequest, RollbackSqlResult,
  BinlogFileInfo, PagedHistoryResponse,
} from '@/types'
import { KERNEL_BASE_URL, kernelFetch } from './kernelAuth'

const BASE = '/api/tracker'

interface ApiEnvelope<T> {
  success?: boolean
  data?: T
  error?: { message?: string }
  message?: string
}

function responseMessage(payload: unknown): string | undefined {
  if (!payload || typeof payload !== 'object') return undefined
  const envelope = payload as ApiEnvelope<unknown>
  return envelope.error?.message ?? envelope.message
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await kernelFetch(`${BASE}${path}`, {
    cache: 'no-store',
    ...options,
  })
  const text = await res.text()
  if (!res.ok) {
    // HTTP error — try to parse error message from body
    let message: string | undefined
    try {
      message = responseMessage(JSON.parse(text))
    } catch {
      // 非 JSON 错误响应沿用原始文本
    }
    throw new Error(message || `HTTP ${res.status}: ${text || res.statusText}`)
  }
  if (!text) throw new Error('Empty response from server')
  let json: ApiEnvelope<T>
  try {
    json = JSON.parse(text) as ApiEnvelope<T>
  } catch {
    throw new Error(`Invalid JSON response: ${text.substring(0, 200)}`)
  }
  if (json.success === false) {
    throw new Error(json.error?.message || 'Request failed')
  }
  if (json.data === undefined) throw new Error('Missing response data')
  return json.data
}

export const trackerApi = {
  /** 检查服务端兼容性 */
  serverCheck: (connectionId: string) =>
    request<TrackerServerCheck>(`/server-check?connectionId=${connectionId}`),

  /** 启动追踪 */
  start: (config: TrackerSessionConfig) =>
    request<{ sessionId: string }>('/start', {
      method: 'POST',
      body: JSON.stringify(config),
    }),

  /** 停止追踪 */
  stop: (sessionId: string) =>
    request<{ success: boolean }>('/stop', {
      method: 'POST',
      body: JSON.stringify({ sessionId }),
    }),

  /** 获取追踪状态 */
  status: (sessionId?: string) =>
    request<TrackerSessionStatus | TrackerSessionStatus[]>(
      sessionId ? `/status?sessionId=${sessionId}` : '/status'
    ),

  /** 获取历史事件（服务端分页 + 筛选） */
  history: (params: {
    sessionId: string
    page?: number
    pageSize?: number
    table?: string
    type?: string
    keyword?: string
    startTime?: number
    endTime?: number
    signal?: AbortSignal
  }) => {
    const qs = new URLSearchParams()
    qs.set('sessionId', params.sessionId)
    if (params.page !== undefined) qs.set('page', String(params.page))
    if (params.pageSize !== undefined) qs.set('pageSize', String(params.pageSize))
    if (params.table) qs.set('table', params.table)
    if (params.type) qs.set('type', params.type)
    if (params.keyword) qs.set('keyword', params.keyword)
    if (params.startTime !== undefined) qs.set('startTime', String(params.startTime))
    if (params.endTime !== undefined) qs.set('endTime', String(params.endTime))
    return request<PagedHistoryResponse>(`/history?${qs.toString()}`, { signal: params.signal })
  },

  /** 生成回滚 SQL */
  rollbackSql: (req: RollbackSqlRequest) =>
    request<RollbackSqlResult>('/rollback-sql', {
      method: 'POST',
      body: JSON.stringify(req),
    }),

  /** 生成正向重放 SQL */
  forwardSql: (req: RollbackSqlRequest) =>
    request<RollbackSqlResult>('/forward-sql', {
      method: 'POST',
      body: JSON.stringify(req),
    }),

  /** 列出 binlog 文件 */
  listBinlogFiles: (connectionId: string) =>
    request<BinlogFileInfo[]>(`/binlog-files?connectionId=${connectionId}`),

  /** 创建 SSE 连接（现在只接收 SseTick 轻量通知） */
  createEventSource: async (sessionId: string) => {
    const { ticket } = await request<{ ticket: string }>('/events-ticket', { method: 'POST' })
    const params = new URLSearchParams({ sessionId, ticket })
    return new EventSource(`${KERNEL_BASE_URL}${BASE}/events?${params.toString()}`)
  },
}
