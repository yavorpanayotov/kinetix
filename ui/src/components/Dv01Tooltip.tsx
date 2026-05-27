// Dv01Tooltip — explains why DV01 shows an em-dash for non-rates positions
// (kx-29ry).
//
// DV01 (dollar value of a basis point) measures sensitivity to a 1bp shift
// in the yield curve. It only applies to interest-rate instruments — bonds,
// swaps, futures, caps/floors, swaptions. Equity and FX positions have no
// curve exposure, so the DV01 cell shows "—". Without an explainer, junior
// traders frequently raise tickets thinking the dash is a missing value or a
// bug. This tooltip puts the canonical answer one hover away.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface Dv01TooltipProps {
  children: ReactNode
}

const TOOLTIP_MESSAGE = 'Rates-only metric; shows — for equities/FX'

export function Dv01Tooltip({ children }: Dv01TooltipProps) {
  const [open, setOpen] = useState(false)
  const tooltipId = useId()
  // Track which mode the popup opened in so blur after a focus open and
  // mouseleave after a hover open both close cleanly without fighting.
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
      data-testid="dv01-tooltip-trigger"
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
          data-testid="dv01-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-64 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          {TOOLTIP_MESSAGE}
        </span>
      )}
    </span>
  )
}
