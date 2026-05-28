// VegaTooltip — explains Vega's unit convention in one hover (kx-buz7).
//
// Vega is the option Greek that measures the change in option value per
// unit move in implied volatility. The unit is the standard confusion:
// "1 vol point" can be read as 1 percentage point or 1 basis point
// depending on vendor convention. Surfacing the canonical phrasing —
// "Per 1 percentage point (pp) in implied volatility" — pins down the
// scale next to the Vega column header without leaving the page.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface VegaTooltipProps {
  children: ReactNode
}

const TOOLTIP_MESSAGE = 'Per 1 percentage point (pp) in implied volatility'

export function VegaTooltip({ children }: VegaTooltipProps) {
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
      data-testid="vega-tooltip-trigger"
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
          data-testid="vega-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-64 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          {TOOLTIP_MESSAGE}
        </span>
      )}
    </span>
  )
}
