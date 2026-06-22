import type { PositionRiskDto } from '../types'

/**
 * Aggregates per-book position-risk rows into a single firm-level view by
 * merging rows that share an `instrumentId`.
 *
 * Greeks and market value are additive across books, so the merged row carries
 * the exact firm-level sensitivity for each instrument (firm delta = sum of the
 * per-book deltas, etc.). VaR/ES contributions are summed as an approximation
 * (they ignore cross-book diversification) purely to keep `percentageOfTotal`
 * self-consistent within the merged table; the headline firm VaR continues to
 * come from the cross-book aggregate, not from this sum.
 *
 * Input is the list of each book's rows (e.g. one entry per book in the group).
 */
export function aggregatePositionRisk(perBook: PositionRiskDto[][]): PositionRiskDto[] {
  const byInstrument = new Map<string, PositionRiskDto>()

  const num = (v: string | null | undefined): number => (v == null ? 0 : Number(v) || 0)

  for (const rows of perBook) {
    for (const row of rows) {
      const existing = byInstrument.get(row.instrumentId)
      if (!existing) {
        // Clone so we never mutate the caller's row objects.
        byInstrument.set(row.instrumentId, { ...row })
        continue
      }
      existing.marketValue = String(num(existing.marketValue) + num(row.marketValue))
      existing.delta = String(num(existing.delta) + num(row.delta))
      existing.gamma = String(num(existing.gamma) + num(row.gamma))
      existing.vega = String(num(existing.vega) + num(row.vega))
      existing.theta = String(num(existing.theta) + num(row.theta))
      existing.rho = String(num(existing.rho) + num(row.rho))
      if (existing.dv01 != null || row.dv01 != null) {
        existing.dv01 = String(num(existing.dv01) + num(row.dv01))
      }
      existing.varContribution = String(num(existing.varContribution) + num(row.varContribution))
      existing.esContribution = String(num(existing.esContribution) + num(row.esContribution))
    }
  }

  const merged = [...byInstrument.values()]

  // Recompute percentageOfTotal against the merged VaR-contribution total so the
  // firm table's percentages are internally consistent.
  const totalVar = merged.reduce((acc, r) => acc + Math.abs(num(r.varContribution)), 0)
  if (totalVar > 0) {
    for (const row of merged) {
      row.percentageOfTotal = String((num(row.varContribution) / totalVar) * 100)
    }
  }

  return merged
}
