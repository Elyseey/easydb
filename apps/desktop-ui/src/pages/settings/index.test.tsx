import { App } from 'antd'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SettingsPage } from '.'

describe('SettingsPage about section', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('opens the official repository from the GitHub support action', () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)

    render(
      <App>
        <SettingsPage />
      </App>,
    )

    fireEvent.click(screen.getByRole('tab', { name: /关于 EasyDB/ }))
    fireEvent.click(screen.getByRole('button', { name: /在 GitHub 上支持 EasyDB/ }))

    expect(screen.getByText('qingwz1994/easydb')).toBeInTheDocument()
    expect(openSpy).toHaveBeenCalledWith(
      'https://github.com/qingwz1994/easydb',
      '_blank',
      'noopener,noreferrer',
    )
  })
})
