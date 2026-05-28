import { AlertTriangle, X, XCircle } from 'lucide-react'
import type { AlertEventDto } from '../types'
import { formatAlertMessage } from '../utils/alertMessageFormatter'

interface RiskAlertBannerProps {
  alerts: AlertEventDto[]
  onDismiss: (id: string) => void
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

function alertStyles(severity: string) {
  // Dark-mode variants match the "Need a hedge?" button next to this banner
  // — without them the body text inherits the page's near-white slate-100
  // colour over a near-white bg-*-50 fill and becomes invisible.
  switch (severity) {
    case 'CRITICAL':
      return {
        container:
          'border-red-200 bg-red-50 text-red-900 dark:border-red-700 dark:bg-red-900/30 dark:text-red-200',
        icon: <XCircle className="h-4 w-4 text-red-500 shrink-0" />,
      }
    case 'WARNING':
      return {
        container:
          'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200',
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

export function RiskAlertBanner({ alerts, onDismiss }: RiskAlertBannerProps) {
  if (alerts.length === 0) return null

  const visible = alerts.slice(0, 3)
  const hasMore = alerts.length > 3

  return (
    <div data-testid="risk-alert-banner" className="space-y-2">
      {visible.map((alert) => {
        const styles = alertStyles(alert.severity)
        return (
          <div
            key={alert.id}
            data-testid={`alert-item-${alert.id}`}
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
      {hasMore && (
        <p className="text-xs text-slate-500 text-center">
          View all in Alerts tab
        </p>
      )}
    </div>
  )
}
