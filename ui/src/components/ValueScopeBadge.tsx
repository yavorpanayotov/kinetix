import { formatRelativeTime, formatTimestamp, isOlderThanMinutes } from '../utils/format'

const DEFAULT_STALE_AFTER_MINUTES = 15

interface ValueScopeBadgeProps {
  /** What the number describes: 'Firm', a book id, a hierarchy node name… */
  scope: string
  /** ISO timestamp of the run that produced the number. */
  asOf?: string | null
  /**
   * Minutes after which the as-of stamp is styled as stale. A risk number
   * older than this is no longer "live" and the freshness stamp turns amber
   * so the reader does not mistake it for a current figure.
   */
  staleAfterMinutes?: number
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
export function ValueScopeBadge({
  scope,
  asOf = null,
  staleAfterMinutes = DEFAULT_STALE_AFTER_MINUTES,
  'data-testid': testId = 'value-scope-badge',
}: ValueScopeBadgeProps) {
  const isStale = asOf !== null && isOlderThanMinutes(asOf, staleAfterMinutes)

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
          className={`text-[10px] ${
            isStale
              ? 'text-amber-500 dark:text-amber-400'
              : 'text-slate-400 dark:text-slate-500'
          }`}
          title={isStale ? `${formatTimestamp(asOf)} — stale` : formatTimestamp(asOf)}
        >
          {formatRelativeTime(asOf)}
        </span>
      )}
    </span>
  )
}
