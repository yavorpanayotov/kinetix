// DeltaTooltip — explains the first-order Delta Greek in one hover
// (kx-6zu1).
//
// Delta is the first-order Greek measuring sensitivity of option value to
// the underlying price, frequently read as "shares-equivalent exposure".
// Junior traders sometimes misread 0.50 delta as "50% of the notional
// moves." The canonical one-line phrasing — "Estimated profit/loss per
// 1% underlying move" — anchors the unit next to the Delta column header
// without leaving the page.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface DeltaTooltipProps {
  children: ReactNode
}

const TOOLTIP_MESSAGE = 'Estimated profit/loss per 1% underlying move'

export function DeltaTooltip({ children }: DeltaTooltipProps) {
  const [open, setOpen] = useState(false)
  const tooltipId = useId()
  const blurTimeout = useRef<number | null>(null)

  function handleKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') {
      setOpen(false)
    }
  }

  function clearBlurTimeout() {
    if (blurTimeout.current !== null) {
      window.clearTimeout(blurTimeout.current)
      blurTimeout.current = null
    }
  }

  return (
    <span
      data-testid="delta-tooltip-trigger"
      tabIndex={0}
      role="button"
      aria-describedby={open ? tooltipId : undefined}
      className="relative inline-block cursor-help border-b border-dotted border-slate-400"
      onMouseEnter={() => {
        clearBlurTimeout()
        setOpen(true)
      }}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => {
        clearBlurTimeout()
        setOpen(true)
      }}
      onBlur={() => setOpen(false)}
      onKeyDown={handleKeyDown}
    >
      {children}
      {open && (
        <span
          id={tooltipId}
          role="tooltip"
          data-testid="delta-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-64 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          {TOOLTIP_MESSAGE}
        </span>
      )}
    </span>
  )
}
