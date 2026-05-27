import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { VarPanel } from './VarPanel'

describe('VarPanel not-ready state', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-27T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the bootstrap message while no result is available', () => {
    render(<VarPanel result={null} bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')} />)
    expect(screen.getByText(/var bootstrap in progress/i)).toBeInTheDocument()
  })

  it('shows a running timer that starts at 0s', () => {
    render(<VarPanel result={null} bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')} />)
    expect(screen.getByTestId('var-bootstrap-timer')).toHaveTextContent('0s')
  })

  it('increments the timer each second while waiting', () => {
    render(<VarPanel result={null} bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')} />)

    act(() => {
      vi.advanceTimersByTime(1_000)
    })
    expect(screen.getByTestId('var-bootstrap-timer')).toHaveTextContent('1s')

    act(() => {
      vi.advanceTimersByTime(4_000)
    })
    expect(screen.getByTestId('var-bootstrap-timer')).toHaveTextContent('5s')
  })

  it('switches to mm:ss format past sixty seconds', () => {
    render(<VarPanel result={null} bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')} />)
    act(() => {
      vi.advanceTimersByTime(75_000)
    })
    expect(screen.getByTestId('var-bootstrap-timer')).toHaveTextContent('1m15s')
  })

  it('renders the VaR result once it arrives, hiding the bootstrap message', () => {
    const { rerender } = render(
      <VarPanel result={null} bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')} />,
    )
    expect(screen.getByText(/var bootstrap in progress/i)).toBeInTheDocument()

    rerender(
      <VarPanel
        result={{ value: 1234.56, currency: 'USD', confidence: 0.99 }}
        bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')}
      />,
    )

    expect(screen.queryByText(/var bootstrap in progress/i)).not.toBeInTheDocument()
    expect(screen.getByTestId('var-result-value')).toHaveTextContent('1234.56')
  })

  it('exposes the bootstrap message with role=status so screen readers announce it', () => {
    render(<VarPanel result={null} bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')} />)
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent(/var bootstrap in progress/i)
  })

  it('stops the timer interval once a result arrives', () => {
    const clearSpy = vi.spyOn(globalThis, 'clearInterval')
    const { rerender } = render(
      <VarPanel result={null} bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')} />,
    )
    rerender(
      <VarPanel
        result={{ value: 1, currency: 'USD', confidence: 0.99 }}
        bootstrapStartedAt={new Date('2026-05-27T12:00:00Z')}
      />,
    )
    expect(clearSpy).toHaveBeenCalled()
  })
})
