import { useEffect, useState } from 'react'
import { X } from 'lucide-react'

/**
 * Phase 2.5.2 — surfaces the demo-orchestrator bootstrap progress as a
 * dismissible strip rendered above the tab bar.
 *
 * Polls `GET /demo/bootstrap-status` every 3s. While the bootstrap is
 * `NOT_STARTED` or `IN_PROGRESS` the banner renders with progress copy;
 * `FAILED` shows a separate failure variant; `READY` auto-dismisses the
 * banner.
 *
 * Manual dismiss flips a sessionStorage flag (NOT localStorage — the user
 * may want to see the banner again on the next page load).
 */

export const BOOTSTRAP_DISMISS_SESSION_KEY = 'kinetix_bootstrap_banner_dismissed'

const POLL_INTERVAL_MS = 3_000
const BOOTSTRAP_STATUS_URL = '/demo/bootstrap-status'

type BootstrapState = 'NOT_STARTED' | 'IN_PROGRESS' | 'READY' | 'FAILED'

interface BootstrapStatus {
  state: BootstrapState
  successCount: number | null
  failureCount: number | null
  sodSuccessCount?: number | null
  sodFailureCount?: number | null
}

function readDismissedFromSession(): boolean {
  if (typeof window === 'undefined') return false
  try {
    return sessionStorage.getItem(BOOTSTRAP_DISMISS_SESSION_KEY) === 'true'
  } catch {
    return false
  }
}

export function BootstrapBanner() {
  const [status, setStatus] = useState<BootstrapStatus | null>(null)
  const [dismissed, setDismissed] = useState<boolean>(() =>
    readDismissedFromSession(),
  )

  // Poll the bootstrap endpoint every POLL_INTERVAL_MS. We use a plain
  // setInterval rather than a chained setTimeout because the work per tick
  // (one fetch + a setState) is bounded and we want a predictable cadence
  // for the test that asserts "polls every 3 seconds".
  useEffect(() => {
    if (dismissed) return

    let cancelled = false

    async function fetchStatus() {
      try {
        const res = await fetch(BOOTSTRAP_STATUS_URL)
        if (cancelled) return
        if (!res.ok) return
        const body = (await res.json()) as BootstrapStatus
        if (cancelled) return
        setStatus(body)
      } catch {
        // Network/parse errors silently leave the prior state in place. The
        // demo-orchestrator endpoint is best-effort; not all deployments run
        // it (e.g. non-demo prod) and we must never crash the app over it.
      }
    }

    void fetchStatus()
    const timer = setInterval(fetchStatus, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [dismissed])

  if (dismissed) return null
  if (!status) return null
  if (status.state === 'READY') return null

  const handleDismiss = () => {
    try {
      sessionStorage.setItem(BOOTSTRAP_DISMISS_SESSION_KEY, 'true')
    } catch {
      // Some browser modes (Safari private, locked-down embeds) throw on
      // sessionStorage writes. Falling through still removes the banner for
      // the current page-view via the local state below.
    }
    setDismissed(true)
  }

  const isFailed = status.state === 'FAILED'

  const message = isFailed
    ? 'Initialisation failed — some metrics may be missing'
    : 'Initialising demo data — risk metrics loading…'

  // Visual treatment: amber-tinted for progress, red-tinted for failure.
  // We keep the same structural classes as DemoWelcomeStrip so the strip
  // stacks naturally above the tab bar.
  const containerClasses = isFailed
    ? 'bg-red-500/10 border-b border-red-500/30 text-red-200 dark:text-red-300'
    : 'bg-amber-500/10 border-b border-amber-500/30 text-amber-200 dark:text-amber-300'

  const dismissColourClasses = isFailed
    ? 'text-red-300 hover:text-white hover:bg-red-500/20 focus:ring-red-500'
    : 'text-amber-300 hover:text-white hover:bg-amber-500/20 focus:ring-amber-500'

  const showCounts =
    !isFailed &&
    (status.successCount !== null && status.successCount !== undefined ||
      status.failureCount !== null && status.failureCount !== undefined)

  return (
    <div
      data-testid="bootstrap-banner"
      data-variant={isFailed ? 'failed' : 'in-progress'}
      role="status"
      aria-live="polite"
      aria-label={
        isFailed
          ? 'Demo bootstrap failed'
          : 'Demo bootstrap in progress'
      }
      className={`${containerClasses} px-6 py-2 text-sm flex items-center justify-between`}
    >
      <span>
        {message}
        {showCounts && (
          <span className="opacity-75 ml-2">
            ({status.successCount ?? 0} succeeded
            {status.failureCount && status.failureCount > 0
              ? `, ${status.failureCount} failed`
              : ''}
            )
          </span>
        )}
        {isFailed &&
          (status.successCount !== null || status.failureCount !== null) && (
            <span className="opacity-75 ml-2">
              ({status.successCount ?? 0} succeeded, {status.failureCount ?? 0}{' '}
              failed)
            </span>
          )}
      </span>
      <button
        data-testid="bootstrap-banner-dismiss"
        onClick={handleDismiss}
        className={`${dismissColourClasses} ml-4 flex-shrink-0 p-1 rounded transition-colors focus:outline-none focus:ring-2`}
        aria-label="Dismiss bootstrap status banner"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}
