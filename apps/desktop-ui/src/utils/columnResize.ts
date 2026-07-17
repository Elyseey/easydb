import type { MouseEvent as ReactMouseEvent } from 'react'

interface DeferredColumnResizeOptions {
  event: ReactMouseEvent
  startWidth: number
  minWidth: number
  maxWidth: number
  boundsElement?: HTMLElement | null
  onCommit: (width: number) => void
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value))
}

export function startDeferredColumnResize({
  event,
  startWidth,
  minWidth,
  maxWidth,
  boundsElement,
  onCommit,
}: DeferredColumnResizeOptions) {
  event.preventDefault()
  event.stopPropagation()

  const startX = event.clientX
  let pendingWidth = startWidth
  let pendingX = startX
  let frame: number | null = null

  const bounds = boundsElement?.getBoundingClientRect()
  const guide = document.createElement('div')
  guide.setAttribute('aria-hidden', 'true')
  Object.assign(guide.style, {
    position: 'fixed',
    top: `${bounds?.top ?? 0}px`,
    left: '0px',
    width: '2px',
    height: `${bounds?.height ?? window.innerHeight}px`,
    background: 'var(--ant-color-primary, #6f7cff)',
    boxShadow: '0 0 0 1px rgba(111, 124, 255, 0.2)',
    pointerEvents: 'none',
    zIndex: '9999',
    transform: `translateX(${startX}px)`,
    willChange: 'transform',
  })
  document.body.appendChild(guide)

  const updateGuide = () => {
    frame = null
    guide.style.transform = `translateX(${pendingX}px)`
  }

  const cleanup = () => {
    if (frame != null) {
      window.cancelAnimationFrame(frame)
      frame = null
    }
    guide.remove()
    document.removeEventListener('mousemove', handleMouseMove)
    document.removeEventListener('mouseup', handleMouseUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }

  const handleMouseMove = (moveEvent: MouseEvent) => {
    pendingWidth = clamp(startWidth + moveEvent.clientX - startX, minWidth, maxWidth)
    pendingX = startX + pendingWidth - startWidth
    if (frame == null) {
      frame = window.requestAnimationFrame(updateGuide)
    }
  }

  const handleMouseUp = () => {
    cleanup()
    onCommit(pendingWidth)
  }

  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', handleMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
}
