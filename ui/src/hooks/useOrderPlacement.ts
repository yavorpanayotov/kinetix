import { useCallback, useState } from 'react'
import { submitOrder } from '../api/orders'
import type { OrderResponseDto, SubmitOrderRequestDto } from '../types'

/**
 * Submit-flow state machine for order placement (ADR-0035 phase 4 §4.13).
 *
 *   idle → submitting → (success | failed | duplicate | rejected)
 *
 *   - success:   venue acknowledged with PENDING_NEW + venueOrderId. Show
 *                confirmation modal.
 *   - failed:    PENDING_FAILED on a retryable reason (SESSION_DOWN /
 *                DEADLINE_EXCEEDED) OR a network error. Retry CTA is enabled
 *                and the original clOrdId is preserved so fix-gateway
 *                reconciles via FIX 35=H instead of producing a duplicate.
 *   - duplicate: PENDING_FAILED with DUPLICATE_IN_FLIGHT. Retry is BLOCKED —
 *                the trader must wait for the original RPC to settle.
 *   - rejected:  REJECTED — terminal. No retry CTA. Reason copy comes from
 *                the rejectReason field.
 */
export type OrderPlacementState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'success'; clOrdId: string; venueOrderId: string }
  | { kind: 'failed'; clOrdId: string | null; reason: string }
  | { kind: 'duplicate'; clOrdId: string }
  | { kind: 'rejected'; reason: string }

export interface UseOrderPlacementResult {
  state: OrderPlacementState
  submit: (request: SubmitOrderRequestDto) => Promise<OrderResponseDto | null>
  reset: () => void
}

function classify(response: OrderResponseDto): OrderPlacementState {
  if (response.status === 'SENT' && response.venueOrderId) {
    return { kind: 'success', clOrdId: response.orderId, venueOrderId: response.venueOrderId }
  }
  if (response.status === 'REJECTED') {
    return { kind: 'rejected', reason: response.rejectReason ?? 'REJECTED' }
  }
  if (response.status === 'PENDING_FAILED') {
    if (response.rejectReason === 'DUPLICATE_IN_FLIGHT') {
      return { kind: 'duplicate', clOrdId: response.orderId }
    }
    return { kind: 'failed', clOrdId: response.orderId, reason: response.rejectReason ?? 'SESSION_DOWN' }
  }
  // Any other terminal status (CANCELLED, EXPIRED, FILLED, etc.) shouldn't surface
  // here for a brand-new submit, but if it does, treat as success without a venue id.
  return { kind: 'success', clOrdId: response.orderId, venueOrderId: response.venueOrderId ?? '' }
}

export function useOrderPlacement(): UseOrderPlacementResult {
  const [state, setState] = useState<OrderPlacementState>({ kind: 'idle' })

  const submit = useCallback(async (request: SubmitOrderRequestDto): Promise<OrderResponseDto | null> => {
    if (state.kind === 'submitting') return null

    setState({ kind: 'submitting' })
    try {
      const response = await submitOrder(request)
      setState(classify(response))
      return response
    } catch {
      setState({ kind: 'failed', clOrdId: null, reason: 'NETWORK_ERROR' })
      return null
    }
  }, [state.kind])

  const reset = useCallback(() => {
    setState({ kind: 'idle' })
  }, [])

  return { state, submit, reset }
}
