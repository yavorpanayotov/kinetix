import type { ReactNode } from 'react'

// Accessible "no data" empty-state component (kx-67nh).
//
// Tables and panels frequently fall back to "No data" or a blank surface
// when their query returns nothing. Sighted users see the gap and infer
// the absence; screen-reader users hear silence and cannot tell whether
// the surface is empty, still loading, or broken. Wrapping the visible
// message inside an element annotated with role="status" promotes the
// empty state to a live region, so the message is announced when it
// arrives without the user having to hunt for it.
//
// The component is deliberately content-thin: a heading, a body line,
// and an optional secondary action. Callers supply their own copy so the
// hint can be context-appropriate ("No positions today" vs. "No alerts
// in the last 24 hours").

interface AccessibleEmptyStateProps {
  /** Visible headline. Also used as the accessible name when no heading slot. */
  title: string
  /** Optional supporting copy below the headline. */
  message?: ReactNode
  /** Optional call to action — e.g. a "Clear filters" button. */
  action?: ReactNode
  /**
   * Override the announced label. Defaults to `${title}. ${message}` so a
   * screen reader hears the same thing the operator reads.
   */
  ariaLabel?: string
  className?: string
}

/**
 * Render an empty-state block with a polite live region so assistive tech
 * announces the "no data" message as soon as it appears.
 */
export function AccessibleEmptyState({
  title,
  message,
  action,
  ariaLabel,
  className,
}: AccessibleEmptyStateProps) {
  const composedLabel =
    ariaLabel ??
    (typeof message === 'string' && message.length > 0 ? `${title}. ${message}` : title)

  return (
    <div
      data-testid="accessible-empty-state"
      role="status"
      aria-live="polite"
      aria-label={composedLabel}
      className={
        'flex flex-col items-center justify-center gap-2 py-8 px-4 text-center ' +
        'text-slate-600 dark:text-slate-300 ' +
        (className ?? '')
      }
    >
      <p className="text-sm font-medium text-slate-700 dark:text-slate-200">
        {title}
      </p>
      {message ? (
        <p className="text-xs text-slate-500 dark:text-slate-400 max-w-md">
          {message}
        </p>
      ) : null}
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  )
}
