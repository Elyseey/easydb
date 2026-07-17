import React, { useEffect, useState, useRef } from 'react'
import { Modal, Input } from 'antd'
import { SearchOutlined, CodeOutlined } from '@ant-design/icons'
import { useCommandStore } from '@/stores/commandStore'

export const CommandPalette: React.FC = () => {
  const { isOpen, toggleOpen, setOpen, commands, executeCommand } = useCommandStore()
  const [search, setSearch] = useState('')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Cmd+K (Mac) or Ctrl+K (Windows/Linux)
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        toggleOpen()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [toggleOpen])

  useEffect(() => {
    if (isOpen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSearch('')
      setSelectedIndex(0)
    }
  }, [isOpen])

  const filteredCommands = commands.filter((c) =>
    c.title.toLowerCase().includes(search.toLowerCase()) || 
    c.category.toLowerCase().includes(search.toLowerCase())
  )

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setSelectedIndex((prev) => Math.min(prev + 1, filteredCommands.length - 1))
      // Scroll into view logic could be added here
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setSelectedIndex((prev) => Math.max(prev - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (filteredCommands[selectedIndex]) {
        executeCommand(filteredCommands[selectedIndex].id)
      }
    }
  }

  return (
    <Modal
      open={isOpen}
      onCancel={() => setOpen(false)}
      footer={null}
      closable={false}
      width={600}
      styles={{
        body: { padding: 0 },
        mask: { backdropFilter: 'var(--glass-blur-sm)' }
      }}
      modalRender={(node) => (
        <div style={{
          boxShadow: 'var(--glass-shadow-lg)',
          borderRadius: 'var(--edb-radius-lg)',
          overflow: 'hidden',
          border: '1px solid var(--edb-border-default)',
        }}>
          {node}
        </div>
      )}
    >
      <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--edb-border-default)' }}>
        <Input
          autoFocus
          placeholder="搜索命令或操作..."
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setSelectedIndex(0)
          }}
          onKeyDown={handleKeyDown}
          variant="borderless"
          prefix={<SearchOutlined style={{ color: 'var(--edb-text-muted)', fontSize: 18, marginRight: 8 }} />}
          style={{ fontSize: 16, padding: 0, boxShadow: 'none' }}
        />
      </div>
      
      <div 
        ref={listRef}
        style={{ 
          maxHeight: 350, 
          overflowY: 'auto', 
          padding: '8px 0',
          background: 'var(--glass-popup)'
        }}
      >
        {filteredCommands.length === 0 ? (
          <div style={{ padding: '32px 0', textAlign: 'center', color: 'var(--edb-text-muted)' }}>
            找不到匹配的命令
          </div>
        ) : (
          filteredCommands.map((cmd, index) => {
            const isSelected = index === selectedIndex
            return (
              <div
                key={cmd.id}
                onClick={() => executeCommand(cmd.id)}
                onMouseEnter={() => setSelectedIndex(index)}
                style={{
                  padding: '10px 20px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  cursor: 'pointer',
                  background: isSelected ? 'var(--glass-panel-selected)' : 'transparent',
                  transition: 'background 0.1s ease',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <div style={{ 
                    color: isSelected ? 'var(--edb-accent)' : 'var(--edb-text-secondary)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}>
                    {cmd.icon || <CodeOutlined />}
                  </div>
                  <div>
                    <div style={{ 
                      fontSize: 14, 
                      fontWeight: 500,
                      color: 'var(--edb-text-primary)'
                    }}>
                      {cmd.title}
                    </div>
                    {cmd.category && (
                      <div style={{ 
                        fontSize: 12, 
                        color: 'var(--edb-text-muted)',
                        marginTop: 2
                      }}>
                        {cmd.category}
                      </div>
                    )}
                  </div>
                </div>
                {cmd.shortcut && (
                  <div style={{ display: 'flex', gap: 4 }}>
                    {cmd.shortcut.map(key => (
                      <kbd 
                        key={key}
                        style={{
                          background: 'var(--edb-bg-surface)',
                          border: '1px solid var(--edb-border-default)',
                          borderRadius: 4,
                          padding: '2px 6px',
                          fontSize: 11,
                          color: 'var(--edb-text-secondary)',
                          fontFamily: 'monospace'
                        }}
                      >
                        {key}
                      </kbd>
                    ))}
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </Modal>
  )
}
