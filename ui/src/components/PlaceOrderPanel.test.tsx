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

  describe('arrival price is captured server-side (execution.allium SubmitOrder)', () => {
    it('enables submit for a MARKET order without an arrival price', () => {
      render(<PlaceOrderPanel bookId="port-1" />)
      fireEvent.change(screen.getByTestId('place-order-instrument'), { target: { value: 'AAPL' } })
      fireEvent.change(screen.getByTestId('place-order-quantity'), { target: { value: '100' } })
      fireEvent.change(screen.getByTestId('place-order-type'), { target: { value: 'MARKET' } })

      expect(screen.getByTestId('place-order-submit')).not.toBeDisabled()
    })

    it('enables submit for a LIMIT order with a limit price but no arrival price', () => {
      render(<PlaceOrderPanel bookId="port-1" />)
      fireEvent.change(screen.getByTestId('place-order-instrument'), { target: { value: 'AAPL' } })
      fireEvent.change(screen.getByTestId('place-order-quantity'), { target: { value: '100' } })
      fireEvent.change(screen.getByTestId('place-order-limit-price'), { target: { value: '150.00' } })

      expect(screen.getByTestId('place-order-submit')).not.toBeDisabled()
    })

    it('submits a MARKET order without sending an arrival price', async () => {
      vi.mocked(submitOrder).mockResolvedValue(ack({ orderType: 'MARKET', limitPrice: null }))
      render(<PlaceOrderPanel bookId="port-1" />)
      fireEvent.change(screen.getByTestId('place-order-instrument'), { target: { value: 'AAPL' } })
      fireEvent.change(screen.getByTestId('place-order-quantity'), { target: { value: '100' } })
      fireEvent.change(screen.getByTestId('place-order-type'), { target: { value: 'MARKET' } })
      fireEvent.click(screen.getByTestId('place-order-submit'))

      await waitFor(() => expect(submitOrder).toHaveBeenCalled())
      expect(vi.mocked(submitOrder).mock.calls[0][0].arrivalPrice).toBeUndefined()
    })

    it('prefills the arrival price from the book position market price for the typed instrument', () => {
      render(
        <PlaceOrderPanel
          bookId="port-1"
          positions={[
            {
              bookId: 'port-1',
              instrumentId: 'AAPL',
              assetClass: 'EQUITY',
              quantity: '100',
              averageCost: { amount: '150.00', currency: 'USD' },
              marketPrice: { amount: '155.25', currency: 'USD' },
              marketValue: { amount: '15525.00', currency: 'USD' },
              unrealizedPnl: { amount: '525.00', currency: 'USD' },
            },
          ]}
        />,
      )
      fireEvent.change(screen.getByTestId('place-order-instrument'), { target: { value: 'aapl' } })

      expect(screen.getByTestId('place-order-arrival-price')).toHaveValue('155.25')
    })
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

  describe('instrument type resolution (kx-lz7p)', () => {
    const ust5y = {
      bookId: 'port-1',
      instrumentId: 'UST-5Y',
      assetClass: 'FIXED_INCOME',
      quantity: '1000',
      averageCost: { amount: '98.50', currency: 'USD' },
      marketPrice: { amount: '98.80', currency: 'USD' },
      marketValue: { amount: '98800.00', currency: 'USD' },
      unrealizedPnl: { amount: '300.00', currency: 'USD' },
      instrumentType: 'GOVERNMENT_BOND',
    }

    it('submits the reference instrument type from the book position instead of defaulting to CASH_EQUITY', async () => {
      vi.mocked(submitOrder).mockResolvedValue(ack({ instrumentId: 'UST-5Y' }))
      render(<PlaceOrderPanel bookId="port-1" positions={[ust5y]} />)
      fireEvent.change(screen.getByTestId('place-order-instrument'), { target: { value: 'UST-5Y' } })
      fireEvent.change(screen.getByTestId('place-order-quantity'), { target: { value: '1000' } })
      fireEvent.change(screen.getByTestId('place-order-limit-price'), { target: { value: '98.80' } })
      fireEvent.click(screen.getByTestId('place-order-submit'))

      await waitFor(() => expect(submitOrder).toHaveBeenCalled())
      expect(vi.mocked(submitOrder).mock.calls[0][0].instrumentType).toBe('GOVERNMENT_BOND')
    })

    it('matches the position case-insensitively when resolving the instrument type', async () => {
      vi.mocked(submitOrder).mockResolvedValue(ack({ instrumentId: 'UST-5Y' }))
      render(<PlaceOrderPanel bookId="port-1" positions={[ust5y]} />)
      fireEvent.change(screen.getByTestId('place-order-instrument'), { target: { value: 'ust-5y' } })
      fireEvent.change(screen.getByTestId('place-order-quantity'), { target: { value: '1000' } })
      fireEvent.change(screen.getByTestId('place-order-limit-price'), { target: { value: '98.80' } })
      fireEvent.click(screen.getByTestId('place-order-submit'))

      await waitFor(() => expect(submitOrder).toHaveBeenCalled())
      expect(vi.mocked(submitOrder).mock.calls[0][0].instrumentType).toBe('GOVERNMENT_BOND')
    })

    it('falls back to CASH_EQUITY when the instrument has no book position to resolve a type from', async () => {
      vi.mocked(submitOrder).mockResolvedValue(ack())
      render(<PlaceOrderPanel bookId="port-1" positions={[ust5y]} />)
      await fillBaseForm()
      fireEvent.click(screen.getByTestId('place-order-submit'))

      await waitFor(() => expect(submitOrder).toHaveBeenCalled())
      expect(vi.mocked(submitOrder).mock.calls[0][0].instrumentType).toBe('CASH_EQUITY')
    })
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
