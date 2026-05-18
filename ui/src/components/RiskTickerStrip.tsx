import { AlertTriangle } from 'lucide-react'
import type {
  BookAggregationDto,
  GreeksResultDto,
  IntradayPnlSnapshotDto,
  VaRResultDto,
} from '../types'
import {
  formatMoney,
  formatNum,
  formatSignedMoney,
  formatTimeOnly,
  pnlColorClass,
} from '../utils/format'

interface RiskTickerStripProps {
  bookId: string | null
  bookSummary: BookAggregationDto | null
  intradaySnapshot: IntradayPnlSnapshotDto | null
  varResult: VaRResultDto | null
  greeksResult: GreeksResultDto | null
  varLimit: number | null
  streamConnected: boolean
}

const EM_DASH = '—'
const VAR_BREACH_THRESHOLD = 0.8

function aggregateGreeks(greeks: GreeksResultDto | null): { delta: number; vega: number } | null {
  if (!greeks) return null
  let delta = 0
  let vega = 0
  for (const ac of greeks.assetClassGreeks) {
    delta += Number(ac.delta)
    vega += Number(ac.vega)
  }
  return { delta, vega }
}

function safePct(numerator: string, denominator: string): string | null {
  const n = Number(numerator)
  const d = Number(denominator)
  if (!Number.isFinite(n) || !Number.isFinite(d) || d === 0) return null
  return ((n / d) * 100).toFixed(2)
}

