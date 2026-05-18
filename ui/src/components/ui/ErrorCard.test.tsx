import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ErrorCard } from './ErrorCard'

describe('ErrorCard', () => {
  it('renders the error message with role=alert', () => {
    render(<ErrorCard message="Something exploded" />)

    const alert = screen.getByRole('alert')
    expect(alert).toBeInTheDocument()
    expect(alert).toHaveTextContent('Something exploded')
  })

  it('does not render a retry button when onRetry is omitted', () => {
    render(<ErrorCard message="Boom" />)

    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
  })

  it('renders a retry button when onRetry is provided', () => {
    const onRetry = vi.fn()
    render(<ErrorCard message="Boom" onRetry={onRetry} />)

    const button = screen.getByRole('button', { name: /retry/i })
    expect(button).toBeInTheDocument()

    fireEvent.click(button)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('forwards data-testid to the alert root', () => {
    render(<ErrorCard message="Boom" data-testid="my-error" />)

    expect(screen.getByTestId('my-error')).toBeInTheDocument()
  })

  it('uses the supplied retryLabel as the button text', () => {
    render(<ErrorCard message="Boom" onRetry={() => {}} retryLabel="Reload" />)

    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^retry$/i })).not.toBeInTheDocument()
  })

  it('defaults retry button label to "Retry" when retryLabel is omitted', () => {
    render(<ErrorCard message="Boom" onRetry={() => {}} />)

    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })

  it('forwards retryTestId as data-testid on the retry button', () => {
    render(<ErrorCard message="Boom" onRetry={() => {}} retryTestId="my-retry" />)

    expect(screen.getByTestId('my-retry')).toBeInTheDocument()
    expect(screen.getByTestId('my-retry').tagName).toBe('BUTTON')
  })

  it('does not set a data-testid on the retry button when retryTestId is omitted', () => {
    render(<ErrorCard message="Boom" onRetry={() => {}} />)

    const button = screen.getByRole('button', { name: /retry/i })
    expect(button.getAttribute('data-testid')).toBeNull()
  })
})
