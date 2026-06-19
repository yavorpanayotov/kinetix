import { useMemo, useState } from 'react'
import { useNotifications } from '../../hooks/useNotifications'
import { Badge } from '../ui/Badge'
import { formatNum, formatRelativeTime, formatTimestamp } from '../../utils/format'
import type { AlertEventDto } from '../../types'

// Plan §mobile — the phone-first Alerts view. A read-only stream of the same
// alerts the desktop NotificationCenter shows, distilled for a phone: a
// vertical list of cards where the WHOLE card carries the severity colour (the
// UX spec wants severity to read at arm's length, not via a thin border), a
// status badge, the book, the breach magnitude, and how long ago it fired.
// Tapping a card opens a single-alert detail panel; there are deliberately NO
// acknowledge / escalate / resolve / snooze controls — triage stays on the
// desktop. Severity and status colours are derived from the SAME vocabulary as
// NotificationCenter (CRITICAL=red, WARNING=amber/yellow, INFO=blue) so the two
// surfaces never disagree on what a colour means.

interface MobileAlertsViewProps {
  // The authenticated username, threaded through to useNotifications exactly as
  // App.tsx does (`useNotifications(auth.username)`). Optional so the view can
  // be rendered standalone; the hook only needs it for the lifecycle actions
  // this read-only view never calls.
  username?: string | null
}

// Severity ranking — mirrors `severityOrder` in NotificationCenter.tsx so the
// mobile list sorts critical-first identically to the desktop.
const severityOrder: Record<string, number> = {
  CRITICAL: 0,
  WARNING: 1,
  INFO: 2,
}

// Full-card background per severity. The colour FAMILY matches the desktop
// `severityBorderColor` mapping in NotificationCenter.tsx (CRITICAL=red,
// WARNING=yellow, INFO=blue) — here applied as a tinted card fill rather than a
// left border, per the mobile UX spec.
const severityCardClass: Record<string, string> = {
  CRITICAL:
    'bg-red-50 border-red-300 dark:bg-red-900/30 dark:border-red-800',
  WARNING:
    'bg-yellow-50 border-yellow-300 dark:bg-yellow-900/30 dark:border-yellow-800',
  INFO: 'bg-blue-50 border-blue-300 dark:bg-blue-900/30 dark:border-blue-800',
}

const DEFAULT_CARD_CLASS =
  'bg-white border-slate-200 dark:bg-surface-800 dark:border-slate-700'

// Reuse the desktop status-badge palette via the shared Badge variants
// (NotificationCenter.tsx maps the same four statuses to these colours).
const statusVariant: Record<string, 'critical' | 'info' | 'warning' | 'success'> = {
  TRIGGERED: 'critical',
  ACKNOWLEDGED: 'info',
  ESCALATED: 'warning',
  RESOLVED: 'success',
}

function severityRank(severity: string): number {
  return severityOrder[severity] ?? severityOrder.INFO
}

function cardClass(severity: string): string {
  return severityCardClass[severity] ?? DEFAULT_CARD_CLASS
}

export function MobileAlertsView({ username = null }: MobileAlertsViewProps) {
  const { alerts, loading, error, connected } = useNotifications(username)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  // Sort by severity (critical first) then recency, mirroring the desktop list.
  const sorted = useMemo(() => {
    return [...alerts].sort((a, b) => {
      const bySeverity = severityRank(a.severity) - severityRank(b.severity)
      if (bySeverity !== 0) return bySeverity
      return (
        new Date(b.triggeredAt).getTime() - new Date(a.triggeredAt).getTime()
      )
    })
  }, [alerts])

  const selected = selectedId
    ? sorted.find((a) => a.id === selectedId) ?? null
    : null

  if (loading && alerts.length === 0) {
    return (
      <div
        data-testid="mobile-alerts-loading"
        className="flex items-center justify-center py-16 text-sm text-slate-500 dark:text-slate-400"
      >
        Loading alerts…
      </div>
    )
  }

  if (sorted.length === 0) {
    // An empty list only means "all quiet" if the feed is actually healthy.
    // If the live stream has dropped (or the snapshot fetch errored), newly
    // raised alerts may be silently missing — so we must NOT reassure. Show an
    // amber feed-health warning instead, so a trader can tell "no alerts" apart
    // from "feed is broken". `connected` comes from useAlertStream via
    // useNotifications; `error` is the REST snapshot failure.
    const feedHealthy = connected && !error
    if (!feedHealthy) {
      return (
        <div
          data-testid="mobile-alerts-empty"
          className="flex flex-col items-center justify-center gap-1 py-16 text-center text-amber-700 dark:text-amber-400"
        >
          <p className="text-sm font-medium">Alert feed unavailable</p>
          <p className="text-xs text-amber-700 dark:text-amber-500">
            Can't confirm there are no alerts — check your connection.
          </p>
        </div>
      )
    }
    return (
      <div
        data-testid="mobile-alerts-empty"
        className="flex flex-col items-center justify-center gap-1 py-16 text-center"
      >
        <p className="text-sm font-medium text-slate-600 dark:text-slate-300">
          No active alerts
        </p>
        <p className="text-xs text-slate-400 dark:text-slate-400">
          You're all caught up.
        </p>
        <span
          data-testid="mobile-alerts-feed-status"
          className="mt-2 inline-flex items-center gap-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400"
        >
          <span
            aria-hidden="true"
            className="h-1.5 w-1.5 rounded-full bg-emerald-500"
          />
          Feed live
        </span>
      </div>
    )
  }

  return (
    <div data-testid="mobile-alerts-view" className="flex flex-col gap-3">
      {sorted.map((alert) => (
        <AlertCard
          key={alert.id}
          alert={alert}
          onSelect={() => setSelectedId(alert.id)}
        />
      ))}

      {selected && (
        <AlertDetail alert={selected} onClose={() => setSelectedId(null)} />
      )}
    </div>
  )
}

