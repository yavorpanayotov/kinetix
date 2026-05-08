import { useCallback, useState } from 'react'
import { Copy } from 'lucide-react'
import { Button, Card, Input, Select } from './ui'
import { OrderPlacementErrorBanner } from './OrderPlacementErrorBanner'
import { useOrderPlacement } from '../hooks/useOrderPlacement'
import type { OrderTimeInForce, SubmitOrderRequestDto } from '../types'

/**
 * Minimal order ticket panel introduced for ADR-0035 phase 4 §4.13. Calls the
 * existing `POST /api/v1/orders` endpoint via `useOrderPlacement`, which
 * routes through fix-gateway when the `FIX_GATEWAY_PLACE_ORDER` flag is on.
 *
 * The form intentionally exposes only the fields that exercise the new state
 * machine (PENDING_NEW / PENDING_FAILED / DUPLICATE_IN_FLIGHT / REJECTED) —
 * full ticket variants (bracket / OCO / multi-leg) are out of scope for this
 * plan item.
 */
interface PlaceOrderPanelProps {
  bookId: string
}

const TIF_OPTIONS: OrderTimeInForce[] = ['DAY', 'GTC', 'IOC', 'FOK', 'GTD']

export function PlaceOrderPanel({ bookId }: PlaceOrderPanelProps) {
  const [instrumentId, setInstrumentId] = useState('')
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY')
  const [quantity, setQuantity] = useState('')
  const [orderType, setOrderType] = useState<'LIMIT' | 'MARKET'>('LIMIT')
  const [limitPrice, setLimitPrice] = useState('')
  const [arrivalPrice, setArrivalPrice] = useState('')
  const [timeInForce, setTimeInForce] = useState<OrderTimeInForce>('DAY')
  const [copyConfirmed, setCopyConfirmed] = useState(false)

  const { state, submit, reset } = useOrderPlacement()

  const formIncomplete =
    !instrumentId.trim() ||
    !quantity.trim() ||
    !arrivalPrice.trim() ||
    (orderType === 'LIMIT' && !limitPrice.trim())

  const submitting = state.kind === 'submitting'

  const buildRequest = useCallback((): SubmitOrderRequestDto => ({
    bookId,
    instrumentId: instrumentId.trim().toUpperCase(),
    side,
    quantity: quantity.trim(),
    orderType,
    limitPrice: orderType === 'LIMIT' ? limitPrice.trim() : undefined,
    arrivalPrice: arrivalPrice.trim(),
    timeInForce,
    instrumentType: 'CASH_EQUITY',
  }), [bookId, instrumentId, side, quantity, orderType, limitPrice, arrivalPrice, timeInForce])

  const handleSubmit = useCallback(async () => {
    if (formIncomplete || submitting) return
    await submit(buildRequest())
  }, [buildRequest, formIncomplete, submitting, submit])

  const handleRetry = useCallback(async () => {
    await submit(buildRequest())
  }, [buildRequest, submit])

  const handleCopyVenueId = useCallback(async (venueId: string) => {
    if (typeof navigator === 'undefined' || !navigator.clipboard) return
    await navigator.clipboard.writeText(venueId)
    setCopyConfirmed(true)
    window.setTimeout(() => setCopyConfirmed(false), 1500)
  }, [])

  const dismissConfirmation = useCallback(() => {
    reset()
    setCopyConfirmed(false)
  }, [reset])

  return (
    <Card>
      <div className="space-y-4">
        <h2 className="text-base font-semibold text-slate-700 dark:text-slate-200">
          Place Order
        </h2>

        <div className="grid grid-cols-2 gap-3">
          <label className="text-xs text-slate-500">
            Instrument
            <Input
              data-testid="place-order-instrument"
              value={instrumentId}
              onChange={(e) => setInstrumentId(e.target.value)}
              className="w-full mt-1"
              placeholder="AAPL"
              disabled={submitting}
            />
          </label>

          <label className="text-xs text-slate-500">
            Side
            <Select
              data-testid="place-order-side"
              value={side}
              onChange={(e) => setSide(e.target.value as 'BUY' | 'SELL')}
              className="w-full mt-1"
              disabled={submitting}
            >
              <option value="BUY">BUY</option>
              <option value="SELL">SELL</option>
            </Select>
          </label>

          <label className="text-xs text-slate-500">
            Quantity
            <Input
              data-testid="place-order-quantity"
              type="text"
              inputMode="decimal"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
              className="w-full mt-1"
              disabled={submitting}
            />
          </label>

          <label className="text-xs text-slate-500">
            Order Type
            <Select
              data-testid="place-order-type"
              value={orderType}
              onChange={(e) => setOrderType(e.target.value as 'LIMIT' | 'MARKET')}
              className="w-full mt-1"
              disabled={submitting}
            >
              <option value="LIMIT">LIMIT</option>
              <option value="MARKET">MARKET</option>
            </Select>
          </label>

          <label className="text-xs text-slate-500">
            Arrival Price
            <Input
              data-testid="place-order-arrival-price"
              type="text"
              inputMode="decimal"
              value={arrivalPrice}
              onChange={(e) => setArrivalPrice(e.target.value)}
              className="w-full mt-1"
              disabled={submitting}
            />
          </label>

          <label className="text-xs text-slate-500">
            {orderType === 'LIMIT' ? 'Limit Price' : 'Limit Price (n/a)'}
            <Input
              data-testid="place-order-limit-price"
              type="text"
              inputMode="decimal"
              value={limitPrice}
              onChange={(e) => setLimitPrice(e.target.value)}
              className="w-full mt-1"
              disabled={submitting || orderType === 'MARKET'}
            />
          </label>

          <label className="text-xs text-slate-500 col-span-2">
            Time in Force
            <Select
              data-testid="place-order-tif"
              value={timeInForce}
              onChange={(e) => setTimeInForce(e.target.value as OrderTimeInForce)}
              className="w-full mt-1"
              disabled={submitting}
            >
              {TIF_OPTIONS.map((tif) => (
                <option key={tif} value={tif}>{tif}</option>
              ))}
            </Select>
          </label>
        </div>

        <OrderPlacementErrorBanner state={state} onRetry={handleRetry} />

        <div className="flex items-center gap-3">
          <Button
            data-testid="place-order-submit"
            variant="primary"
            loading={submitting}
            disabled={formIncomplete || submitting}
            onClick={handleSubmit}
          >
            {submitting ? 'Sending to venue...' : 'Submit Order'}
          </Button>
          <span className="text-xs text-slate-500">
            Routes through fix-gateway. Venue ack timeout depends on the venue's session config.
          </span>
        </div>

        {state.kind === 'success' && (
          <div
            data-testid="place-order-confirmation"
            role="dialog"
            aria-modal="true"
            aria-labelledby="place-order-confirmation-title"
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
            onClick={dismissConfirmation}
          >
            <div
              className="bg-white dark:bg-surface-800 rounded-lg shadow-xl max-w-md w-full mx-4 p-6"
              onClick={(e) => e.stopPropagation()}
            >
              <h3
                id="place-order-confirmation-title"
                className="text-lg font-semibold text-slate-800 dark:text-slate-100"
              >
                Order Sent
              </h3>
              <p className="mt-2 text-sm text-slate-600 dark:text-slate-400">
                Venue acknowledged the order with a Pending New status.
              </p>
              <dl className="mt-4 grid grid-cols-2 gap-y-2 text-sm">
                <dt className="text-slate-500">clOrdID</dt>
                <dd
                  data-testid="place-order-confirmation-clord-id"
                  className="font-mono text-right"
                >
                  {state.clOrdId}
                </dd>
                <dt className="text-slate-500">Venue Order ID</dt>
                <dd className="text-right flex items-center justify-end gap-2">
                  <span
                    data-testid="place-order-confirmation-venue-id"
                    className="font-mono"
                  >
                    {state.venueOrderId}
                  </span>
                  <button
                    data-testid="place-order-confirmation-copy"
                    aria-label="Copy venue order ID"
                    onClick={() => handleCopyVenueId(state.venueOrderId)}
                    className="p-1 rounded hover:bg-slate-100"
                  >
                    <Copy className="h-3.5 w-3.5 text-slate-500" />
                  </button>
                </dd>
              </dl>
              {copyConfirmed && (
                <p
                  data-testid="place-order-confirmation-copied"
                  className="mt-2 text-xs text-green-600"
                >
                  Copied to clipboard
                </p>
              )}
              <div className="mt-6 flex justify-end">
                <Button
                  variant="secondary"
                  onClick={dismissConfirmation}
                  data-testid="place-order-confirmation-dismiss"
                >
                  Dismiss
                </Button>
              </div>
            </div>
          </div>
        )}
      </div>
    </Card>
  )
}
