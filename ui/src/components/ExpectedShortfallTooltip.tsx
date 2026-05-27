// ExpectedShortfallTooltip — explains the Expected Shortfall (ES / CVaR)
// metric in one hover (kx-lwc6).
//
// Expected Shortfall is the average loss on the worst X% of trading days,
// conditional on a VaR breach. It is the regulator-preferred tail-risk
// metric under FRTB, but most operators encounter it less often than VaR
// and are unsure how to read it. This tooltip surfaces the canonical
// one-line definition so the ES column header and any ES badge can be
// understood without leaving the page.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface ExpectedShortfallTooltipProps {
  children: ReactNode
}

const TOOLTIP_MESSAGE = 'Average loss on worst X% of days'

export function ExpectedShortfallTooltip({
  children,
}: ExpectedShortfallTooltipProps) {
  const [open, setOpen] = useState(false)
  const tooltipId = useId()
  // Track focus/blur timing so blur after a focus-open and mouseleave after
  // a hover-open both close cleanly without fighting each other.
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
      data-testid="expected-shortfall-tooltip-trigger"
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
          data-testid="expected-shortfall-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-64 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          {TOOLTIP_MESSAGE}
        </span>
      )}
    </span>
  )
}
