import type { ReactNode } from 'react'
import { Copy } from 'lucide-react'
import type { TradeHistoryDto } from '../types'
import { formatMoney, formatQuantity, formatTimestamp } from '../utils/format'
import { formatPrice } from '../utils/formatPrice'
import { formatInstrumentTypeLabel } from '../utils/instrumentTypes'
import { TERMINAL_STATUSES, fillStatus, notional, qtyFilledOf, qtyOpenOf } from '../utils/tradeProjections'
import { OrderGhostFills } from './OrderGhostFills'

interface TradeDetailPanelProps {
  trade: TradeHistoryDto
}

async function copyToClipboard(value: string): Promise<void> {
  if (typeof navigator === 'undefined' || !navigator.clipboard) return
  await navigator.clipboard.writeText(value)
}

function Field({ label, testId, mono = false, children }: {
  label: string
  testId: string
  mono?: boolean
  children: ReactNode
}) {
  return (
    <div className="min-w-0">
      <dt className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">{label}</dt>
      <dd
        data-testid={testId}
        className={`mt-0.5 text-sm text-slate-700 dark:text-slate-300 ${mono ? 'font-mono text-xs' : ''}`}
      >
        {children}
      </dd>
    </div>
  )
}

/**
 * Inline order detail rendered under an expanded blotter row (kx-ia4z).
 * Surfaces the per-trade identifiers and economics that the blotter columns
 * compress or hide behind global toggles, so a trader can answer "who's the
 * counterparty, which venue, what order ID, what did it really cost" for one
 * trade without widening every row. Ghost fills appear as a section for
 * terminal-status orders (ADR-0035 phase 2).
 */
export function TradeDetailPanel({ trade }: TradeDetailPanelProps) {
  const status = fillStatus(trade)

  return (
    <div data-testid="trade-detail-panel" className="space-y-4">
      <section>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          Identifiers
        </h4>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3 lg:grid-cols-6">
          <Field label="Trade ID" testId="detail-trade-id" mono>
            <span className="inline-flex items-center gap-1.5">
              {trade.tradeId}
              <button
                data-testid="copy-trade-id"
                aria-label="Copy trade ID"
                onClick={() => copyToClipboard(trade.tradeId)}
                className="p-1 rounded hover:bg-slate-100 dark:hover:bg-surface-700"
              >
                <Copy className="h-3 w-3 text-slate-500" />
              </button>
            </span>
          </Field>
          <Field label="Venue Order ID" testId="detail-venue-order-id" mono>
            <span className="inline-flex items-center gap-1.5">
              {trade.venueOrderId ?? '—'}
              {trade.venueOrderId && (
                <button
                  data-testid="detail-copy-venue-order-id"
                  aria-label="Copy venue order ID"
                  onClick={() => copyToClipboard(trade.venueOrderId!)}
                  className="p-1 rounded hover:bg-slate-100 dark:hover:bg-surface-700"
                >
                  <Copy className="h-3 w-3 text-slate-500" />
                </button>
              )}
            </span>
          </Field>
          <Field label="Venue" testId="detail-venue">{trade.venue ?? '—'}</Field>
          <Field label="Book" testId="detail-book" mono>{trade.bookId}</Field>
          <Field label="Counterparty" testId="detail-counterparty" mono>
            {trade.counterpartyId ?? '—'}
          </Field>
          <Field label="Asset Class" testId="detail-asset-class">
            {trade.assetClass}
            {trade.instrumentType && (
              <span
                data-testid="detail-instrument-type"
                className="ml-2 text-slate-500 dark:text-slate-400"
              >
                {formatInstrumentTypeLabel(trade.instrumentType)}
              </span>
            )}
          </Field>
        </dl>
      </section>

      <section>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
          Economics
        </h4>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3 lg:grid-cols-6">
          <Field label="Quantity" testId="detail-quantity">{formatQuantity(trade.quantity)}</Field>
          <Field label="Filled" testId="detail-qty-filled">{formatQuantity(qtyFilledOf(trade))}</Field>
          <Field label="Open" testId="detail-qty-open">{formatQuantity(qtyOpenOf(trade))}</Field>
          <Field label="Price" testId="detail-price">
            {formatPrice(trade.price.amount, trade.price.currency, trade.assetClass)}
          </Field>
          <Field label="Notional" testId="detail-notional">
            {formatMoney(String(notional(trade)), trade.price.currency)}
          </Field>
          <Field label="Traded At" testId="detail-traded-at">{formatTimestamp(trade.tradedAt)}</Field>
        </dl>
      </section>

      {TERMINAL_STATUSES.has(status) && <OrderGhostFills orderId={trade.tradeId} />}
    </div>
  )
}
