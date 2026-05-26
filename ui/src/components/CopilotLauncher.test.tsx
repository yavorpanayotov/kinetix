import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CopilotLauncher } from './CopilotLauncher'

// ---------------------------------------------------------------------------
// CopilotLauncher — header button that reveals the AI copilot to demo viewers
// who might not discover ⌘K on their own.
// ---------------------------------------------------------------------------

describe('CopilotLauncher', () => {
  describe('render', () => {
    it('renders a button with data-testid="copilot-launcher"', () => {
      render(<CopilotLauncher onOpen={vi.fn()} />)
      expect(screen.getByTestId('copilot-launcher')).toBeInTheDocument()
    })

    it('displays the "Ask Kinetix" label', () => {
      render(<CopilotLauncher onOpen={vi.fn()} />)
      expect(screen.getByTestId('copilot-launcher')).toHaveTextContent('Ask Kinetix')
    })

    it('displays a keyboard shortcut chip', () => {
      render(<CopilotLauncher onOpen={vi.fn()} />)
      const chip = screen.getByTestId('copilot-launcher-chip')
      expect(chip).toBeInTheDocument()
    })
  })

  describe('interaction', () => {
    it('calls onOpen when clicked', () => {
      const onOpen = vi.fn()
      render(<CopilotLauncher onOpen={onOpen} />)
      fireEvent.click(screen.getByTestId('copilot-launcher'))
      expect(onOpen).toHaveBeenCalledOnce()
    })

    it('does not call onOpen when not clicked', () => {
      const onOpen = vi.fn()
      render(<CopilotLauncher onOpen={onOpen} />)
      expect(onOpen).not.toHaveBeenCalled()
    })
  })

  describe('platform-specific chip glyph', () => {
    beforeEach(() => {
      vi.stubGlobal('navigator', { platform: 'MacIntel' })
    })

    it('shows ⌘K on macOS (navigator.platform starts with "Mac")', () => {
      vi.stubGlobal('navigator', { platform: 'MacIntel' })
      render(<CopilotLauncher onOpen={vi.fn()} />)
      expect(screen.getByTestId('copilot-launcher-chip')).toHaveTextContent('⌘K')
    })

    it('shows Ctrl K on non-macOS platforms', () => {
      vi.stubGlobal('navigator', { platform: 'Win32' })
      render(<CopilotLauncher onOpen={vi.fn()} />)
      expect(screen.getByTestId('copilot-launcher-chip')).toHaveTextContent('Ctrl K')
    })
  })
})
