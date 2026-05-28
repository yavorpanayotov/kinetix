// VarConfidenceTooltip — explains Value-at-Risk's confidence-level phrasing
// in one hover (kx-r4ak).
//
// "95% VaR" frequently gets misread as "95% chance of losing this much" when
// it actually means "the loss exceeded on the worst 5% of days, over a
// 1-day horizon". This tooltip surfaces the canonical one-line phrasing —
// "X% of days, max loss ≤ Y over 1d horizon" — next to the VaR column
// header so the metric can be read without leaving the page.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface VarConfidenceTooltipProps {
  children: ReactNode
}

const TOOLTIP_MESSAGE = 'X% of days, max loss ≤ Y over 1d horizon'

export function VarConfidenceTooltip({ children }: VarConfidenceTooltipProps) {
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
      data-testid="var-confidence-tooltip-trigger"
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
          data-testid="var-confidence-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-64 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          {TOOLTIP_MESSAGE}
        </span>
      )}
    </span>
  )
}
