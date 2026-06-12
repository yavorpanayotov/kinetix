import { useMemo } from 'react'
import { Users } from 'lucide-react'
import type { TradeHistoryDto } from '../types'
import { formatCurrency } from '../utils/format'
import { SectionHeading } from './ui'

/**
 * Simple Counterparty Exposure tile (kx-i72). Aggregates a list of trades
 * by `counterpartyId` and renders the top-10 by absolute net notional, with
 * a horizontal bar visual.
 *
 * Intentionally minimal — concentration metrics, asset-class breakdown, and
 * netting-set drilldown are tracked as follow-ups. The full credit-risk
 * dashboard (PFE, CVA, agreement status) lives in
 * [CounterpartyRiskDashboard].
 */

export interface CounterpartyExposureTileProps {
  /** Trades to aggregate — usually the current book's blotter rows. */
  trades: TradeHistoryDto[]
  /** Override the default top-N cut-off (10). */
  topN?: number
}

interface AggregatedRow {
  counterpartyId: string
  netNotional: number
  tradeCount: number
}

const DEFAULT_TOP_N = 10

function tradeNotional(trade: TradeHistoryDto): number {
  // Notional = qty × price. Trades whose side is SELL flip the sign so net
  // notional reflects the long/short skew toward each counterparty.
  const qty = Number(trade.quantity)
  const price = Number(trade.price.amount)
  if (!Number.isFinite(qty) || !Number.isFinite(price)) return 0
  const sign = trade.side === 'SELL' ? -1 : 1
  return qty * price * sign
}

function aggregate(trades: TradeHistoryDto[]): AggregatedRow[] {
  const byCp = new Map<string, AggregatedRow>()
  for (const trade of trades) {
    const cp = trade.counterpartyId
    if (!cp) continue
    const row = byCp.get(cp) ?? { counterpartyId: cp, netNotional: 0, tradeCount: 0 }
    row.netNotional += tradeNotional(trade)
    row.tradeCount += 1
    byCp.set(cp, row)
  }
  return Array.from(byCp.values()).sort(
    (a, b) => Math.abs(b.netNotional) - Math.abs(a.netNotional),
  )
}

export function CounterpartyExposureTile({
  trades,
  topN = DEFAULT_TOP_N,
}: CounterpartyExposureTileProps) {
  const rows = useMemo(() => aggregate(trades).slice(0, topN), [trades, topN])
  const maxAbs = useMemo(
    () => (rows.length === 0 ? 1 : Math.max(...rows.map((r) => Math.abs(r.netNotional)))),
    [rows],
  )

  if (rows.length === 0) {
    return (
      <div
        data-testid="counterparty-exposure-tile"
        className="rounded-lg bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-4"
      >
        <div className="flex items-center gap-2 mb-3">
          <Users className="h-4 w-4 text-indigo-400" aria-hidden="true" />
          <SectionHeading as="h3">Counterparty Exposure</SectionHeading>
        </div>
        <div
          data-testid="counterparty-exposure-empty"
          className="text-sm text-slate-500 dark:text-slate-400 py-6 text-center"
        >
          No counterparty exposure yet — trades booked with a counterparty
          will appear here.
        </div>
      </div>
    )
  }

  return (
    <div
      data-testid="counterparty-exposure-tile"
      className="rounded-lg bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 p-4"
    >
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Users className="h-4 w-4 text-indigo-400" aria-hidden="true" />
          <SectionHeading as="h3">Counterparty Exposure</SectionHeading>
        </div>
        <span className="text-xs text-slate-400 dark:text-slate-500">
          Top {rows.length} by |net notional|
        </span>
      </div>
      <ul className="space-y-1.5" aria-label="Counterparty exposure top list">
        {rows.map((row) => {
          const widthPct = Math.max(2, (Math.abs(row.netNotional) / maxAbs) * 100)
          const isShort = row.netNotional < 0
          return (
            <li
              key={row.counterpartyId}
              data-testid={`counterparty-exposure-row-${row.counterpartyId}`}
              className="flex items-center gap-3 text-sm"
            >
              <span className="font-mono text-xs text-slate-700 dark:text-slate-200 w-20 truncate">
                {row.counterpartyId}
              </span>
              <div className="flex-1 h-3 bg-slate-100 dark:bg-slate-700/40 rounded overflow-hidden">
                <div
                  data-testid={`counterparty-exposure-bar-${row.counterpartyId}`}
                  className={`h-full transition-all ${
                    isShort
                      ? 'bg-rose-400 dark:bg-rose-500'
                      : 'bg-indigo-500 dark:bg-indigo-400'
                  }`}
                  style={{ width: `${widthPct}%` }}
                />
              </div>
              <span
                data-testid={`counterparty-exposure-notional-${row.counterpartyId}`}
                className="font-mono tabular-nums text-xs text-slate-700 dark:text-slate-200 w-28 text-right"
              >
                {formatCurrency(row.netNotional)}
              </span>
              <span
                data-testid={`counterparty-exposure-trade-count-${row.counterpartyId}`}
                className="text-xs text-slate-400 dark:text-slate-500 w-16 text-right"
              >
                {row.tradeCount} trade{row.tradeCount === 1 ? '' : 's'}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
