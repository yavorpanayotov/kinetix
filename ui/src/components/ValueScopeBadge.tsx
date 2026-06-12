import { formatRelativeTime, formatTimestamp } from '../utils/format'

interface ValueScopeBadgeProps {
  /** What the number describes: 'Firm', a book id, a hierarchy node name… */
  scope: string
  /** ISO timestamp of the run that produced the number. */
  asOf?: string | null
  'data-testid'?: string
}

/**
 * Scope + freshness stamp for a risk figure.
 *
 * UX review: the platform showed eight different numbers all labelled bare
 * "VaR" — firm aggregate, per-book, scenario-stressed, model runs of
 * different vintages — and a reader could not tell which scope any of them
 * described. Every headline VaR figure carries one of these badges so the
 * scope and the as-of time travel with the number.
 */
export function ValueScopeBadge({ scope, asOf = null, 'data-testid': testId = 'value-scope-badge' }: ValueScopeBadgeProps) {
  return (
    <span data-testid={testId} className="inline-flex items-center gap-1 align-middle">
      <span
        data-testid={`${testId}-scope`}
        className="inline-flex items-center rounded bg-slate-200/70 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-slate-600 dark:bg-slate-700 dark:text-slate-300"
      >
        {scope}
      </span>
      {asOf && (
        <span
          data-testid={`${testId}-asof`}
          className="text-[10px] text-slate-400 dark:text-slate-500"
          title={formatTimestamp(asOf)}
        >
          {formatRelativeTime(asOf)}
        </span>
      )}
    </span>
  )
}
