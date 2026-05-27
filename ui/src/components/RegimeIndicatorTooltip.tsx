// RegimeIndicatorTooltip — explains the regime and its applied adjustments
// (kx-ek9v).
//
// The platform tags each scenario run with a market regime (e.g. "Risk-Off",
// "Calm", "Tightening Cycle"). Each regime carries two adjustments applied
// before pricing:
//
//   volMultiplier      — scales all implied vols (1.5 = +50% across the surface)
//   correlationShift   — shifts the off-diagonal correlations toward the
//                        regime's empirical mean by this fraction
//
// Traders see the regime name next to the run summary, but the actual
// adjustments are buried in the run manifest. This tooltip surfaces a
// one-line summary on hover/focus so the user understands why their stress
// numbers moved without digging into manifests.

import { useId, useRef, useState, type ReactNode } from 'react'

export interface RegimeIndicatorTooltipProps {
  regimeName: string
  volMultiplier: number
  correlationShift: number
  children: ReactNode
}

export function RegimeIndicatorTooltip({
  regimeName,
  volMultiplier,
  correlationShift,
  children,
}: RegimeIndicatorTooltipProps) {
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
      data-testid="regime-indicator-tooltip-trigger"
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
          data-testid="regime-indicator-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-72 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          <div className="font-semibold mb-1">{regimeName}</div>
          <div>Vol multiplier: {volMultiplier}×</div>
          <div>Correlation shift: {correlationShift}</div>
        </span>
      )}
    </span>
  )
}
