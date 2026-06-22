import type { ChartDataPoint } from '../api/jobHistory'
import type { VaRHistoryEntry } from '../hooks/useVaR'

/**
 * Builds a firm-level greeks time-series by summing each book's per-bucket
 * delta/gamma/vega/theta. Greeks are additive, so the firm delta at time T is
 * exactly the sum of the per-book deltas at T.
 *
 * VaR/ES are NOT additive and are intentionally left at 0 — this series feeds
 * only the Greeks-trend chart, never the VaR/ES line (which shows a
 * "select a book" affordance at firm scope).
 *
 * Only buckets where at least one book reported greeks are emitted, so the
 * chart's "has greeks" filter renders a line rather than the empty state.
 */
export function aggregateGreeksHistory(perBook: ChartDataPoint[][]): VaRHistoryEntry[] {
  interface Acc {
    delta: number
    gamma: number
    vega: number
    theta: number
    confidenceLevel: string
    hasGreeks: boolean
  }
  const byBucket = new Map<string, Acc>()

  for (const points of perBook) {
    for (const p of points) {
      const acc = byBucket.get(p.bucket) ?? {
        delta: 0,
        gamma: 0,
        vega: 0,
        theta: 0,
        confidenceLevel: p.confidenceLevel ?? 'CL_95',
        hasGreeks: false,
      }
      if (p.delta != null && p.gamma != null && p.vega != null) {
        acc.delta += p.delta
        acc.gamma += p.gamma
        acc.vega += p.vega
        acc.theta += p.theta ?? 0
        acc.hasGreeks = true
      }
      byBucket.set(p.bucket, acc)
    }
  }

  return [...byBucket.entries()]
    .filter(([, a]) => a.hasGreeks)
    .map(([bucket, a]) => ({
      varValue: 0,
      expectedShortfall: 0,
      calculatedAt: bucket,
      confidenceLevel: a.confidenceLevel,
      delta: a.delta,
      gamma: a.gamma,
      vega: a.vega,
      theta: a.theta,
    }))
    .sort((x, y) => new Date(x.calculatedAt).getTime() - new Date(y.calculatedAt).getTime())
}
