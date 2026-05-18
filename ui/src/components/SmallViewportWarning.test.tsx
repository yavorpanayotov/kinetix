import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { SmallViewportWarning } from './SmallViewportWarning'

// Helper: set window.innerWidth to a specific value and emit a 'resize' event
// so listeners react. JSDOM exposes innerWidth as a writable global, so we use
// Object.defineProperty to be safe across environments.
function setWindowWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', {
    configurable: true,
    writable: true,
    value: width,
  })
  window.dispatchEvent(new Event('resize'))
}

describe('SmallViewportWarning', () => {
  const originalInnerWidth = window.innerWidth

  beforeEach(() => {
    // Default to a desktop-class width so each test starts from a clean slate.
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 1440,
    })
  })

  afterEach(() => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: originalInnerWidth,
    })
  })

  it('renders nothing when the viewport is at least 1280px wide', () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 1280,
    })

    const { container } = render(<SmallViewportWarning />)

    expect(screen.queryByTestId('small-viewport-warning')).not.toBeInTheDocument()
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing on a typical desktop width', () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 1920,
    })

    render(<SmallViewportWarning />)

    expect(screen.queryByTestId('small-viewport-warning')).not.toBeInTheDocument()
  })

  it('renders the warning copy when the viewport is narrower than 1280px', () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 1024,
    })

    render(<SmallViewportWarning />)

    const warning = screen.getByTestId('small-viewport-warning')
    expect(warning).toBeInTheDocument()
    expect(warning).toHaveTextContent(/Kinetix is desktop-only/i)
    expect(warning).toHaveTextContent(/1280/)
  })

  it('flips to the warning when the viewport shrinks below 1280px at runtime', () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 1440,
    })

    render(<SmallViewportWarning />)
    expect(screen.queryByTestId('small-viewport-warning')).not.toBeInTheDocument()

    act(() => {
      setWindowWidth(800)
    })

    expect(screen.getByTestId('small-viewport-warning')).toBeInTheDocument()
  })

  it('flips back to no warning when the viewport grows back to >=1280px', () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 800,
    })

    render(<SmallViewportWarning />)
    expect(screen.getByTestId('small-viewport-warning')).toBeInTheDocument()

    act(() => {
      setWindowWidth(1600)
    })

    expect(screen.queryByTestId('small-viewport-warning')).not.toBeInTheDocument()
  })

  it('warning has role=alertdialog so assistive tech treats it as a blocking message', () => {
    Object.defineProperty(window, 'innerWidth', {
      configurable: true,
      writable: true,
      value: 800,
    })

    render(<SmallViewportWarning />)

    const warning = screen.getByTestId('small-viewport-warning')
    expect(warning).toHaveAttribute('role', 'alertdialog')
  })
})
