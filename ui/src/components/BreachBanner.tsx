import { useMemo } from 'react'
import { XCircle } from 'lucide-react'
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

/**
 * Window over which near-duplicate alerts get folded into a single rollup
 * banner (plan §3.1 / G3). Three breaches within 24h with the same severity,
 * book and rule type collapse to one row with a count badge — the audit
 * screenshot showed three near-identical $2.5M VaR banners stacked, which is
 * what we're fixing here.
 */
const ROLLUP_WINDOW_MS = 24 * 60 * 60 * 1000

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
  /**
   * Plan §3.1 — when a rollup group is rendered, a "View all" link is shown
   * that navigates the user to the Alerts tab. If the callback is not
   * supplied, the link is not rendered.
   */
  onViewAllAlerts?: () => void
}

interface RollupGroup {
  key: string
  alerts: AlertEventDto[]
}

/**
 * Human-readable label per alert `type` for the rollup banner header.
 * E.g. `VAR_BREACH` → "VaR breaches".
 */
const ROLLUP_LABEL: Record<string, string> = {
  VAR_BREACH: 'VaR breaches',
  PNL_THRESHOLD: 'P&L threshold breaches',
  CONCENTRATION: 'Concentration breaches',
  DELTA_BREACH: 'Delta breaches',
  VEGA_BREACH: 'Vega breaches',
  MARGIN_BREACH: 'Margin breaches',
  DATA_STALENESS: 'Data staleness alerts',
  RISK_LIMIT: 'Risk limit breaches',
}

function rollupLabel(type: string): string {
  return ROLLUP_LABEL[type] ?? `${type} alerts`
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(Math.round(value))
}

/**
 * Group CRITICAL alerts by (severity, bookId, type) and, within each group,
 * keep only the alerts that fall inside a 24h window anchored on the most
 * recent member. Anchoring on the latest alert (rather than `now`) matches the
 * audit case — three identical breaches "3 days ago" still roll up because
 * they all triggered within minutes of each other.
 *
 * Returns the groups in input order of their first qualifying member so the
 * visible ordering stays stable across renders.
 */
function groupRollups(alerts: AlertEventDto[]): RollupGroup[] {
  const byKey = new Map<string, AlertEventDto[]>()
  const order: string[] = []

  for (const alert of alerts) {
    const triggered = new Date(alert.triggeredAt).getTime()
    if (!Number.isFinite(triggered)) continue

    const key = `${alert.severity}|${alert.bookId}|${alert.type}`
    let bucket = byKey.get(key)
    if (!bucket) {
      bucket = []
      byKey.set(key, bucket)
      order.push(key)
    }
    bucket.push(alert)
  }

  return order.map((key) => {
    const bucket = byKey.get(key)!
    const latestMs = bucket.reduce(
      (max, a) => Math.max(max, new Date(a.triggeredAt).getTime()),
      Number.NEGATIVE_INFINITY,
    )
    const inWindow = bucket.filter(
      (a) => latestMs - new Date(a.triggeredAt).getTime() <= ROLLUP_WINDOW_MS,
    )
    return { key, alerts: inWindow }
  })
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
  onViewAllAlerts,
}: BreachBannerProps) {
  const criticalAlerts = useMemo(
    () => alerts.filter((a) => a.severity === 'CRITICAL'),
    [alerts],
  )

  // Group by (severity, bookId, type) within a 24h window. Groups with 2+
  // members fold into a single rollup row; singletons render as before.
  const { rollups, passthroughAlerts } = useMemo(() => {
    const groups = groupRollups(criticalAlerts)

    const rollupGroups: RollupGroup[] = []
    const rolledUpIds = new Set<string>()
    for (const group of groups) {
      if (group.alerts.length >= 2) {
        rollupGroups.push(group)
        for (const a of group.alerts) rolledUpIds.add(a.id)
      }
    }

    const passthrough = criticalAlerts.filter((a) => !rolledUpIds.has(a.id))
    return { rollups: rollupGroups, passthroughAlerts: passthrough }
  }, [criticalAlerts])

  if (!VISIBLE_TABS.has(activeTab)) return null

  const varUtilisation =
    varValue !== null && varLimit !== null && varLimit > 0
      ? varValue / varLimit
      : null
  const varBreach = varUtilisation !== null && varUtilisation > VAR_BREACH_THRESHOLD

  if (!varBreach && criticalAlerts.length === 0) return null

  const visibleAlerts: AlertEventDto[] = [...passthroughAlerts]

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
        <div className="flex-1 min-w-0 space-y-2">
          {rollups.map((group) => {
            // Latest alert (most recent triggeredAt) drives the headline value.
            const latest = group.alerts.reduce((a, b) =>
              new Date(a.triggeredAt).getTime() >= new Date(b.triggeredAt).getTime() ? a : b,
            )
            const label = rollupLabel(latest.type)
            const headline = `${group.alerts.length} ${label} in the last 24h — latest ${formatCurrency(
              latest.currentValue,
            )} (${latest.bookId})`
            return (
              <div
                key={group.key}
                data-testid="breach-banner-rollup"
                role="alert"
                aria-label={headline}
                className="flex items-center gap-3 rounded-lg border border-red-200 bg-red-50 text-red-900 dark:border-red-700 dark:bg-red-900/30 dark:text-red-200 px-4 py-2"
              >
                <XCircle className="h-4 w-4 text-red-500 shrink-0" />
                <span
                  data-testid="breach-banner-count"
                  className="shrink-0 inline-flex items-center justify-center min-w-[1.5rem] h-6 px-1.5 rounded-full bg-red-600 text-white text-xs font-semibold"
                  aria-label={`${group.alerts.length} grouped alerts`}
                >
                  {group.alerts.length}
                </span>
                <span className="flex-1 text-sm">{headline}</span>
                {onViewAllAlerts && (
                  <button
                    type="button"
                    data-testid="breach-banner-view-all"
                    onClick={onViewAllAlerts}
                    className="shrink-0 text-xs font-medium text-red-700 underline hover:text-red-800 focus:outline-none focus:ring-2 focus:ring-red-400 rounded"
                  >
                    View all
                  </button>
                )}
              </div>
            )
          })}
          {visibleAlerts.length > 0 && (
            <RiskAlertBanner
              alerts={visibleAlerts}
              onDismiss={(id) => {
                if (id === SYNTHETIC_VAR_ALERT_ID) return
                onDismiss(id)
              }}
            />
          )}
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
