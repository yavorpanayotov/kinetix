import { type ReactNode } from 'react'

type HeadingTag = 'h2' | 'h3' | 'h4'

interface SectionHeadingProps {
  /** Heading text / inline content. */
  children: ReactNode
  /** Semantic heading level. Defaults to `h3`. */
  as?: HeadingTag
  /**
   * Optional content rendered to the right of the heading (e.g. count badge,
   * action button). When supplied, the component wraps the heading and the
   * right slot in a flex row.
   */
  right?: ReactNode
  /** Optional extra classes appended to the heading element. */
  className?: string
  /** Optional data-testid for end-to-end tests. */
  'data-testid'?: string
}

const CANONICAL_HEADING_CLASSES =
  'text-base font-semibold text-slate-700 dark:text-slate-200'

/**
 * Canonical section heading for dashboard panels and cards.
 *
 * Consolidates three legacy `text-sm/text-base/text-lg font-semibold` patterns
 * (BookSummaryCard, CounterpartyRiskDashboard, ReportsTab, SystemDashboard)
 * into a single design-system primitive so the same semantic role is rendered
 * one way across the app.
 */
export function SectionHeading({
  children,
  as: Tag = 'h3',
  right,
  className = '',
  'data-testid': testId,
}: SectionHeadingProps) {
  const headingClassName = `${CANONICAL_HEADING_CLASSES} ${className}`.trim()

  if (right) {
    return (
      <div className="flex items-center justify-between gap-2">
        <Tag className={headingClassName} data-testid={testId}>
          {children}
        </Tag>
        <div className="flex items-center gap-2">{right}</div>
      </div>
    )
  }

  return (
    <Tag className={headingClassName} data-testid={testId}>
      {children}
    </Tag>
  )
}
