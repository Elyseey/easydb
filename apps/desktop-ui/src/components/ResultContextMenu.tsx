import React from 'react'
import { Menu, theme } from 'antd'
import type { MenuProps } from 'antd'

export interface ResultContextMenuPosition {
  x: number
  y: number
}

interface ResultContextMenuProps {
  position: ResultContextMenuPosition
  items: MenuProps['items']
  onClose: () => void
}

const MENU_WIDTH = 240
const MENU_MAX_HEIGHT = 360
const VIEWPORT_GAP = 8

export const ResultContextMenu: React.FC<ResultContextMenuProps> = ({ position, items, onClose }) => {
  const { token } = theme.useToken()
  const maxHeight = Math.max(120, Math.min(MENU_MAX_HEIGHT, window.innerHeight - VIEWPORT_GAP * 2))
  const left = position.x + MENU_WIDTH > window.innerWidth
    ? Math.max(VIEWPORT_GAP, position.x - MENU_WIDTH)
    : position.x
  const top = position.y + maxHeight > window.innerHeight
    ? Math.max(VIEWPORT_GAP, window.innerHeight - maxHeight - VIEWPORT_GAP)
    : position.y

  return (
    <>
      <div
        role="presentation"
        style={{
          position: 'fixed',
          left,
          top,
          zIndex: token.zIndexPopupBase + 1,
          width: MENU_WIDTH,
          maxHeight,
          overflowY: 'auto',
          background: 'var(--glass-popup)',
          border: '1px solid var(--glass-border)',
          borderRadius: token.borderRadiusLG,
          boxShadow: 'var(--glass-shadow-lg)',
          backdropFilter: 'blur(20px)',
          padding: 4,
        }}
        onClick={(event) => event.stopPropagation()}
      >
        <Menu
          items={items}
          selectable={false}
          onClick={onClose}
          classNames={{ popup: { root: 'result-context-menu-popup' } }}
          styles={{
            popup: {
              root: {
                zIndex: token.zIndexPopupBase + 2,
                background: 'var(--glass-popup)',
                border: '1px solid var(--glass-border)',
                borderRadius: token.borderRadiusLG,
                boxShadow: 'var(--glass-shadow-lg)',
                backdropFilter: 'var(--glass-blur-heavy)',
                WebkitBackdropFilter: 'var(--glass-blur-heavy)',
              },
            },
          }}
          style={{ border: 0, background: 'transparent' }}
        />
      </div>
      <div
        role="presentation"
        style={{ position: 'fixed', inset: 0, zIndex: token.zIndexPopupBase }}
        onClick={onClose}
        onContextMenu={(event) => {
          event.preventDefault()
          onClose()
        }}
      />
    </>
  )
}
