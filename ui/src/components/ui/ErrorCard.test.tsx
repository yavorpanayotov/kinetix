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
})
