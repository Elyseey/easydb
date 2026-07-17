import { isTauri } from '@tauri-apps/api/core'
import { open } from '@tauri-apps/plugin-shell'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { openExternalUrl } from './openExternalUrl'

vi.mock('@tauri-apps/api/core', () => ({ isTauri: vi.fn() }))
vi.mock('@tauri-apps/plugin-shell', () => ({ open: vi.fn() }))

describe('openExternalUrl', () => {
  afterEach(() => {
    vi.clearAllMocks()
    vi.restoreAllMocks()
  })

  it('uses the Tauri shell plugin in the desktop app', async () => {
    vi.mocked(isTauri).mockReturnValue(true)

    await openExternalUrl('https://github.com/qingwz1994/easydb')

    expect(open).toHaveBeenCalledWith('https://github.com/qingwz1994/easydb')
  })

  it('falls back to a browser tab during web development', async () => {
    vi.mocked(isTauri).mockReturnValue(false)
    const windowOpen = vi.spyOn(window, 'open').mockImplementation(() => null)

    await openExternalUrl('https://github.com/qingwz1994/easydb')

    expect(windowOpen).toHaveBeenCalledWith(
      'https://github.com/qingwz1994/easydb',
      '_blank',
      'noopener,noreferrer',
    )
    expect(open).not.toHaveBeenCalled()
  })
})
