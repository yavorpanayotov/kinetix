import type { TradeHistoryDto } from '../types'

/**
 * Order states that can no longer fill normally — fills arriving against
 * these are ghost fills requiring operator resolution (ADR-0035 phase 2).
 */
export const TERMINAL_STATUSES = new Set(['EXPIRED', 'CANCELLED', 'REJECTED'])

const BOOKED_LIFECYCLE = new Set(['LIVE', 'AMENDED'])

/**
 * Trader-review P2 §21: project the trade's fill state into the badge text.
 * The gateway sends an explicit FIX-style `fillStatus`
 * (WORKING / FILLED / PARTIAL / CANCELLED / REJECTED) on every booked row.
 *
 * When the upstream omits `fillStatus`, fall back to deriving from the
 * trade-lifecycle `status`:
 *   - LIVE / AMENDED (or missing) → FILLED  (booked records are filled)
 *   - CANCELLED → CANCELLED
 *   - Anything else (in-flight order states like PENDING / PENDING_FAILED /
 *     SENT / EXPIRED — see ADR-0035 phase 4) is left as-is so the order
 *     badges keep rendering their original status text.
 */
export function fillStatus(trade: TradeHistoryDto): string {
  if (trade.fillStatus) return trade.fillStatus
  const lifecycle = trade.status ?? 'LIVE'
  if (lifecycle === 'CANCELLED') return 'CANCELLED'
  if (BOOKED_LIFECYCLE.has(lifecycle)) return 'FILLED'
  return lifecycle
}

export function qtyFilledOf(trade: TradeHistoryDto): string {
  if (trade.qtyFilled !== undefined) return trade.qtyFilled
  const lifecycle = trade.status ?? 'LIVE'
  if (lifecycle === 'CANCELLED') return '0'
  if (BOOKED_LIFECYCLE.has(lifecycle)) return trade.quantity
  return '0'
}

export function qtyOpenOf(trade: TradeHistoryDto): string {
  if (trade.qtyOpen !== undefined) return trade.qtyOpen
  const lifecycle = trade.status ?? 'LIVE'
  if (BOOKED_LIFECYCLE.has(lifecycle) || lifecycle === 'CANCELLED') return '0'
  // In-flight order states default to "open = quantity" — the order
  // hasn't filled yet.
  return trade.quantity
}

export function notional(trade: TradeHistoryDto): number {
  return Number(trade.quantity) * Number(trade.price.amount)
}
