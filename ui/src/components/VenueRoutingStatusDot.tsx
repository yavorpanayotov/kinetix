import { useEffect, useState } from 'react'
import { fetchSystemHealth, type SystemHealthResponse } from '../api/system'

/**
 * Blotter-header indicator for fix-gateway venue routing health (ADR-0035
 * phase 2). Polls /api/v1/system/health every 30s and surfaces:
 *   - green   when fix-gateway reports READY
 *   - amber   when fix-gateway reports DOWN/NOT_READY (cancel emission is
 *             still best-effort but trader needs to know to call the venue)
 *   - red     when the health endpoint itself is unreachable
 *
 * The colour-only signal would be insufficient on its own; the tooltip and
 * data-testid let trader workflows assert on the degraded state without
 * relying on visual inspection.
 */
type DotState = 'up' | 'degraded' | 'unreachable'

const POLL_INTERVAL_MS = 30_000

function readFixGatewayState(health: SystemHealthResponse): DotState {
  const fixGateway = health.services?.['fix-gateway']
  if (!fixGateway) {
    // No fix-gateway entry from the aggregator yet — fail open so we don't
    // alarm the trader during a window where the aggregator hasn't been
    // updated to surface the new service.
    return 'up'
  }
  return fixGateway.status === 'READY' ? 'up' : 'degraded'
}

function dotClasses(state: DotState): string {
  switch (state) {
    case 'up':
      return 'bg-green-500 border-2 border-green-600'
    case 'degraded':
      return 'bg-amber-400 border-2 border-amber-500'
    case 'unreachable':
      return 'bg-red-500 border-2 border-red-600 animate-pulse'
  }
}

function tooltip(state: DotState): string {
  switch (state) {
    case 'up':
      return 'Venue routing healthy'
    case 'degraded':
      return 'Cancel confirmation unavailable — call venue directly to confirm cancel'
    case 'unreachable':
      return 'Health endpoint unreachable — venue routing status unknown'
  }
}

export function VenueRoutingStatusDot() {
  const [state, setState] = useState<DotState>('up')

  useEffect(() => {
    let cancelled = false

    async function poll() {
      try {
        const health = await fetchSystemHealth()
        if (!cancelled) setState(readFixGatewayState(health))
      } catch {
        if (!cancelled) setState('unreachable')
      }
    }

    poll()
    const id = window.setInterval(poll, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [])

  return (
    <span
      role="status"
      data-testid="venue-routing-status-dot"
      data-state={state}
      aria-label={tooltip(state)}
      title={tooltip(state)}
      className={`inline-block h-3 w-3 rounded-full ${dotClasses(state)}`}
    />
  )
}
