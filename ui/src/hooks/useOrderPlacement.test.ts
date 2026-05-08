import { renderHook, act } from '@testing-library/react'
import { describe, expect, it, beforeEach, vi } from 'vitest'
import { useOrderPlacement } from './useOrderPlacement'
import type { OrderResponseDto, SubmitOrderRequestDto } from '../types'

vi.mock('../api/orders', () => ({
  submitOrder: vi.fn(),
}))

import { submitOrder } from '../api/orders'

const baseRequest: SubmitOrderRequestDto = {
  bookId: 'port-1',
  instrumentId: 'AAPL',
  side: 'BUY',
  quantity: '100',
  orderType: 'LIMIT',
  limitPrice: '150.00',
  arrivalPrice: '149.90',
  instrumentType: 'CASH_EQUITY',
}

function ackResponse(overrides: Partial<OrderResponseDto> = {}): OrderResponseDto {
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
    venueOrderId: 'NYSE-99887766',
    rejectReason: null,
    ...overrides,
  }
}

describe('useOrderPlacement', () => {
  beforeEach(() => {
    vi.mocked(submitOrder).mockReset()
  })

  it('starts in idle state', () => {
    const { result } = renderHook(() => useOrderPlacement())
    expect(result.current.state.kind).toBe('idle')
  })

  it('transitions through submitting → success on a SENT response with venueOrderId', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(ackResponse())

    const { result } = renderHook(() => useOrderPlacement())

    await act(async () => {
      await result.current.submit(baseRequest)
    })

    expect(result.current.state.kind).toBe('success')
    if (result.current.state.kind === 'success') {
      expect(result.current.state.venueOrderId).toBe('NYSE-99887766')
      expect(result.current.state.clOrdId).toBe('order-1')
    }
  })

  it('transitions to rejected on a REJECTED response and surfaces the reason', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(
      ackResponse({ status: 'REJECTED', rejectReason: 'INVALID_REQUEST', venueOrderId: null }),
    )

    const { result } = renderHook(() => useOrderPlacement())

    await act(async () => {
      await result.current.submit(baseRequest)
    })

    expect(result.current.state.kind).toBe('rejected')
    if (result.current.state.kind === 'rejected') {
      expect(result.current.state.reason).toBe('INVALID_REQUEST')
    }
  })

  it('transitions to failed on PENDING_FAILED with SESSION_DOWN reason — retry is allowed', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(
      ackResponse({ status: 'PENDING_FAILED', rejectReason: 'SESSION_DOWN', venueOrderId: null }),
    )

    const { result } = renderHook(() => useOrderPlacement())

    await act(async () => {
      await result.current.submit(baseRequest)
    })

    expect(result.current.state.kind).toBe('failed')
    if (result.current.state.kind === 'failed') {
      expect(result.current.state.reason).toBe('SESSION_DOWN')
      expect(result.current.state.clOrdId).toBe('order-1')
    }
  })

  it('transitions to duplicate on PENDING_FAILED with DUPLICATE_IN_FLIGHT — retry is blocked', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(
      ackResponse({ status: 'PENDING_FAILED', rejectReason: 'DUPLICATE_IN_FLIGHT', venueOrderId: null }),
    )

    const { result } = renderHook(() => useOrderPlacement())

    await act(async () => {
      await result.current.submit(baseRequest)
    })

    expect(result.current.state.kind).toBe('duplicate')
    if (result.current.state.kind === 'duplicate') {
      expect(result.current.state.clOrdId).toBe('order-1')
    }
  })

  it('transitions to failed when the submit call throws (network / 5xx)', async () => {
    vi.mocked(submitOrder).mockRejectedValueOnce(new Error('Order submission failed: 503'))

    const { result } = renderHook(() => useOrderPlacement())

    await act(async () => {
      await result.current.submit(baseRequest)
    })

    expect(result.current.state.kind).toBe('failed')
    if (result.current.state.kind === 'failed') {
      expect(result.current.state.reason).toBe('NETWORK_ERROR')
      // No clOrdId because the submission never completed; retry mints fresh.
      expect(result.current.state.clOrdId).toBeNull()
    }
  })

  it('reset() returns to idle and clears the previous outcome', async () => {
    vi.mocked(submitOrder).mockResolvedValueOnce(ackResponse())

    const { result } = renderHook(() => useOrderPlacement())

    await act(async () => {
      await result.current.submit(baseRequest)
    })
    expect(result.current.state.kind).toBe('success')

    act(() => {
      result.current.reset()
    })
    expect(result.current.state.kind).toBe('idle')
  })

  it('disables a second submit while one is already in flight (double-click guard)', async () => {
    let resolveFirst!: (value: OrderResponseDto) => void
    const firstPromise = new Promise<OrderResponseDto>((r) => { resolveFirst = r })
    vi.mocked(submitOrder).mockReturnValueOnce(firstPromise)

    const { result } = renderHook(() => useOrderPlacement())

    let secondCallReturned: unknown
    act(() => {
      void result.current.submit(baseRequest)
    })
    expect(result.current.state.kind).toBe('submitting')

    // Second call while submitting should be a no-op (returns null and does not call API again)
    await act(async () => {
      secondCallReturned = await result.current.submit(baseRequest)
    })
    expect(secondCallReturned).toBeNull()
    expect(vi.mocked(submitOrder)).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveFirst(ackResponse())
      await firstPromise
    })
    expect(result.current.state.kind).toBe('success')
  })
})
