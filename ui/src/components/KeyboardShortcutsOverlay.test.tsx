import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { KeyboardShortcutsOverlay } from './KeyboardShortcutsOverlay'

describe('KeyboardShortcutsOverlay', () => {
  it('does not render when open is false', () => {
    render(<KeyboardShortcutsOverlay open={false} onClose={vi.fn()} />)
    expect(screen.queryByTestId('keyboard-shortcuts-overlay')).not.toBeInTheDocument()
  })

  it('renders a dialog when open is true', () => {
    render(<KeyboardShortcutsOverlay open={true} onClose={vi.fn()} />)
    const overlay = screen.getByTestId('keyboard-shortcuts-overlay')
    expect(overlay).toBeInTheDocument()
    expect(overlay).toHaveAttribute('role', 'dialog')
    expect(overlay).toHaveAttribute('aria-modal', 'true')
    expect(overlay).toHaveAttribute('aria-label', 'Keyboard shortcuts')
  })

  it('lists the Shift+H Suggest Hedge shortcut', () => {
    render(<KeyboardShortcutsOverlay open={true} onClose={vi.fn()} />)
    expect(screen.getByText('Shift+H')).toBeInTheDocument()
    expect(screen.getByText('Suggest Hedge')).toBeInTheDocument()
  })

  it('lists the Cmd+K / Ctrl+K command palette shortcut', () => {
    render(<KeyboardShortcutsOverlay open={true} onClose={vi.fn()} />)
    expect(screen.getByText('Cmd+K / Ctrl+K')).toBeInTheDocument()
    expect(screen.getByText(/open command palette/i)).toBeInTheDocument()
  })

  it('lists the ? shortcut for showing the overlay', () => {
    render(<KeyboardShortcutsOverlay open={true} onClose={vi.fn()} />)
    expect(screen.getByText('?')).toBeInTheDocument()
    expect(screen.getByText(/show keyboard shortcuts/i)).toBeInTheDocument()
  })

  it('lists Escape for closing dialogs', () => {
    render(<KeyboardShortcutsOverlay open={true} onClose={vi.fn()} />)
    // "Esc" appears in the shortcuts row and in the closing-hint footer — both are fine.
    expect(screen.getAllByText('Esc').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText(/close dialog/i)).toBeInTheDocument()
  })

  it('lists arrow-key tab navigation', () => {
    render(<KeyboardShortcutsOverlay open={true} onClose={vi.fn()} />)
    // The tab bar handles ArrowLeft/ArrowRight/Home/End in App.tsx
    expect(screen.getByText(/arrow keys/i)).toBeInTheDocument()
  })

  it('calls onClose when Escape is pressed', () => {
    const onClose = vi.fn()
    render(<KeyboardShortcutsOverlay open={true} onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('does not call onClose on Escape when closed', () => {
    const onClose = vi.fn()
    render(<KeyboardShortcutsOverlay open={false} onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
  })

  it('calls onClose when the backdrop is clicked', () => {
    const onClose = vi.fn()
    render(<KeyboardShortcutsOverlay open={true} onClose={onClose} />)
    fireEvent.click(screen.getByTestId('keyboard-shortcuts-backdrop'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('moves focus into the dialog when opened', () => {
    render(<KeyboardShortcutsOverlay open={true} onClose={vi.fn()} />)
    const overlay = screen.getByTestId('keyboard-shortcuts-overlay')
    // Focus should be on the dialog container (or a focusable child within it).
    expect(overlay.contains(document.activeElement)).toBe(true)
  })
})
