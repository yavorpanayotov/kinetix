// ScenarioBadgeTooltip — surfaces the scenario name and shock summary on
// hover/focus of a stress-scenario badge (kx-qtdb).
//
// Stress scenario badges (e.g. "2020 COVID Shock") appear across the risk
// pages — on tiles, in alert banners, on PnL drill-downs. A risk manager
// reviewing a screen of badges needs to know what each shock actually
// contains without leaving the page: which factors moved, by how much.
//
// The tooltip mirrors the keyboard and ARIA behaviour of `Dv01Tooltip` so
// the component family stays consistent: hover OR focus to open, mouse-leave
// OR blur to close, Escape dismisses, `aria-describedby` is wired only while
// the tooltip is open. The shock list is rendered as a definition list so
// screen readers announce "factor — magnitude" pairs cleanly.

import { useId, useState, type ReactNode } from 'react'

export interface ScenarioShock {
  factor: string
  magnitude: string
}

export interface ScenarioSummary {
  name: string
  shocks: readonly ScenarioShock[]
}

export interface ScenarioBadgeTooltipProps {
  scenario: ScenarioSummary
  children: ReactNode
}

export function ScenarioBadgeTooltip({
  scenario,
  children,
}: ScenarioBadgeTooltipProps) {
  const [open, setOpen] = useState(false)
  const tooltipId = useId()

  function handleKeyDown(event: React.KeyboardEvent) {
    if (event.key === 'Escape') {
      setOpen(false)
    }
  }

  return (
    <span
      data-testid="scenario-badge-tooltip-trigger"
      tabIndex={0}
      role="button"
      aria-describedby={open ? tooltipId : undefined}
      className="relative inline-block cursor-help border-b border-dotted border-slate-400"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      onKeyDown={handleKeyDown}
    >
      {children}
      {open && (
        <span
          id={tooltipId}
          role="tooltip"
          data-testid="scenario-badge-tooltip"
          className="absolute left-0 top-full mt-1 z-50 w-72 p-3 rounded-md border bg-white dark:bg-surface-800 border-slate-200 dark:border-slate-700 shadow-md text-xs text-slate-700 dark:text-slate-300"
        >
          <strong className="block mb-1 text-sm">{scenario.name}</strong>
          {scenario.shocks.length === 0 ? (
            <span className="italic text-slate-500">No shocks</span>
          ) : (
            <dl className="space-y-0.5">
              {scenario.shocks.map((shock, idx) => (
                <div key={`${shock.factor}-${idx}`} className="flex justify-between gap-2">
                  <dt className="font-medium">{shock.factor}</dt>
                  <dd className="tabular-nums">{shock.magnitude}</dd>
                </div>
              ))}
            </dl>
          )}
        </span>
      )}
    </span>
  )
}
