import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DemoWelcomeStrip } from './DemoWelcomeStrip'

vi.mock('../auth/demoPersonas', () => ({
  DEMO_MODE: true,
}))

describe('DemoWelcomeStrip', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
  })

  it('renders when sessionStorage flag is absent', () => {
    render(<DemoWelcomeStrip />)
    expect(screen.getByTestId('demo-welcome-strip')).toBeInTheDocument()
  })

  it('does not render when sessionStorage flag is set', () => {
    sessionStorage.setItem('kinetix_demo_strip_dismissed', 'true')
    render(<DemoWelcomeStrip />)
    expect(screen.queryByTestId('demo-welcome-strip')).not.toBeInTheDocument()
  })

  it('clicking dismiss removes the strip from DOM', () => {
    render(<DemoWelcomeStrip />)
    expect(screen.getByTestId('demo-welcome-strip')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('demo-strip-dismiss'))
    expect(screen.queryByTestId('demo-welcome-strip')).not.toBeInTheDocument()
  })

  it('clicking dismiss writes persistence flag to sessionStorage', () => {
    render(<DemoWelcomeStrip />)
    fireEvent.click(screen.getByTestId('demo-strip-dismiss'))
    expect(sessionStorage.getItem('kinetix_demo_strip_dismissed')).toBe('true')
  })

  it('clicking dismiss does not write to localStorage (session-scoped only)', () => {
    render(<DemoWelcomeStrip />)
    fireEvent.click(screen.getByTestId('demo-strip-dismiss'))
    expect(localStorage.getItem('kinetix_demo_strip_dismissed')).toBeNull()
  })

  it('strip does not render after dismiss and re-mount (persisted in session)', () => {
    const { unmount } = render(<DemoWelcomeStrip />)
    fireEvent.click(screen.getByTestId('demo-strip-dismiss'))
    unmount()

    render(<DemoWelcomeStrip />)
    expect(screen.queryByTestId('demo-welcome-strip')).not.toBeInTheDocument()
  })

  it('strip reappears after the session flag is cleared', () => {
    const { unmount } = render(<DemoWelcomeStrip />)
    fireEvent.click(screen.getByTestId('demo-strip-dismiss'))
    unmount()

    sessionStorage.clear()
    render(<DemoWelcomeStrip />)
    expect(screen.getByTestId('demo-welcome-strip')).toBeInTheDocument()
  })

  it('strip copy contains expected text', () => {
    render(<DemoWelcomeStrip />)
    expect(screen.getByTestId('demo-welcome-strip')).toHaveTextContent('Demo mode')
    expect(screen.getByTestId('demo-welcome-strip')).toHaveTextContent('Switch personas')
  })

  it('dismiss button has accessible label', () => {
    render(<DemoWelcomeStrip />)
    expect(screen.getByRole('button', { name: /dismiss/i })).toBeInTheDocument()
  })
})
