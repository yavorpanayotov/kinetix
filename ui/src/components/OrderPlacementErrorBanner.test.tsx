import { describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { OrderPlacementErrorBanner } from './OrderPlacementErrorBanner'

describe('OrderPlacementErrorBanner', () => {
  it('renders nothing when state is idle', () => {
    const { container } = render(<OrderPlacementErrorBanner state={{ kind: 'idle' }} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing when submission succeeded', () => {
    const { container } = render(
      <OrderPlacementErrorBanner state={{ kind: 'success', clOrdId: 'o1', venueOrderId: 'v1' }} />,
    )
    expect(container.firstChild).toBeNull()
  })

  it('renders a WARNING banner with retry CTA enabled when state is failed', () => {
    const onRetry = vi.fn()
    render(
      <OrderPlacementErrorBanner
        state={{ kind: 'failed', clOrdId: 'order-1', reason: 'SESSION_DOWN' }}
        onRetry={onRetry}
      />,
    )

    const banner = screen.getByTestId('order-placement-error-banner')
    expect(banner).toHaveAttribute('data-severity', 'warning')
    expect(banner).toHaveTextContent('Order routing timed out (SESSION_DOWN)')

    const retry = screen.getByTestId('order-placement-retry')
    expect(retry).not.toBeDisabled()
    fireEvent.click(retry)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('renders a WARNING banner with retry CTA disabled when state is duplicate', () => {
    render(
      <OrderPlacementErrorBanner state={{ kind: 'duplicate', clOrdId: 'order-1' }} />,
    )

    const banner = screen.getByTestId('order-placement-error-banner')
    expect(banner).toHaveAttribute('data-severity', 'warning')
    expect(banner).toHaveTextContent('Previous submission still in flight, do not retry yet')

    const retry = screen.getByTestId('order-placement-retry')
    expect(retry).toBeDisabled()
  })

  it('renders a CRITICAL banner with no retry CTA when state is rejected', () => {
    render(
      <OrderPlacementErrorBanner state={{ kind: 'rejected', reason: 'INVALID_REQUEST' }} />,
    )

    const banner = screen.getByTestId('order-placement-error-banner')
    expect(banner).toHaveAttribute('data-severity', 'critical')
    expect(banner).toHaveAttribute('role', 'alert')
    expect(banner).toHaveTextContent('Order rejected: INVALID_REQUEST')
    expect(screen.queryByTestId('order-placement-retry')).toBeNull()
  })
})
