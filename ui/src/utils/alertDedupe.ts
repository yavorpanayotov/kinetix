import type { AlertEventDto } from '../types'

const SEVERITY_RANK: Record<string, number> = { CRITICAL: 3, WARNING: 2, INFO: 1 }

/**
 * Drop alerts that are lower-severity shadows of the same underlying
 * condition — same book and alert type already alerting at a higher severity.
 *
 * Motivation (UX review): a book whose VaR breached both its $750K WARNING
 * rule and its $1M CRITICAL rule surfaced as two stacked banners telling the
 * user the same fact at two severities. One condition, one severity: only the
 * highest severity for a given book+type survives.
 *
 * Repeated firings at the SAME severity are all kept — rollup grouping (and
 * its count badge) is the caller's concern, not this filter's.
 */
export function dedupeAlerts(alerts: AlertEventDto[]): AlertEventDto[] {
  const maxRank = new Map<string, number>()
  for (const alert of alerts) {
    const key = `${alert.bookId}|${alert.type}`
    const rank = SEVERITY_RANK[alert.severity] ?? 0
    const current = maxRank.get(key)
    if (current === undefined || rank > current) maxRank.set(key, rank)
  }

  return alerts.filter((alert) => {
    const key = `${alert.bookId}|${alert.type}`
    return (SEVERITY_RANK[alert.severity] ?? 0) >= (maxRank.get(key) ?? 0)
  })
}
