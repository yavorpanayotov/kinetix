import { type ReactNode } from 'react'

interface StalePanelWrapperProps {
  /** Whether the underlying data is stale. */
  stale: boolean
  /** Optional ISO-8601 timestamp for when the metric was computed. */
  computedAt?: string
  /** Optional ISO-8601 timestamp for when the source data was sampled. */
  sourceAsOf?: string
  /** Optional extra classes applied to the outer container. */
  className?: string
  /** Optional data-testid override. Defaults to `stale-wrapper` when stale. */
  'data-testid'?: string
  /** Panel contents. */
  children: ReactNode
}

/**
 * Wraps a panel that may surface stale data.
 *
 * When `stale === false`, the wrapper is a transparent pass-through —
 * no styling, no provenance, no extra DOM nesting.
 *
 * When `stale === true`, the wrapper:
 *   - applies an amber tint + ring overlay so the staleness is impossible to miss
 *   - slightly desaturates the contents (90% opacity) to reinforce "do not trust this"
 *   - renders a provenance line ("Computed at … · Source as of …") when timestamps
 *     are provided, so the operator can judge how stale "stale" really is
 *
 * Designed to layer on top of existing in-panel STALE pills — additive, not a
 * replacement: redundancy is the point when a five-figure trading decision is
 * on the line.
 */
export function StalePanelWrapper({
  stale,
  computedAt,
  sourceAsOf,
  className = '',
  'data-testid': testId,
  children,
}: StalePanelWrapperProps) {
  if (!stale) {
    return <>{children}</>
  }

  const showProvenance = Boolean(computedAt || sourceAsOf)

  return (
    <div
      data-testid={testId ?? 'stale-wrapper'}
      className={`rounded-lg bg-amber-50/60 dark:bg-amber-900/10 ring-1 ring-amber-300 dark:ring-amber-600/40 p-2 ${className}`}
    >
      {showProvenance && (
        <div
          data-testid="stale-provenance"
          className="text-[11px] text-amber-800 dark:text-amber-300 mb-2 px-1"
        >
          {computedAt && (
            <span>
              Computed at: <span className="font-medium">{computedAt}</span>
            </span>
          )}
          {computedAt && sourceAsOf && (
            <span className="mx-1 text-amber-600 dark:text-amber-500">·</span>
          )}
          {sourceAsOf && (
            <span>
              Source as of: <span className="font-medium">{sourceAsOf}</span>
            </span>
          )}
        </div>
      )}
      <div className="opacity-90">{children}</div>
    </div>
  )
}
