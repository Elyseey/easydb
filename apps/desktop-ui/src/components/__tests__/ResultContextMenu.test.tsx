import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ResultContextMenu } from '../ResultContextMenu'

describe('ResultContextMenu', () => {
  it('renders nested export options on an opaque popup above the root menu', async () => {
    const user = userEvent.setup()

    render(
      <ResultContextMenu
        position={{ x: 80, y: 80 }}
        items={[{
          key: 'export-selected',
          label: '导出所选 2 行',
          children: [
            { key: 'csv', label: 'CSV' },
            { key: 'json', label: 'JSON' },
          ],
        }]}
        onClose={vi.fn()}
      />,
    )

    await user.hover(screen.getByText('导出所选 2 行'))
    await screen.findByText('CSV')

    const popup = document.querySelector<HTMLElement>('.result-context-menu-popup')
    expect(popup).not.toBeNull()
    await waitFor(() => {
      expect(popup).toHaveStyle({
        background: 'var(--glass-popup)',
        zIndex: '1002',
      })
    })
  })
})
