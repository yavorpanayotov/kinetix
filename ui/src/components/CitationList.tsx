import { Database, Info } from 'lucide-react'
import type { Citation } from '../api/copilot'

export interface CitationListProps {
  citations: Citation[]
}

/**
 * Format a freshness value (in seconds) into a coarse relative label
 * suitable for a footnote-list badge. We deliberately keep the
 * formatter dumb and dependency-free — sub-minute precision adds noise
 * for a reader scanning provenance, and a single point of truth here
 * keeps tests stable.
 *
 * Buckets:
 *  - ``0`` → ``"just now"``
 *  - ``< 60s`` → ``"Ns ago"``
 *  - ``< 3600s`` → ``"Nm ago"`` (floor)
 *  - ``< 86400s`` → ``"Nh ago"`` (floor)
 *  - ``>= 86400s`` → ``"Nd ago"`` (floor)
 *
 * Kept non-exported so this file only exports the component itself —
 * react-refresh's ``only-export-components`` rule otherwise trips.
 */
function formatFreshness(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return 'just now'
  if (seconds < 60) return `${Math.floor(seconds)}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  return `${Math.floor(seconds / 86400)}d ago`
}

/**
 * Render a citation's ``result_value`` for display. Numbers pass
 * through ``toString`` (no thousand-separators — the footer is dense
 * and a raw value matches the underlying citation better); strings
 * pass through verbatim so pre-formatted values like ``"5.2M USD"``
 * aren't mangled.
 */
function formatResultValue(
  value: number | string,
  currency: string | null,
): string {
  if (typeof value === 'string') return value
  const base = Number.isFinite(value) ? value.toString() : ''
  return currency ? `${base} ${currency}` : base
}

/**
 * Footer list rendered below an AI narrative; one entry per
 * citation in input (tool-call) order. Each entry surfaces the
 * tool, data source, field/value, freshness, optional quality
 * flags, and a collapsible ``<details>`` element exposing the
 * raw parameters JSON for inspection.
 *
 * Returns ``null`` when ``citations`` is empty so callers don't have
 * to gate on length themselves.
 */
export function CitationList({ citations }: CitationListProps): React.ReactElement | null {
  if (citations.length === 0) return null

  return (
    <ol
      data-testid="citation-list"
      className="mt-3 space-y-2 text-xs text-slate-600 dark:text-slate-300"
    >
      {citations.map((c, idx) => {
        const index = idx + 1
        const freshness = formatFreshness(c.freshness_seconds)
        const value = formatResultValue(c.result_value, c.result_currency)
        return (
          <li
            key={`${c.tool}-${c.result_field}-${idx}`}
            data-testid="citation-list-item"
            className="rounded border border-slate-200 dark:border-surface-700 bg-slate-50 dark:bg-surface-900 px-2 py-1.5"
          >
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <span
                aria-label="citation-index"
                className="font-mono text-[10px] text-slate-500 dark:text-slate-400"
              >
                {index}
              </span>
              <span className="font-semibold text-slate-800 dark:text-slate-100">
                {c.tool}
              </span>
              <span className="inline-flex items-center gap-1 text-slate-500 dark:text-slate-400">
                <Database className="h-3 w-3" aria-hidden="true" />
                {c.data_source}
              </span>
              <span className="text-slate-500 dark:text-slate-400">
                {c.result_field}
                {': '}
                <span className="font-mono text-slate-700 dark:text-slate-200">
                  {value}
                </span>
              </span>
              <span className="text-slate-400 dark:text-slate-500">
                {freshness}
              </span>
              {c.quality_flags.map((flag) => (
                <span
                  key={flag}
                  data-testid="citation-flag"
                  className="inline-flex items-center gap-0.5 rounded bg-amber-100 dark:bg-amber-900/30 px-1 py-0.5 text-[10px] font-medium text-amber-800 dark:text-amber-300"
                >
                  <Info className="h-2.5 w-2.5" aria-hidden="true" />
                  {flag}
                </span>
              ))}
            </div>
            <details className="mt-1 text-[11px]">
              <summary className="cursor-pointer text-slate-500 dark:text-slate-400 select-none">
                Parameters
              </summary>
              <pre className="mt-1 overflow-x-auto rounded bg-slate-100 dark:bg-surface-800 p-2 font-mono text-[10px] text-slate-700 dark:text-slate-300">
                {JSON.stringify(c.params, null, 2)}
              </pre>
            </details>
          </li>
        )
      })}
    </ol>
  )
}

export default CitationList
