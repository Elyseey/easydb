import type { ThemeConfig } from 'antd'
import type { EffectiveTheme, ThemeStyle } from '@/stores/themeStore'

const fontFamily = "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
const fontFamilyCode = "'JetBrains Mono', 'Fira Code', 'SF Mono', monospace"

const professionalTokens: Record<EffectiveTheme, ThemeConfig['token']> = {
  light: {
    colorPrimary: '#3B6FD8',
    colorBgBase: '#F5F6F8',
    colorBgContainer: '#FFFFFF',
    colorBgElevated: '#FFFFFF',
    colorBgLayout: '#F5F6F8',
    colorBorder: '#DDE1E6',
    colorBorderSecondary: '#E8EBEF',
    colorText: '#1F2329',
    colorTextSecondary: '#5F6670',
    colorSuccess: '#16855B',
    colorWarning: '#A45F00',
    colorError: '#C83C3C',
    borderRadius: 6,
  },
  dark: {
    colorPrimary: '#6C9BF2',
    colorBgBase: '#17191C',
    colorBgContainer: '#22262B',
    colorBgElevated: '#22262B',
    colorBgLayout: '#17191C',
    colorBorder: '#3A4048',
    colorBorderSecondary: '#31363D',
    colorText: '#E8EAED',
    colorTextSecondary: '#B4BAC3',
    colorSuccess: '#55C792',
    colorWarning: '#E4A84A',
    colorError: '#F07878',
    borderRadius: 6,
  },
}

const glassTokens: Record<EffectiveTheme, ThemeConfig['token']> = {
  light: {
    colorPrimary: '#7C3AED',
    colorBgBase: '#E8D5F5',
    colorBgContainer: 'rgba(255,255,255,0.40)',
    colorBgElevated: 'rgba(255,255,255,0.55)',
    colorBgLayout: 'transparent',
    colorBorder: 'rgba(255,255,255,0.50)',
    colorBorderSecondary: 'rgba(255,255,255,0.35)',
    colorText: 'rgba(0,0,0,0.85)',
    colorTextSecondary: 'rgba(0,0,0,0.55)',
    borderRadius: 12,
  },
  dark: {
    colorPrimary: '#818CF8',
    colorBgBase: '#0F0C29',
    colorBgContainer: 'rgba(255,255,255,0.06)',
    colorBgElevated: 'rgba(255,255,255,0.10)',
    colorBgLayout: 'transparent',
    colorBorder: 'rgba(255,255,255,0.12)',
    colorBorderSecondary: 'rgba(255,255,255,0.08)',
    colorText: 'rgba(255,255,255,0.95)',
    colorTextSecondary: 'rgba(255,255,255,0.65)',
    borderRadius: 12,
  },
}

export function getEasyDbThemeConfig(
  themeStyle: ThemeStyle,
  effectiveTheme: EffectiveTheme,
  algorithm: ThemeConfig['algorithm'],
): ThemeConfig {
  const styleTokens = themeStyle === 'professional' ? professionalTokens : glassTokens
  return {
    algorithm,
    token: {
      ...styleTokens[effectiveTheme],
      fontSize: 13,
      fontFamily,
      fontFamilyCode,
      controlOutlineWidth: 2,
    },
  }
}
