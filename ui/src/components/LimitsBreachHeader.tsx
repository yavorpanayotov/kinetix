import { AlertTriangle, Bell, CheckCircle2, ShieldAlert } from 'lucide-react'
import type { AlertEventDto } from '../types'

interface LimitsBreachHeaderProps {
  alerts: AlertEventDto[]
  onScrollToLimits?: () => void
  onShowAlerts?: () => void
}

const NEAR_BREACH_THRESHOLD = 0.8
const RECENT_WINDOW_MS = 30 * 60_000

function isActive(alert: AlertEventDto): boolean {
  // Treat anything that's not resolved/acknowledged as still active.
  return alert.status !== 'RESOLVED' && alert.status !== 'ACKNOWLEDGED'
}

function utilisation(alert: AlertEventDto): number | null {
  const current = Number(alert.currentValue)
  const threshold = Number(alert.threshold)
  if (!Number.isFinite(current) || !Number.isFinite(threshold) || threshold === 0) {
    return null
  }
  return current / threshold
}

function isBreach(alert: AlertEventDto): boolean {
  if (!isActive(alert)) return false
  const util = utilisation(alert)
  return util !== null && util > 1
}

function isNearBreach(alert: AlertEventDto): boolean {
  if (!isActive(alert)) return false
  const util = utilisation(alert)
  return util !== null && util >= NEAR_BREACH_THRESHOLD && util <= 1
}

function isRecentMaterial(alert: AlertEventDto): boolean {
  if (alert.severity !== 'WARNING' && alert.severity !== 'CRITICAL') return false
  const triggeredMs = new Date(alert.triggeredAt).getTime()
  if (!Number.isFinite(triggeredMs)) return false
  return Date.now() - triggeredMs <= RECENT_WINDOW_MS
}

export function LimitsBreachHeader({
  alerts,
  onScrollToLimits,
  onShowAlerts,
}: LimitsBreachHeaderProps) {
  // Count breached CONDITIONS (book + alert type), not alert events —
  // repeated firings and lower-severity shadows of the same condition must
  // not inflate the chip (UX review: "BREACHES 20" vs 3 books in breach).
  const conditionCount = (filtered: AlertEventDto[]) =>
    new Set(filtered.map((a) => `${a.bookId}|${a.type}`)).size

  const breaches = alerts.filter(isBreach)
  const nearBreaches = alerts.filter(isNearBreach)
  const recent = alerts.filter(isRecentMaterial)
  const breachCount = conditionCount(breaches)
  const nearBreachCount = conditionCount(nearBreaches)

  const allClear =
    breaches.length === 0 && nearBreaches.length === 0 && recent.length === 0

  return (
    <div
      data-testid="limits-breach-header"
      className="sticky top-0 z-20 -mx-1 mb-3 flex flex-wrap items-center gap-3 rounded-md border border-slate-200 dark:border-slate-700 bg-white/95 dark:bg-slate-900/95 backdrop-blur px-3 py-2 shadow-sm"
    >
      <button
        type="button"
        data-testid="breach-chip"
        onClick={onScrollToLimits}
        disabled={!onScrollToLimits}
        className={`flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors ${
          breaches.length > 0
            ? 'bg-red-50 text-red-700 hover:bg-red-100 dark:bg-red-900/30 dark:text-red-300 dark:hover:bg-red-900/50'
            : 'bg-slate-50 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
        } ${onScrollToLimits ? 'cursor-pointer' : 'cursor-default'} disabled:cursor-default`}
      >
        <ShieldAlert
          className={`h-3.5 w-3.5 ${breaches.length > 0 ? 'text-red-600 dark:text-red-400' : 'text-slate-400'}`}
          aria-hidden="true"
        />
        <span className="uppercase tracking-wide text-[10px] text-slate-500 dark:text-slate-400">
          Breaches
        </span>
        <span data-testid="breach-count" className="font-mono tabular-nums">
          {breachCount}
        </span>
      </button>

      <button
        type="button"
        data-testid="near-breach-chip"
        onClick={onScrollToLimits}
        disabled={!onScrollToLimits}
        className={`flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors ${
          nearBreaches.length > 0
            ? 'bg-amber-50 text-amber-700 hover:bg-amber-100 dark:bg-amber-900/30 dark:text-amber-300 dark:hover:bg-amber-900/50'
            : 'bg-slate-50 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
        } ${onScrollToLimits ? 'cursor-pointer' : 'cursor-default'} disabled:cursor-default`}
      >
        <AlertTriangle
          className={`h-3.5 w-3.5 ${nearBreaches.length > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-slate-400'}`}
          aria-hidden="true"
        />
        <span className="uppercase tracking-wide text-[10px] text-slate-500 dark:text-slate-400">
          Near-breach
        </span>
        <span data-testid="near-breach-count" className="font-mono tabular-nums">
          {nearBreachCount}
        </span>
      </button>

      <button
        type="button"
        data-testid="recent-alert-chip"
        onClick={onShowAlerts}
        disabled={!onShowAlerts}
        className={`flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium transition-colors ${
          recent.length > 0
            ? 'bg-slate-100 text-slate-700 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700'
            : 'bg-slate-50 text-slate-500 dark:bg-slate-800 dark:text-slate-400'
        } ${onShowAlerts ? 'cursor-pointer' : 'cursor-default'} disabled:cursor-default`}
      >
        <Bell
          className={`h-3.5 w-3.5 ${recent.length > 0 ? 'text-slate-600 dark:text-slate-300' : 'text-slate-400'}`}
          aria-hidden="true"
        />
        <span className="uppercase tracking-wide text-[10px] text-slate-500 dark:text-slate-400">
          Recent alerts (30m)
        </span>
        <span data-testid="recent-alert-count" className="font-mono tabular-nums">
          {recent.length}
        </span>
      </button>

      {allClear && (
        <span
          data-testid="limits-breach-header-all-clear"
          className="ml-auto flex items-center gap-1 text-xs text-green-600 dark:text-green-400"
        >
          <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
          All clear
        </span>
      )}
    </div>
  )
}
