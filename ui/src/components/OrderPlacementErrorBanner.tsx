import { AlertTriangle, XCircle } from 'lucide-react'
import type { OrderPlacementState } from '../hooks/useOrderPlacement'

/**
 * Error banner for the order-placement flow (ADR-0035 phase 4 §4.13). Reuses
 * the same severity-tier visual language as `RiskAlertBanner`:
 *   - PENDING_FAILED (retryable) → WARNING (amber)
 *   - DUPLICATE_IN_FLIGHT       → WARNING (amber) but with a NO-RETRY message
 *   - REJECTED                  → CRITICAL (red)
 *
 * Other states render nothing — the banner only appears for outcomes that the
 * trader needs to act on.
 */
interface OrderPlacementErrorBannerProps {
  state: OrderPlacementState
  onRetry?: () => void
}

interface BannerContent {
  severity: 'warning' | 'critical'
  message: string
  retry: 'enabled' | 'disabled' | 'hidden'
}

function bannerFor(state: OrderPlacementState): BannerContent | null {
  switch (state.kind) {
    case 'failed':
      return {
        severity: 'warning',
        message: `Order routing timed out (${state.reason}) — call venue to confirm before retry`,
        retry: 'enabled',
      }
    case 'duplicate':
      return {
        severity: 'warning',
        message: 'Previous submission still in flight, do not retry yet',
        retry: 'disabled',
      }
    case 'rejected':
      return {
        severity: 'critical',
        message: `Order rejected: ${state.reason}`,
        retry: 'hidden',
      }
    default:
      return null
  }
}

export function OrderPlacementErrorBanner({ state, onRetry }: OrderPlacementErrorBannerProps) {
  const content = bannerFor(state)
  if (!content) return null

  const containerClass = content.severity === 'critical'
    ? 'border-red-200 bg-red-50'
    : 'border-amber-200 bg-amber-50'

  const icon = content.severity === 'critical'
    ? <XCircle className="h-4 w-4 text-red-500 shrink-0" />
    : <AlertTriangle className="h-4 w-4 text-amber-500 shrink-0" />

  return (
    <div
      data-testid="order-placement-error-banner"
      data-severity={content.severity}
      role={content.severity === 'critical' ? 'alert' : 'status'}
      className={`flex items-center gap-3 rounded-lg border px-4 py-2 ${containerClass}`}
    >
      {icon}
      <span className="flex-1 text-sm">{content.message}</span>
      {content.retry !== 'hidden' && (
        <button
          data-testid="order-placement-retry"
          disabled={content.retry === 'disabled'}
          onClick={onRetry}
          className="px-3 py-1 text-xs font-medium rounded-md border border-slate-300 hover:bg-slate-100 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Retry
        </button>
      )}
    </div>
  )
}
