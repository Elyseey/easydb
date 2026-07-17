import { beforeEach, describe, expect, it } from 'vitest'
import {
  resolveStoredTheme,
  THEME_MODE_STORAGE_KEY,
  THEME_STYLE_STORAGE_KEY,
  useThemeStore,
} from '../themeStore'

describe('themeStore', () => {
  beforeEach(() => {
    localStorage.clear()
    useThemeStore.setState({
      themeMode: 'light',
      themeStyle: 'professional',
      effectiveTheme: 'light',
    })
    document.documentElement.setAttribute('data-theme', 'light')
    document.documentElement.setAttribute('data-theme-style', 'professional')
  })

  it('uses professional light for a fresh install', () => {
    expect(resolveStoredTheme(localStorage)).toEqual({
      themeMode: 'light',
      themeStyle: 'professional',
    })
  })

  it('keeps the legacy glass appearance when only the old mode exists', () => {
    localStorage.setItem(THEME_MODE_STORAGE_KEY, 'system')

    expect(resolveStoredTheme(localStorage)).toEqual({
      themeMode: 'system',
      themeStyle: 'glass',
    })
  })

  it('restores an explicitly selected professional style', () => {
    localStorage.setItem(THEME_MODE_STORAGE_KEY, 'dark')
    localStorage.setItem(THEME_STYLE_STORAGE_KEY, 'professional')

    expect(resolveStoredTheme(localStorage)).toEqual({
      themeMode: 'dark',
      themeStyle: 'professional',
    })
  })

  it('switches style without changing the selected color mode', () => {
    useThemeStore.setState({ themeMode: 'dark', effectiveTheme: 'dark' })

    useThemeStore.getState().setThemeStyle('glass')

    expect(useThemeStore.getState()).toMatchObject({
      themeMode: 'dark',
      themeStyle: 'glass',
      effectiveTheme: 'dark',
    })
    expect(localStorage.getItem(THEME_STYLE_STORAGE_KEY)).toBe('glass')
    expect(document.documentElement.dataset.themeStyle).toBe('glass')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })
})
