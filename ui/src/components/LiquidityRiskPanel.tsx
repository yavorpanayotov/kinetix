import { AlertTriangle, RefreshCw } from 'lucide-react'
import type { LiquidityRiskResultDto } from '../types'
import { LiquidityScoreBar } from './LiquidityScoreBar'
import { Card } from './ui'
import { Spinner } from './ui/Spinner'

interface LiquidityRiskPanelProps {
  result: LiquidityRiskResultDto | null
  loading: boolean
  onRefresh: () => void
}

const STATUS_STYLES: Record<string, string> = {
  OK: 'text-green-700 bg-green-100 dark:text-green-300 dark:bg-green-900/30',
  WARNING:
    'text-yellow-700 bg-yellow-100 dark:text-yellow-300 dark:bg-yellow-900/30',
  BREACHED: 'text-red-700 bg-red-100 dark:text-red-300 dark:bg-red-900/30',
}

const STALENESS_THRESHOLD_MS = 24 * 60 * 60 * 1000

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value)
}

/**
 * Format an LVaR contribution / portfolio LVaR magnitude.
 *
 * Trader-review P0 #7 surfaced that LVaR rendered in single dollars
 * (`$0.7`, `$1.4`) while the rest of the dashboard rendered in compact
 * notation (`$13.3K`, `$58.3K`). The inconsistency made the order-of-
 * magnitude difference easy to miss. Rule:
 *
 *   |v| >= $1,000  → compact notation (matches `Stressed Liq` column)
 *   |v| <  $1,000  → fixed two-decimal `$X.XX` so single-dollar
 *                    magnitudes are unambiguous (never a misleading
 *                    `$0.7` that looks like `$0.7K` at a glance)
 */
export function formatLvar(value: number): string {
  if (!Number.isFinite(value)) return '—'
  if (Math.abs(value) >= 1_000) {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      notation: 'compact',
      maximumFractionDigits: 1,
    }).format(value)
  }
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/**
 * Compute the staleness of a liquidity snapshot in whole days, or null
 * when the snapshot is fresher than [STALENESS_THRESHOLD_MS]. Drives the
 * staleness banner — trader-review P0 #7 flagged a 50-day-old LVaR
 * calculation rendered with no warning.
 */
export function computeStalenessDays(
  calculatedAt: string,
  now: Date,
): number | null {
  const calculated = new Date(calculatedAt).getTime()
  const ageMs = now.getTime() - calculated
  if (!Number.isFinite(ageMs) || ageMs < STALENESS_THRESHOLD_MS) {
    return null
  }
  return Math.floor(ageMs / (24 * 60 * 60 * 1000))
}

export function LiquidityRiskPanel({
  result,
  loading,
  onRefresh,
}: LiquidityRiskPanelProps) {
  if (loading) {
    return (
      <Card className="p-6 flex items-center justify-center">
        <div data-testid="liquidity-loading">
          <Spinner />
        </div>
      </Card>
    )
  }

  if (!result) {
    return (
      <Card className="p-6 flex flex-col items-center gap-2 text-center">
        <p
          data-testid="liquidity-empty"
          className="text-gray-500 dark:text-gray-400"
        >
          No liquidity risk data available. Trigger a calculation to see
          results.
        </p>
        <button
          data-testid="liquidity-refresh"
          onClick={onRefresh}
          className="mt-2 flex items-center gap-1 text-sm text-indigo-600 dark:text-indigo-400 hover:underline"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Calculate now
        </button>
      </Card>
    )
  }

  const completenessPercent = Math.round(result.dataCompleteness * 100)
  const statusStyle =
    STATUS_STYLES[result.portfolioConcentrationStatus] ?? STATUS_STYLES.OK
  const stalenessDays = computeStalenessDays(result.calculatedAt, new Date())

  return (
    <Card className="p-4 flex flex-col gap-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
          Liquidity Risk
        </h3>
        <button
          data-testid="liquidity-refresh"
          onClick={onRefresh}
          aria-label="Refresh liquidity risk"
          className="rounded p-1 text-gray-400 hover:text-indigo-500 dark:hover:text-indigo-400 transition-colors"
        >
          <RefreshCw className="h-4 w-4" />
        </button>
      </div>

      {/* Staleness banner — trader-review P0 #7 */}
      {stalenessDays !== null && (
        <div
          data-testid="liquidity-staleness-banner"
          role="alert"
          className="flex items-start gap-2 rounded border border-yellow-300 bg-yellow-50 px-3 py-2 text-xs text-yellow-800 dark:border-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-200"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>
            Liquidity snapshot is {stalenessDays}d old. Trigger a refresh to
            recalculate against the latest positions and ADV data.
          </span>
        </div>
      )}

      {/* Summary metrics */}
      <div className="grid grid-cols-3 gap-3">
        <div className="flex flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">
            Portfolio LVaR
          </span>
          <span
            data-testid="portfolio-lvar"
            className="text-lg font-bold text-gray-900 dark:text-gray-100"
          >
            {formatLvar(result.portfolioLvar)}
          </span>
        </div>

        <div className="flex flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">
            Data Completeness
          </span>
          <span
            data-testid="data-completeness"
            className={`text-lg font-bold ${completenessPercent < 80 ? 'text-yellow-600 dark:text-yellow-400' : 'text-gray-900 dark:text-gray-100'}`}
          >
            {completenessPercent}%
          </span>
        </div>

        <div className="flex flex-col">
          <span className="text-xs text-gray-500 dark:text-gray-400">
            Concentration
          </span>
          <span
            data-testid="concentration-status"
            data-status={result.portfolioConcentrationStatus}
            className={`mt-0.5 inline-block self-start rounded px-2 py-0.5 text-xs font-semibold ${statusStyle}`}
          >
            {result.portfolioConcentrationStatus}
          </span>
        </div>
      </div>

      {/* Position risks table */}
      {result.positionRisks.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-gray-200 dark:border-gray-700">
                <th className="pb-1 text-left font-medium text-gray-500 dark:text-gray-400">
                  Instrument
                </th>
                <th className="pb-1 text-left font-medium text-gray-500 dark:text-gray-400">
                  Tier
                </th>
                <th className="pb-1 text-right font-medium text-gray-500 dark:text-gray-400">
                  LVaR Contrib
                </th>
                <th className="pb-1 text-right font-medium text-gray-500 dark:text-gray-400">
                  Stressed Liq
                </th>
              </tr>
            </thead>
            <tbody>
              {result.positionRisks.map((pos) => (
                <tr
                  key={pos.instrumentId}
                  data-testid={`position-row-${pos.instrumentId}`}
                  className="border-b border-gray-100 dark:border-gray-800 last:border-0"
                >
                  <td className="py-1.5 pr-2">
                    <span className="font-medium text-gray-900 dark:text-gray-100">
                      {pos.instrumentId}
                    </span>
                  </td>
                  <td className="py-1.5 pr-2 w-28">
                    <LiquidityScoreBar
                      tier={pos.tier}
                      horizonDays={pos.horizonDays}
                      advMissing={pos.advMissing}
                      advStale={pos.advStale}
                    />
                  </td>
                  <td className="py-1.5 text-right text-gray-900 dark:text-gray-100">
                    {formatLvar(pos.lvarContribution)}
                  </td>
                  <td className="py-1.5 text-right text-gray-900 dark:text-gray-100">
                    {formatCurrency(pos.stressedLiquidationValue)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="text-right text-xs text-gray-400 dark:text-gray-500">
        Calculated: {new Date(result.calculatedAt).toLocaleString()}
      </p>
    </Card>
  )
}
