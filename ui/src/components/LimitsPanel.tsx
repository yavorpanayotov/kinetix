import { Shield, RefreshCw } from 'lucide-react'
import { useMemo, useState } from 'react'
import type { LimitDefinitionDto, LimitLevel } from '../api/limits'
import { useLimits } from '../hooks/useLimits'
import { Card, EmptyState, Spinner } from './ui'

const LEVEL_ORDER: LimitLevel[] = [
  'FIRM',
  'DIVISION',
  'DESK',
  'BOOK',
  'TRADER',
  'COUNTERPARTY',
]

function formatNumeric(value: string | null): string {
  if (value == null) return '—'
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return value
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(numeric)
}

/**
 * Render the utilisation cell against a ceiling. The trader-review P0
 * pattern is `$640,000,000 (80%)` — `current` formatted as a number,
 * followed by the utilisation percentage in parentheses. When either the
 * ceiling for this period (intraday / overnight) is absent OR the server
 * couldn't compute utilisation for this limit type (VAR / CONCENTRATION /
 * non-position-attributable scopes), we fall back to the bare ceiling
 * value so the row still tells the trader what the wall is, even when we
 * can't say how close they are.
 */
function formatUtilisationCell(
  ceiling: string | null,
  current?: string | null,
  utilisationPct?: number | null,
): string {
  if (ceiling == null) return '—'
  if (current == null || utilisationPct == null) {
    return formatNumeric(ceiling)
  }
  const pct = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(utilisationPct)
  return `${formatNumeric(current)} (${pct}%)`
}

function groupByLevel(
  limits: LimitDefinitionDto[],
): Map<LimitLevel, LimitDefinitionDto[]> {
  const grouped = new Map<LimitLevel, LimitDefinitionDto[]>()
  for (const level of LEVEL_ORDER) grouped.set(level, [])
  for (const limit of limits) {
    grouped.get(limit.level)?.push(limit)
  }
  return grouped
}

export function LimitsPanel() {
  const { limits, loading, error, refresh } = useLimits()
  const [levelFilter, setLevelFilter] = useState<LimitLevel | 'ALL'>('ALL')

  const grouped = useMemo(() => groupByLevel(limits), [limits])
  const visibleLevels: LimitLevel[] = levelFilter === 'ALL'
    ? LEVEL_ORDER.filter((level) => (grouped.get(level)?.length ?? 0) > 0)
    : [levelFilter]
  const hasVisibleRows = visibleLevels.some(
    (level) => (grouped.get(level)?.length ?? 0) > 0,
  )

  if (loading) {
    return (
      <Card className="p-6 flex items-center justify-center">
        <div data-testid="limits-loading">
          <Spinner />
        </div>
      </Card>
    )
  }

  if (error) {
    return (
      <Card className="p-6">
        <div
          data-testid="limits-error"
          role="alert"
          className="flex items-center justify-between rounded-md bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 px-4 py-3 text-sm text-red-700 dark:text-red-400"
        >
          <span>{error}</span>
          <button
            data-testid="limits-retry-btn"
            onClick={refresh}
            className="ml-4 flex items-center gap-1 text-xs font-medium underline hover:no-underline"
          >
            <RefreshCw className="h-3 w-3" /> Retry
          </button>
        </div>
      </Card>
    )
  }

  if (limits.length === 0) {
    return (
      <Card className="p-6">
        <EmptyState
          icon={<Shield className="h-8 w-8" />}
          title="No limits defined"
          description="No limit definitions have been configured for any hierarchy level."
        />
      </Card>
    )
  }

  return (
    <Card className="p-6" data-testid="limits-panel">
      <div className="flex items-center justify-between mb-4 flex-wrap gap-2">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
          <Shield className="h-4 w-4 text-indigo-500" />
          Limits
        </h3>
        <div className="flex items-center gap-2">
          <label className="text-xs text-slate-500 dark:text-slate-400">Level</label>
          <select
            data-testid="limits-level-filter"
            value={levelFilter}
            onChange={(e) => setLevelFilter(e.target.value as LimitLevel | 'ALL')}
            className="text-xs rounded-md border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 px-2 py-1"
          >
            <option value="ALL">All</option>
            {LEVEL_ORDER.map((level) => (
              <option key={level} value={level}>{level}</option>
            ))}
          </select>
          <button
            data-testid="limits-refresh-btn"
            onClick={refresh}
            className="text-xs text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 flex items-center gap-1"
            aria-label="Refresh limits"
          >
            <RefreshCw className="h-3 w-3" /> Refresh
          </button>
        </div>
      </div>

      {!hasVisibleRows && (
        <p data-testid="limits-no-matching" className="text-sm text-slate-500 dark:text-slate-400">
          No limits at this level.
        </p>
      )}

      {visibleLevels.map((level) => {
        const rows = grouped.get(level) ?? []
        if (rows.length === 0) return null
        return (
          <div key={level} data-testid={`limits-group-${level}`} className="mb-4 last:mb-0">
            <h4 className="text-xs uppercase font-semibold text-slate-500 dark:text-slate-400 mb-2">
              {level}
            </h4>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-slate-600 dark:text-slate-400">
                  <th className="py-2">Entity</th>
                  <th className="py-2">Type</th>
                  <th className="py-2 text-right">Limit</th>
                  <th className="py-2 text-right">Intraday</th>
                  <th className="py-2 text-right">Overnight</th>
                  <th className="py-2 text-center">Active</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.id}
                    data-testid={`limits-row-${row.id}`}
                    className="border-b border-slate-100 dark:border-slate-800"
                  >
                    <td className="py-2 text-slate-900 dark:text-slate-100">{row.entityId}</td>
                    <td className="py-2 text-slate-700 dark:text-slate-300">{row.limitType}</td>
                    <td className="py-2 text-right text-slate-900 dark:text-slate-100">{formatNumeric(row.limitValue)}</td>
                    <td
                      className="py-2 text-right text-slate-700 dark:text-slate-300"
                      data-testid={`limits-cell-intraday-${row.id}`}
                    >
                      {formatUtilisationCell(row.intradayLimit, row.current, row.utilisationPct)}
                    </td>
                    <td
                      className="py-2 text-right text-slate-700 dark:text-slate-300"
                      data-testid={`limits-cell-overnight-${row.id}`}
                    >
                      {formatUtilisationCell(row.overnightLimit, row.current, row.utilisationPct)}
                    </td>
                    <td className="py-2 text-center">
                      {row.active ? (
                        <span className="text-green-600 dark:text-green-400 text-xs">●</span>
                      ) : (
                        <span className="text-slate-400 dark:text-slate-500 text-xs">○</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      })}
    </Card>
  )
}
