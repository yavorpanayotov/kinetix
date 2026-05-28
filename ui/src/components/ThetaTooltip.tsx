// ThetaTooltip — explains the option Theta convention in one hover
// (kx-oqog).
//
// Theta measures the change in option value per unit of time, conventionally
// expressed per calendar day. The sign trips junior traders up: long
// option positions have negative theta (they bleed value as expiry
// approaches), while short option positions have positive theta (they
// collect that decay). The canonical one-line phrasing —
// "Daily P&L from time passage (positive = long decay)" — anchors the
// convention next to the Theta column header without leaving the page.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface ThetaTooltipProps {
  children: ReactNode
}

const TOOLTIP_MESSAGE = 'Daily P&L from time passage (positive = long decay)'

export function ThetaTooltip({ children }: ThetaTooltipProps) {
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
      data-testid="theta-tooltip-trigger"
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
          data-testid="theta-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-64 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          {TOOLTIP_MESSAGE}
        </span>
      )}
    </span>
  )
}
