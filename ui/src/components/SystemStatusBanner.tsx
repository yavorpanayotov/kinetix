/**
 * Single severity-prioritised status bar that replaces the previous four
 * stacked banners (exhausted / reconnecting / maintenance) in App.tsx.
 *
 * Why a single bar?
 *   - Four stacked banners pushed the active tab content below the fold on
 *     1080p displays.
 *   - Multiple simultaneous `role="alert"` regions produce overlapping
 *     screen-reader announcements.
 *
 * Priority order (highest first), preserved verbatim from the previous
 * inline rendering in App.tsx:
 *   1. exhausted        — WebSocket has given up reconnecting (red, role=alert).
 *   2. reconnecting     — WebSocket dropped, retrying (amber/blue, role=alert
 *                         on the copy only; sibling elapsed counter is
 *                         aria-live="off" so the seconds tick does not re-fire
 *                         the announcement).
 *   3. maintenance      — system health DEGRADED while WS is still alive
 *                         (blue, role=status — polite).
 *
 * The Demo welcome strip is intentionally NOT folded into this component:
 * it is a persistent, user-dismissible mode notice, not a system status
 * alert. The plan explicitly says "Demo strip stays as its own dismissible
 * row".
 */

type SystemHealthStatus = 'UP' | 'DEGRADED' | 'DOWN' | null

interface SystemStatusBannerProps {
  exhausted: boolean
  reconnecting: boolean
  maintenance: boolean
  systemHealthStatus: SystemHealthStatus
  disconnectElapsed: number
  onReconnect: () => void
}

export function SystemStatusBanner({
  exhausted,
  reconnecting,
  maintenance,
  systemHealthStatus,
  disconnectElapsed,
  onReconnect,
}: SystemStatusBannerProps) {
  if (exhausted) {
    return (
      <div
        data-testid="connection-lost-banner"
        className="bg-red-50 border-b border-red-200 text-red-700 px-6 py-2 text-sm font-medium flex items-center justify-between"
        role="alert"
      >
        <span>Connection lost. Live prices are unavailable.</span>
        <button
          data-testid="reconnect-button"
          onClick={onReconnect}
          className="ml-4 px-3 py-1 text-sm font-medium bg-red-100 hover:bg-red-200 text-red-800 rounded-md transition-colors"
        >
          Reconnect
        </button>
      </div>
    )
  }

  if (reconnecting) {
    const healthUp = systemHealthStatus === 'UP'
    const healthDegraded = systemHealthStatus === 'DEGRADED'
    const healthUnknown = systemHealthStatus === null
    let bannerText: string
    let bannerClass: string
    if (healthDegraded) {
      bannerText = 'System update in progress. Prices paused.'
      bannerClass = 'bg-blue-50 border-b border-blue-200 text-blue-700'
    } else if (healthUnknown) {
      bannerText = 'Unable to reach server. Reconnecting...'
      bannerClass = 'bg-amber-100 border-b border-amber-300 text-amber-800'
    } else if (healthUp) {
      bannerText = 'Price feed interrupted. Reconnecting...'
      bannerClass = 'bg-amber-100 border-b border-amber-300 text-amber-800'
    } else {
      bannerText = 'Reconnecting...'
      bannerClass = 'bg-amber-100 border-b border-amber-300 text-amber-800'
    }
    return (
      <div
        data-testid="reconnecting-banner"
        className={`${bannerClass} px-6 py-2 text-sm font-medium`}
      >
        <span role="alert">{bannerText}</span>
        <span data-testid="reconnecting-banner-elapsed" aria-live="off">
          {disconnectElapsed > 0 ? ` (${disconnectElapsed}s)` : ''}
        </span>
      </div>
    )
  }

  if (maintenance) {
    return (
      <div
        data-testid="maintenance-banner"
        className="bg-blue-50 border-b border-blue-200 text-blue-700 px-6 py-2 text-sm font-medium"
        role="status"
      >
        Scheduled maintenance in progress. Some features may be temporarily limited.
      </div>
    )
  }

  return null
}
