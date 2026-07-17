/*
 * Copyright (c) 2024-2026 EasyDB Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
import { create } from 'zustand'

export type ThemeMode = 'light' | 'dark' | 'system'
export type ThemeStyle = 'professional' | 'glass'
export type EffectiveTheme = Exclude<ThemeMode, 'system'>

export const THEME_MODE_STORAGE_KEY = 'easydb-theme'
export const THEME_STYLE_STORAGE_KEY = 'easydb-theme-style'

const isThemeMode = (value: string | null): value is ThemeMode =>
  value === 'light' || value === 'dark' || value === 'system'

const isThemeStyle = (value: string | null): value is ThemeStyle =>
  value === 'professional' || value === 'glass'

function getSystemTheme(): EffectiveTheme {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function getEffectiveTheme(mode: ThemeMode): EffectiveTheme {
  return mode === 'system' ? getSystemTheme() : mode
}

export interface StoredThemePreference {
  themeMode: ThemeMode
  themeStyle: ThemeStyle
}

/**
 * Legacy releases persisted only easydb-theme. Treat that as an existing user
 * and retain the glass appearance; a completely fresh install starts with the
 * professional light theme.
 */
export function resolveStoredTheme(storage: Pick<Storage, 'getItem'>): StoredThemePreference {
  const storedMode = storage.getItem(THEME_MODE_STORAGE_KEY)
  const storedStyle = storage.getItem(THEME_STYLE_STORAGE_KEY)
  return {
    themeMode: isThemeMode(storedMode) ? storedMode : 'light',
    themeStyle: isThemeStyle(storedStyle)
      ? storedStyle
      : isThemeMode(storedMode) ? 'glass' : 'professional',
  }
}

function applyRootTheme(themeStyle: ThemeStyle, effectiveTheme: EffectiveTheme) {
  document.documentElement.setAttribute('data-theme-style', themeStyle)
  document.documentElement.setAttribute('data-theme', effectiveTheme)
  document.documentElement.style.colorScheme = effectiveTheme
}

interface ThemeState {
  themeMode: ThemeMode
  themeStyle: ThemeStyle
  effectiveTheme: EffectiveTheme
  setThemeMode: (mode: ThemeMode) => void
  setThemeStyle: (style: ThemeStyle) => void
}

export const useThemeStore = create<ThemeState>((set) => {
  const initial = resolveStoredTheme(localStorage)

  // 监听系统主题变化
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    set((state) => {
      if (state.themeMode === 'system') {
        const effective = getSystemTheme()
        applyRootTheme(state.themeStyle, effective)
        return { effectiveTheme: effective }
      }
      return {}
    })
  })

  const effective = getEffectiveTheme(initial.themeMode)
  applyRootTheme(initial.themeStyle, effective)

  return {
    themeMode: initial.themeMode,
    themeStyle: initial.themeStyle,
    effectiveTheme: effective,
    setThemeMode: (mode: ThemeMode) => {
      localStorage.setItem(THEME_MODE_STORAGE_KEY, mode)
      const effective = getEffectiveTheme(mode)
      set((state) => {
        applyRootTheme(state.themeStyle, effective)
        return { themeMode: mode, effectiveTheme: effective }
      })
    },
    setThemeStyle: (themeStyle: ThemeStyle) => {
      localStorage.setItem(THEME_STYLE_STORAGE_KEY, themeStyle)
      set((state) => {
        applyRootTheme(themeStyle, state.effectiveTheme)
        return { themeStyle }
      })
    },
  }
})
