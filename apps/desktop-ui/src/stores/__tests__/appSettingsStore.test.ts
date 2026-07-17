import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('appSettingsStore SQL templates setting', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetModules()
  })

  it('enables SQL templates by default', async () => {
    const { useAppSettingsStore } = await import('../appSettingsStore')
    expect(useAppSettingsStore.getState().sqlTemplatesEnabled).toBe(true)
  })

  it('restores a disabled value from localStorage', async () => {
    localStorage.setItem('easydb-sql-templates-enabled', 'false')
    const { useAppSettingsStore } = await import('../appSettingsStore')
    expect(useAppSettingsStore.getState().sqlTemplatesEnabled).toBe(false)
  })

  it('persists changes immediately', async () => {
    const { useAppSettingsStore } = await import('../appSettingsStore')
    useAppSettingsStore.getState().setSqlTemplatesEnabled(false)

    expect(useAppSettingsStore.getState().sqlTemplatesEnabled).toBe(false)
    expect(localStorage.getItem('easydb-sql-templates-enabled')).toBe('false')
  })
})
