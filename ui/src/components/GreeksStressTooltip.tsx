// GreeksStressTooltip — explains regime-adjusted Greeks figures by listing
// the stress factors that were applied (kx-7xic).
//
// When a market-regime stress is active (low-vol → high-vol regime, calm →
// crisis), Greeks shown on the dashboard are no longer raw — they reflect
// the regime's stress factors (vega multiplier, gamma multiplier, vol
// shift, ...). Traders need to know which factors were applied to a given
// figure without leaving the row they are looking at. This tooltip puts
// that audit trail one hover away.
//
// Factor shape:
//   - `multiplier` factors render as "<label> × <value>" (e.g. "vega × 1.4").
//   - `shift` factors render as "<label> ±N%" (e.g. "vol shift +5%"). A
//     shift of 0.05 displays as +5%, a shift of -0.03 displays as -3% (no
//     leading + on negative numbers — the minus is part of the formatted
//     value).
//
// Keyboard, focus, and aria behaviour mirror the existing Dv01Tooltip so
// users have a consistent mental model across tooltip-style annotations.

import { useId, useRef, useState, type ReactNode } from 'react'

export type StressFactor =
  | { label: string; multiplier: number }
  | { label: string; shift: number }

export interface GreeksStressTooltipProps {
  factors: StressFactor[]
  children: ReactNode
}

const HEADER = 'Greeks adjusted for market regime'
const NO_FACTORS = 'No stress factors applied'

function formatFactor(factor: StressFactor): string {
  if ('multiplier' in factor) {
    return `${factor.label} × ${factor.multiplier}`
  }
  // Shifts are stored as fractions (0.05 → 5%); render as a signed percent.
  const pct = factor.shift * 100
  if (pct >= 0) {
    // Use a plus sign for non-negative values so traders can read the
    // direction at a glance. A zero shift still renders with a leading + so
    // the format is uniform with positive shifts.
    return `${factor.label} +${pct}%`
  }
  return `${factor.label} ${pct}%`
}

export function GreeksStressTooltip({
  factors,
  children,
}: GreeksStressTooltipProps) {
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
      data-testid="greeks-stress-tooltip-trigger"
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
          data-testid="greeks-stress-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-72 p-2 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          <span className="block font-medium">{HEADER}</span>
          {factors.length === 0 ? (
            <span className="block mt-1 italic">{NO_FACTORS}</span>
          ) : (
            <ul className="mt-1 list-none p-0">
              {factors.map((factor) => (
                <li key={factor.label}>{formatFactor(factor)}</li>
              ))}
            </ul>
          )}
        </span>
      )}
    </span>
  )
}
