import { AlertTriangle, X, XCircle } from 'lucide-react'
import type { AlertEventDto } from '../types'
import { formatAlertMessage } from '../utils/alertMessageFormatter'
import { isStaleAlert } from '../utils/alertStaleness'

interface RiskAlertBannerProps {
  alerts: AlertEventDto[]
  onDismiss: (id: string) => void
  /** When supplied, the overflow line becomes a real link to the Alerts tab. */
  onViewAll?: () => void
}

function formatRelativeTime(triggeredAt: string): string {
  const diffMs = Date.now() - new Date(triggeredAt).getTime()
  const diffSeconds = Math.floor(diffMs / 1000)

  if (diffSeconds < 60) return 'just now'

  const diffMinutes = Math.floor(diffSeconds / 60)
  if (diffMinutes < 60) return `${diffMinutes} min ago`

  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours} hours ago`

  const diffDays = Math.floor(diffHours / 24)
  return `${diffDays} days ago`
}

function alertStyles(severity: string, stale: boolean) {
  // Dark-mode variants match the "Need a hedge?" button next to this banner
  // — without them the body text inherits the page's near-white slate-100
  // colour over a near-white bg-*-50 fill and becomes invisible.
  // Stale variants drop the filled background to an outline so an hours-old
  // alert stays legible without competing with a fresh one.
  switch (severity) {
    case 'CRITICAL':
      return {
        container: stale
          ? 'border-red-300/60 bg-transparent text-red-800/90 dark:border-red-800/60 dark:text-red-300/90'
          : 'border-red-200 bg-red-50 text-red-900 dark:border-red-700 dark:bg-red-900/30 dark:text-red-200',
        icon: <XCircle className="h-4 w-4 text-red-500 shrink-0" />,
      }
    case 'WARNING':
      return {
        container: stale
          ? 'border-amber-300/60 bg-transparent text-amber-800/90 dark:border-amber-800/60 dark:text-amber-300/90'
          : 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200',
        icon: <AlertTriangle className="h-4 w-4 text-amber-500 shrink-0" />,
      }
    default:
      return {
        container:
          'border-slate-200 bg-slate-50 text-slate-800 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200',
        icon: <AlertTriangle className="h-4 w-4 text-slate-500 shrink-0" />,
      }
  }
}

export function RiskAlertBanner({ alerts, onDismiss, onViewAll }: RiskAlertBannerProps) {
  if (alerts.length === 0) return null

  const visible = alerts.slice(0, 3)
  const hasMore = alerts.length > 3

  return (
    <div data-testid="risk-alert-banner" className="space-y-2">
      {visible.map((alert) => {
        const isStale = isStaleAlert(alert.triggeredAt)
        const styles = alertStyles(alert.severity, isStale)
        return (
          <div
            key={alert.id}
            data-testid={`alert-item-${alert.id}`}
            data-stale={isStale}
            role={alert.severity === 'CRITICAL' ? 'alert' : undefined}
            aria-label={formatAlertMessage(alert)}
            className={`flex items-center gap-3 rounded-lg border px-4 py-2 ${styles.container}`}
          >
            {styles.icon}
            <span className="flex-1 text-sm">{formatAlertMessage(alert)}</span>
            <span className="text-xs text-slate-500 shrink-0">
              {formatRelativeTime(alert.triggeredAt)}
            </span>
            <button
              data-testid={`alert-dismiss-${alert.id}`}
              onClick={() => onDismiss(alert.id)}
              className="p-1 rounded hover:bg-black/5"
            >
              <X className="h-3.5 w-3.5 text-slate-400" />
            </button>
          </div>
        )
      })}
      {hasMore &&
        (onViewAll ? (
          <p className="text-xs text-center">
            <button
              type="button"
              data-testid="risk-alert-banner-view-all"
              onClick={onViewAll}
              className="font-medium text-primary-600 dark:text-primary-400 underline hover:text-primary-700 focus:outline-none focus:ring-2 focus:ring-primary-400 rounded"
            >
              View all {alerts.length} in Alerts tab
            </button>
          </p>
        ) : (
          <p className="text-xs text-slate-500 text-center">View all {alerts.length} in Alerts tab</p>
        ))}
    </div>
  )
}
