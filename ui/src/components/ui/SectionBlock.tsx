import { useState, type ReactNode } from 'react'

interface SectionBlockProps {
  /** Heading label shown in the section header. */
  title: string
  /** Initial open state when uncontrolled. Defaults to `true`. */
  defaultOpen?: boolean
  /** Controlled open state. When provided, internal state is ignored. */
  open?: boolean
  /** Callback fired with the next open state when the header is toggled. */
  onToggle?: (open: boolean) => void
  /** Optional element rendered on the right of the header (e.g. count badge, button). */
  right?: ReactNode
  /** Section body content. Rendered only when the section is open. */
  children: ReactNode
  /** Optional extra classes applied to the outer container. */
  className?: string
  /** Optional data-testid for end-to-end tests. */
  'data-testid'?: string
}

/**
 * Collapsible section with a clickable header, chevron, and optional right slot.
 *
 * Used to group dense risk-dashboard panels into named, persistently-collapsible
 * regions. Supports both uncontrolled (`defaultOpen`) and controlled (`open` +
 * `onToggle`) modes so callers can persist the collapse state to workspace
 * preferences.
 */
export function SectionBlock({
  title,
  defaultOpen = true,
  open,
  onToggle,
  right,
  children,
  className = '',
  'data-testid': testId,
}: SectionBlockProps) {
  const [internalOpen, setInternalOpen] = useState(defaultOpen)
  const isControlled = open !== undefined
  const isOpen = isControlled ? open : internalOpen

  const handleToggle = () => {
    const next = !isOpen
    if (!isControlled) {
      setInternalOpen(next)
    }
    onToggle?.(next)
  }

  return (
    <div
      className={`rounded-lg border border-slate-200 dark:border-surface-700 bg-white dark:bg-surface-800 ${className}`}
      data-testid={testId}
    >
      <div className="flex items-center justify-between gap-2 px-3 py-2 border-b border-slate-100 dark:border-surface-700">
        <button
          type="button"
          onClick={handleToggle}
          aria-expanded={isOpen}
          className="flex items-center gap-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:text-slate-900 dark:hover:text-slate-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500 rounded"
        >
          <span
            aria-hidden="true"
            className={`inline-block transition-transform text-slate-400 ${isOpen ? 'rotate-90' : ''}`}
          >
            {/* simple chevron-right; CSS rotation indicates open state */}
            ▶
          </span>
          <span>{title}</span>
        </button>
        {right && <div className="flex items-center gap-2">{right}</div>}
      </div>
      {isOpen && <div className="px-3 py-3">{children}</div>}
    </div>
  )
}
