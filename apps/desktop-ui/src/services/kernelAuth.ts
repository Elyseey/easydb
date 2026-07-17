import { invoke } from '@tauri-apps/api/core'

export const KERNEL_BASE_URL = 'http://localhost:18080'

let tokenPromise: Promise<string> | null = null

async function resolveKernelToken(): Promise<string> {
  const envToken = import.meta.env.VITE_EASYDB_KERNEL_TOKEN?.trim()

  try {
    const token = await invoke<string>('get_kernel_token')
    if (token.trim()) return token.trim()
  } catch {
    // Browser-only dev mode does not expose Tauri IPC; fall back to Vite env.
  }

  if (envToken) return envToken
  throw new Error('内核访问令牌不可用，请通过桌面客户端启动，或设置 VITE_EASYDB_KERNEL_TOKEN')
}

export async function getKernelToken(): Promise<string> {
  if (!tokenPromise) tokenPromise = resolveKernelToken()
  return tokenPromise
}

export async function kernelHeaders(headers?: HeadersInit, includeJson = true): Promise<Headers> {
  const merged = new Headers(headers)
  if (includeJson && !merged.has('Content-Type')) {
    merged.set('Content-Type', 'application/json')
  }
  merged.set('Authorization', `Bearer ${await getKernelToken()}`)
  return merged
}

export async function kernelFetch(pathOrUrl: string, options?: RequestInit): Promise<Response> {
  const url = pathOrUrl.startsWith('http') ? pathOrUrl : `${KERNEL_BASE_URL}${pathOrUrl}`
  const includeJson = !(options?.body instanceof FormData)
  const headers = await kernelHeaders(options?.headers, includeJson)
  return fetch(url, {
    ...options,
    headers,
  })
}

function contentDispositionFileName(header: string | null): string | null {
  if (!header) return null
  const encoded = header.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) return decodeURIComponent(encoded)
  const quoted = header.match(/filename="([^"]+)"/i)?.[1]
  if (quoted) return quoted
  return header.match(/filename=([^;]+)/i)?.[1]?.trim() ?? null
}

export async function downloadKernelFile(path: string, fallbackFileName: string): Promise<void> {
  const res = await kernelFetch(path, { cache: 'no-store' })
  if (!res.ok) {
    let message = `HTTP ${res.status}: ${res.statusText}`
    try {
      const json = await res.json()
      message = json.error?.message || json.message || message
    } catch {
      // Keep the HTTP fallback message.
    }
    throw new Error(message)
  }

  const blob = await res.blob()
  const fileName = contentDispositionFileName(res.headers.get('Content-Disposition')) || fallbackFileName
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
