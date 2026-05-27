import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { useState } from 'react'
import { EscapeProvider } from './KeyboardShortcutsEscape'
import {
  useEscapeDismiss,
  registerEscapeHandler,
  __resetEscapeStackForTests,
} from './escapeStack'

interface PopoverProps {
  id: string
  onClose: () => void
}

function Popover({ id, onClose }: PopoverProps) {
  useEscapeDismiss(true, onClose)
  return <div role="dialog" aria-label={id}>{id} content</div>
}

interface HarnessProps {
  initial: Array<string>
}

function Harness({ initial }: HarnessProps) {
  const [open, setOpen] = useState(initial)
  return (
    <EscapeProvider>
      {open.map(id => (
        <Popover key={id} id={id} onClose={() => setOpen(prev => prev.filter(x => x !== id))} />
      ))}
    </EscapeProvider>
  )
}

describe('Escape global handler', () => {
  beforeEach(() => {
    __resetEscapeStackForTests()
  })

  it('closes a single open popover when Escape is pressed', () => {
    render(<Harness initial={['alpha']} />)
    expect(screen.getByRole('dialog', { name: 'alpha' })).toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(screen.queryByRole('dialog', { name: 'alpha' })).toBeNull()
  })

  it('closes only the topmost popover when several are stacked', () => {
    render(<Harness initial={['alpha', 'beta']} />)
    expect(screen.getByRole('dialog', { name: 'alpha' })).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'beta' })).toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(screen.getByRole('dialog', { name: 'alpha' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'beta' })).toBeNull()

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: 'alpha' })).toBeNull()
  })

  it('does not throw when Escape is pressed with no open layers', () => {
    render(
      <EscapeProvider>
        <div>nothing open</div>
      </EscapeProvider>,
    )
    expect(() => fireEvent.keyDown(window, { key: 'Escape' })).not.toThrow()
  })

  it('ignores key presses other than Escape', () => {
    const onClose = vi.fn()
    render(
      <EscapeProvider>
        <Popover id="gamma" onClose={onClose} />
      </EscapeProvider>,
    )

    fireEvent.keyDown(window, { key: 'Enter' })
    fireEvent.keyDown(window, { key: 'a' })
    fireEvent.keyDown(window, { key: ' ' })

    expect(onClose).not.toHaveBeenCalled()
  })

  it('unregisters its handler on unmount so future Escape keys do nothing', () => {
    const onClose = vi.fn()
    const { unmount } = render(
      <EscapeProvider>
        <Popover id="delta" onClose={onClose} />
      </EscapeProvider>,
    )
    unmount()

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(onClose).not.toHaveBeenCalled()
  })

  it('registerEscapeHandler returns an unregister function that pops from the stack', () => {
    const a = vi.fn()
    const b = vi.fn()
    const unregisterA = registerEscapeHandler(a)
    const unregisterB = registerEscapeHandler(b)

    // Escape fires the most-recently-registered handler.
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(b).toHaveBeenCalledTimes(1)
    expect(a).not.toHaveBeenCalled()

    unregisterB()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(a).toHaveBeenCalledTimes(1)

    unregisterA()
    fireEvent.keyDown(window, { key: 'Escape' })
    // Still only called once — the handler is detached.
    expect(a).toHaveBeenCalledTimes(1)
  })
})
