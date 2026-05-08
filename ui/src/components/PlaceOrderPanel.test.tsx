import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { PlaceOrderPanel } from './PlaceOrderPanel'
import type { OrderResponseDto } from '../types'

vi.mock('../api/orders', () => ({
  submitOrder: vi.fn(),
}))

import { submitOrder } from '../api/orders'

function ack(overrides: Partial<OrderResponseDto> = {}): OrderResponseDto {
  return {
    orderId: 'order-1',
    bookId: 'port-1',
    instrumentId: 'AAPL',
    side: 'BUY',
    quantity: '100',
    orderType: 'LIMIT',
    limitPrice: '150.00',
    arrivalPrice: '149.90',
    submittedAt: '2026-05-08T10:00:00Z',
    status: 'SENT',
    timeInForce: 'DAY',
    venueOrderId: 'NYSE-9988',
    rejectReason: null,
    ...overrides,
  }
}

async function fillBaseForm() {
  fireEvent.change(screen.getByTestId('place-order-instrument'), { target: { value: 'AAPL' } })
  fireEvent.change(screen.getByTestId('place-order-quantity'), { target: { value: '100' } })
  fireEvent.change(screen.getByTestId('place-order-arrival-price'), { target: { value: '149.90' } })
  fireEvent.change(screen.getByTestId('place-order-limit-price'), { target: { value: '150.00' } })
}

describe('PlaceOrderPanel', () => {
  beforeEach(() => {
    vi.mocked(submitOrder).mockReset()
  })

  it('disables submit when required fields are empty', () => {
    render(<PlaceOrderPanel bookId="port-1" />)
    expect(screen.getByTestId('place-order-submit')).toBeDisabled()
  })

  it('on successful submission, shows the venue order id in a confirmation modal with a clipboard button', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(ack())

    render(<PlaceOrderPanel bookId="port-1" />)
    await fillBaseForm()
    fireEvent.click(screen.getByTestId('place-order-submit'))

    await waitFor(() => {
      expect(screen.getByTestId('place-order-confirmation')).toBeVisible()
    })

    const venueIdEl = screen.getByTestId('place-order-confirmation-venue-id')
    expect(venueIdEl).toHaveTextContent('NYSE-9988')

    const copyBtn = screen.getByTestId('place-order-confirmation-copy')
    expect(copyBtn).toHaveAttribute('aria-label', 'Copy venue order ID')
  })

  it('on PENDING_FAILED renders the warning banner with retry CTA enabled', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(
      ack({ status: 'PENDING_FAILED', rejectReason: 'SESSION_DOWN', venueOrderId: null }),
    )

    render(<PlaceOrderPanel bookId="port-1" />)
    await fillBaseForm()
    fireEvent.click(screen.getByTestId('place-order-submit'))

    await waitFor(() => {
      expect(screen.getByTestId('order-placement-error-banner')).toHaveAttribute('data-severity', 'warning')
    })
    expect(screen.getByTestId('order-placement-retry')).not.toBeDisabled()
  })

  it('on DUPLICATE_IN_FLIGHT renders the no-retry banner', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(
      ack({ status: 'PENDING_FAILED', rejectReason: 'DUPLICATE_IN_FLIGHT', venueOrderId: null }),
    )

    render(<PlaceOrderPanel bookId="port-1" />)
    await fillBaseForm()
    fireEvent.click(screen.getByTestId('place-order-submit'))

    await waitFor(() => {
      expect(screen.getByTestId('order-placement-error-banner')).toBeVisible()
    })
    expect(screen.getByTestId('order-placement-retry')).toBeDisabled()
  })

  it('on REJECTED renders the critical banner without retry CTA', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(
      ack({ status: 'REJECTED', rejectReason: 'INVALID_REQUEST', venueOrderId: null }),
    )

    render(<PlaceOrderPanel bookId="port-1" />)
    await fillBaseForm()
    fireEvent.click(screen.getByTestId('place-order-submit'))

    await waitFor(() => {
      expect(screen.getByTestId('order-placement-error-banner')).toHaveAttribute('data-severity', 'critical')
    })
    expect(screen.queryByTestId('order-placement-retry')).toBeNull()
  })

  it('retry on PENDING_FAILED reuses the original clOrdId via the same form payload', async () => {
    vi.mocked(submitOrder)
      .mockResolvedValueOnce(ack({ status: 'PENDING_FAILED', rejectReason: 'SESSION_DOWN', venueOrderId: null, orderId: 'order-1' }))
      .mockResolvedValueOnce(ack({ status: 'SENT', orderId: 'order-1', venueOrderId: 'NYSE-9988' }))

    render(<PlaceOrderPanel bookId="port-1" />)
    await fillBaseForm()
    fireEvent.click(screen.getByTestId('place-order-submit'))

    await waitFor(() => {
      expect(screen.getByTestId('order-placement-retry')).not.toBeDisabled()
    })

    fireEvent.click(screen.getByTestId('order-placement-retry'))

    await waitFor(() => {
      expect(screen.getByTestId('place-order-confirmation')).toBeVisible()
    })
    expect(submitOrder).toHaveBeenCalledTimes(2)
  })
})
