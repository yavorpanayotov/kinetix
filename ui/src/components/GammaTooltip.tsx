// GammaTooltip — explains the option Gamma sign convention in one hover
// (kx-d4m6).
//
// Gamma is the second-order Greek measuring the rate of change of delta
// with respect to the underlying price. Long option positions have
// positive gamma (they benefit from a moving market) while short positions
// have negative gamma (they bleed in volatile conditions). Surfacing
// "Rate of change of delta; >0 benefits from volatility" next to the
// Gamma column keeps the sign convention anchored without leaving the
// page.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface GammaTooltipProps {
  children: ReactNode
}

const TOOLTIP_MESSAGE = 'Rate of change of delta; >0 benefits from volatility'

export function GammaTooltip({ children }: GammaTooltipProps) {
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
      data-testid="gamma-tooltip-trigger"
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
          data-testid="gamma-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-64 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          {TOOLTIP_MESSAGE}
        </span>
      )}
    </span>
  )
}