export function RiskTickerStrip({
  bookId,
  bookSummary,
  intradaySnapshot,
  varResult,
  greeksResult,
  varLimit,
  streamConnected,
}: RiskTickerStripProps) {
  // When no book is selected, render a neutral hint so the strip stays visible globally.
  if (!bookId) {
    return (
      <div
        data-testid="risk-ticker-strip"
        className="flex items-center gap-6 px-4 py-2 bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 text-sm"
      >
        <span
          data-testid="ticker-no-book-hint"
          className="text-xs text-slate-400 dark:text-slate-500"
        >
          Select a book to see live risk metrics.
        </span>
      </div>
    )
  }

  const navText = bookSummary
    ? formatMoney(bookSummary.totalNav.amount, bookSummary.totalNav.currency)
    : EM_DASH

  const unrealisedPnlAmount = bookSummary?.totalUnrealizedPnl.amount ?? null
  const unrealisedPnlText = bookSummary
    ? formatSignedMoney(
        bookSummary.totalUnrealizedPnl.amount,
        bookSummary.totalUnrealizedPnl.currency,
      )
    : EM_DASH

  const unrealisedPnlPct = bookSummary
    ? safePct(bookSummary.totalUnrealizedPnl.amount, bookSummary.totalNav.amount)
    : null
  const unrealisedPnlPctText = unrealisedPnlPct !== null ? `${unrealisedPnlPct}%` : null

  const intradayPnlText = intradaySnapshot
    ? formatSignedMoney(intradaySnapshot.totalPnl, intradaySnapshot.baseCurrency)
    : EM_DASH

  const varValueNumber = varResult ? Number(varResult.varValue) : null
  const varValueText = varResult ? formatMoney(varResult.varValue, 'USD') : EM_DASH
  const varUtilisation =
    varValueNumber !== null && varLimit !== null && varLimit > 0
      ? varValueNumber / varLimit
      : null
  const varUtilPctText = varUtilisation !== null ? `${(varUtilisation * 100).toFixed(1)}%` : null
  const varBreach = varUtilisation !== null && varUtilisation > VAR_BREACH_THRESHOLD
  const varCellClass = varBreach
    ? 'text-red-600 dark:text-red-400 font-semibold'
    : 'text-slate-700 dark:text-slate-200'

  const greeks = aggregateGreeks(greeksResult)
  const deltaText = greeks ? formatNum(greeks.delta) : EM_DASH
  const vegaText = greeks ? formatNum(greeks.vega) : EM_DASH

  const lastCalcAt = varResult?.calculatedAt ?? intradaySnapshot?.snapshotAt ?? null
  const lastCalcText = lastCalcAt ? formatTimeOnly(lastCalcAt) : EM_DASH

  const missingFxRates = intradaySnapshot?.missingFxRates ?? []

  return (
    <div
      data-testid="risk-ticker-strip"
      className="flex flex-wrap items-center gap-x-6 gap-y-1 px-4 py-2 bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 text-sm"
    >
      <div className="flex items-center gap-1.5">
        <span
          data-testid="ticker-connection-status"
          className={`h-2 w-2 rounded-full ${streamConnected ? 'bg-green-500' : 'bg-red-500'}`}
          aria-label={streamConnected ? 'Connected' : 'Disconnected'}
        />
        <span className="text-slate-400 text-xs">{streamConnected ? 'Live' : 'Disconnected'}</span>
      </div>

      <div className="flex items-baseline gap-1">
        <span className="text-xs text-slate-500 uppercase tracking-wide">NAV</span>
        <span
          data-testid="ticker-nav"
          className="font-mono tabular-nums text-slate-800 dark:text-slate-100"
        >
          {navText}
        </span>
      </div>

      <div className="flex items-baseline gap-1">
        <span className="text-xs text-slate-500 uppercase tracking-wide">Unrealised P&amp;L</span>
        <span
          data-testid="ticker-unrealised-pnl"
          className={`font-mono tabular-nums ${unrealisedPnlAmount !== null ? pnlColorClass(unrealisedPnlAmount) : 'text-slate-500'}`}
        >
          {unrealisedPnlText}
        </span>
        {unrealisedPnlPctText && (
          <span
            data-testid="ticker-unrealised-pnl-pct"
            className={`text-xs font-mono ${unrealisedPnlAmount !== null ? pnlColorClass(unrealisedPnlAmount) : 'text-slate-500'}`}
          >
            ({unrealisedPnlPctText})
          </span>
        )}
      </div>

      <div className="flex items-baseline gap-1">
        <span className="text-xs text-slate-500 uppercase tracking-wide">Intraday P&amp;L</span>
        <span
          data-testid="ticker-intraday-pnl"
          className={`font-mono tabular-nums ${intradaySnapshot ? pnlColorClass(intradaySnapshot.totalPnl) : 'text-slate-500'}`}
        >
          {intradayPnlText}
        </span>
      </div>

      <div className="flex items-baseline gap-1">
        <span className="text-xs text-slate-500 uppercase tracking-wide">VaR 1d 95%</span>
        <span
          data-testid="ticker-var"
          className={`font-mono tabular-nums ${varCellClass}`}
        >
          {varValueText}
        </span>
        {varLimit !== null && varValueNumber !== null && (
          <span
            data-testid="ticker-var-utilisation"
            className={`text-xs font-mono ${varCellClass}`}
            title={`Limit: ${formatMoney(String(varLimit), 'USD')}`}
          >
            ({varUtilPctText} of limit)
          </span>
        )}
        {varBreach && (
          <AlertTriangle
            data-testid="ticker-var-breach-icon"
            className="h-3.5 w-3.5 text-red-600 dark:text-red-400"
            aria-label="VaR limit breach"
          />
        )}
      </div>

      <div className="flex items-baseline gap-1">
        <span className="text-xs text-slate-500 uppercase tracking-wide">Net Delta</span>
        <span
          data-testid="ticker-net-delta"
          className="font-mono tabular-nums text-slate-800 dark:text-slate-100"
        >
          {deltaText}
        </span>
      </div>

      <div className="flex items-baseline gap-1">
        <span className="text-xs text-slate-500 uppercase tracking-wide">Net Vega</span>
        <span
          data-testid="ticker-net-vega"
          className="font-mono tabular-nums text-slate-800 dark:text-slate-100"
        >
          {vegaText}
        </span>
      </div>

      {missingFxRates.length > 0 && (
        <div
          data-testid="ticker-missing-fx-rates"
          className="flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400"
          title={`Missing FX rates: ${missingFxRates.join(', ')}`}
        >
          <AlertTriangle className="h-3.5 w-3.5" aria-hidden="true" />
          <span>FX: {missingFxRates.join(', ')}</span>
        </div>
      )}

      <div className="ml-auto flex items-baseline gap-1 text-xs text-slate-400">
        <span className="uppercase tracking-wide">Last calc</span>
        <span data-testid="ticker-last-calc" className="font-mono tabular-nums">
          {lastCalcText}
        </span>
      </div>
    </div>
  )
}
