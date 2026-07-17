import { isTauri } from '@tauri-apps/api/core'
import { open } from '@tauri-apps/plugin-shell'

export async function openExternalUrl(url: string): Promise<void> {
  if (isTauri()) {
    await open(url)
    return
  }

  window.open(url, '_blank', 'noopener,noreferrer')
}