interface AlertCardProps {
  alert: AlertEventDto
  onSelect: () => void
}

function AlertCard({ alert, onSelect }: AlertCardProps) {
  return (
    <button
      type="button"
      data-testid={`mobile-alert-card-${alert.id}`}
      onClick={onSelect}
      className={`w-full text-left rounded-lg border p-3 ${cardClass(alert.severity)}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-bold uppercase tracking-wide text-slate-700 dark:text-slate-200">
          {alert.severity}
        </span>
        <Badge variant={statusVariant[alert.status] ?? 'neutral'}>
          {alert.status}
        </Badge>
      </div>

      <p className="mt-1 text-sm font-medium text-slate-900 dark:text-slate-100">
        {alert.ruleName}
      </p>

      <div className="mt-2 flex items-center justify-between gap-2 text-xs text-slate-500 dark:text-slate-400">
        <span className="font-mono tabular-nums">
          {formatNum(alert.currentValue)} / {formatNum(alert.threshold)}
        </span>
        <span>{formatRelativeTime(alert.triggeredAt)}</span>
      </div>

      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
        {alert.bookId}
      </p>
    </button>
  )
}

interface AlertDetailProps {
  alert: AlertEventDto
  onClose: () => void
}

function AlertDetail({ alert, onClose }: AlertDetailProps) {
  return (
    <div
      data-testid="mobile-alert-detail"
      className="fixed inset-0 z-40 flex flex-col bg-white dark:bg-surface-900"
    >
      <header className="flex items-center justify-between gap-2 border-b border-slate-200 dark:border-slate-700 p-4">
        <div className="flex items-center gap-2">
          <span className="text-xs font-bold uppercase tracking-wide text-slate-700 dark:text-slate-200">
            {alert.severity}
          </span>
          <Badge variant={statusVariant[alert.status] ?? 'neutral'}>
            {alert.status}
          </Badge>
        </div>
        <button
          type="button"
          data-testid="mobile-alert-detail-close"
          onClick={onClose}
          aria-label="Close alert detail"
          className="rounded px-3 py-2 min-h-[44px] text-sm font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
        >
          Close
        </button>
      </header>

      <div className="flex-1 overflow-y-auto p-4">
        <div className={`rounded-lg border p-3 ${cardClass(alert.severity)}`}>
          <p className="text-sm font-semibold text-slate-900 dark:text-slate-100">
            {alert.ruleName}
          </p>
          <p className="mt-1 text-sm text-slate-700 dark:text-slate-300">
            {alert.message}
          </p>
        </div>

        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
          <Field label="Book" value={alert.bookId} />
          <Field label="Type" value={alert.type} />
          <Field label="Current" value={formatNum(alert.currentValue)} mono />
          <Field label="Threshold" value={formatNum(alert.threshold)} mono />
          <Field
            label="Triggered"
            value={`${formatRelativeTime(alert.triggeredAt)} (${formatTimestamp(alert.triggeredAt)})`}
          />
          {alert.escalatedTo && (
            <Field label="Escalated to" value={alert.escalatedTo} />
          )}
          {alert.resolvedReason && (
            <Field label="Resolution" value={alert.resolvedReason} />
          )}
        </dl>

        {alert.suggestedAction && (
          <div className="mt-4">
            <p className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
              Suggested action
            </p>
            <p
              data-testid="mobile-alert-detail-suggested-action"
              className="mt-0.5 text-sm text-slate-700 dark:text-slate-300"
            >
              {alert.suggestedAction}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

interface FieldProps {
  label: string
  value: string
  mono?: boolean
}

function Field({ label, value, mono = false }: FieldProps) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-slate-400 dark:text-slate-500">
        {label}
      </dt>
      <dd
        className={`mt-0.5 text-slate-700 dark:text-slate-200 ${mono ? 'font-mono tabular-nums' : ''}`}
      >
        {value}
      </dd>
    </div>
  )
}
