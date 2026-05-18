import type { AlertEventDto } from '../types'
import { RiskAlertBanner } from './RiskAlertBanner'

/**
 * Threshold (as a fraction of the VaR limit) above which a breach is signalled.
 * Mirrors RiskTickerStrip's VAR_BREACH_THRESHOLD so the strip and banner stay
 * in sync: when the strip's VaR cell goes red, the banner appears alongside it.
 */
const VAR_BREACH_THRESHOLD = 0.8

/**
 * Tabs on which the breach banner is allowed to surface.
 *
 * The breach should "follow the user" across the trading-day flow — Positions,
 * Risk, P&L — but not pollute operational, reporting, or admin screens.
 */
const VISIBLE_TABS: ReadonlySet<string> = new Set(['positions', 'risk', 'pnl'])

const SYNTHETIC_VAR_ALERT_ID = '__synthetic_var_breach__'

interface BreachBannerProps {
  activeTab: string
  varValue: number | null
  varLimit: number | null
  alerts: AlertEventDto[]
  onDismiss: (id: string) => void
  /**
   * Plan §8.2 — when the banner is visible (VaR breach OR active CRITICAL
   * alert), a "Need a hedge?" button is rendered on the right of the banner
   * so the user can jump straight into the Hedge Recommendation panel. If
   * the callback is not supplied, the CTA is not rendered.
   */
  onOpenHedgePanel?: () => void
}

/**
 * Sticky breach banner shown on Positions, Risk, and P&L tabs when:
 *   - VaR utilisation exceeds 80% of the configured limit, OR
 *   - any CRITICAL alert is currently active.
 *
 * Complements the global RiskTickerStrip's red breach colour with a more
 * emphatic, harder-to-miss call-out that travels with the user across the
 * trading-day flow.
 */
export function BreachBanner({
  activeTab,
  varValue,
  varLimit,
  alerts,
  onDismiss,
  onOpenHedgePanel,
}: BreachBannerProps) {
  if (!VISIBLE_TABS.has(activeTab)) return null

  const varUtilisation =
    varValue !== null && varLimit !== null && varLimit > 0
      ? varValue / varLimit
      : null
  const varBreach = varUtilisation !== null && varUtilisation > VAR_BREACH_THRESHOLD

  const criticalAlerts = alerts.filter((a) => a.severity === 'CRITICAL')

  if (!varBreach && criticalAlerts.length === 0) return null

  const visibleAlerts: AlertEventDto[] = [...criticalAlerts]

  if (varBreach && criticalAlerts.length === 0 && varValue !== null && varLimit !== null) {
    // Synthetic row so the user sees a clear explanation even when no
    // CRITICAL alert event has fired yet. The dismiss handler is wired to a
    // no-op for this synthetic id — VaR utilisation is a live metric that
    // re-evaluates on every poll, so dismissing it would be misleading.
    visibleAlerts.push({
      id: SYNTHETIC_VAR_ALERT_ID,
      ruleId: SYNTHETIC_VAR_ALERT_ID,
      ruleName: 'VaR Limit',
      type: 'VAR_BREACH',
      severity: 'CRITICAL',
      message: `VaR ${(varUtilisation! * 100).toFixed(1)}% of limit`,
      currentValue: varValue,
      threshold: varLimit,
      bookId: '',
      triggeredAt: new Date().toISOString(),
      status: 'TRIGGERED',
    })
  }

  return (
    <div
      data-testid="breach-banner"
      className="sticky top-0 z-20 px-6 pt-2 pb-1 bg-surface-50 dark:bg-surface-900"
    >
      <div className="flex items-start gap-3">
        <div className="flex-1 min-w-0">
          <RiskAlertBanner
            alerts={visibleAlerts}
            onDismiss={(id) => {
              if (id === SYNTHETIC_VAR_ALERT_ID) return
              onDismiss(id)
            }}
          />
        </div>
        {onOpenHedgePanel && (
          <button
            type="button"
            data-testid="breach-banner-hedge-cta"
            onClick={onOpenHedgePanel}
            title="Open hedge recommendations (Shift+H)"
            className="shrink-0 mt-1 inline-flex items-center gap-1 text-sm font-medium px-3 py-1.5 rounded border border-red-300 dark:border-red-700 bg-red-50 dark:bg-red-900/30 text-red-700 dark:text-red-300 hover:bg-red-100 dark:hover:bg-red-900/50 focus:outline-none focus:ring-2 focus:ring-red-400 transition-colors"
          >
            Need a hedge?
          </button>
        )}
      </div>
    </div>
  )
}
