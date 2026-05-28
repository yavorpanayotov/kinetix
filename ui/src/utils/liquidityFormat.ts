// Trader-review P0 #7 — formatters for the Liquidity Risk panel.
//
// The dashboard previously rendered LVaR magnitudes in single dollars
// (`$0.7`, `$1.4`) while neighbouring `Stressed Liq` columns used compact
// `$K` / `$M` notation. The inconsistency made the order-of-magnitude
// difference easy to miss, and a 50-day-old liquidity snapshot rendered
// with no staleness signal at all.

const STALENESS_THRESHOLD_MS = 24 * 60 * 60 * 1000

/**
 * Format an LVaR contribution / portfolio LVaR magnitude.
 *
 * Rule:
 *   |v| >= $1,000  → compact notation (matches `Stressed Liq` column)
 *   |v| <  $1,000  → fixed two-decimal `$X.XX` so single-dollar
 *                    magnitudes are unambiguous (never a misleading
 *                    `$0.7` that looks like `$0.7K` at a glance)
 */
export function formatLvar(value: number): string {
  if (!Number.isFinite(value)) return '—'
  if (Math.abs(value) >= 1_000) {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      notation: 'compact',
      maximumFractionDigits: 1,
    }).format(value)
  }
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/**
 * Compute the staleness of a liquidity snapshot in whole days, or null
 * when the snapshot is fresher than 1 day. Drives the staleness banner —
 * trader-review P0 #7 flagged a 50-day-old LVaR calculation rendered
 * with no warning.
 */
export function computeStalenessDays(
  calculatedAt: string,
  now: Date,
): number | null {
  const calculated = new Date(calculatedAt).getTime()
  const ageMs = now.getTime() - calculated
  if (!Number.isFinite(ageMs) || ageMs < STALENESS_THRESHOLD_MS) {
    return null
  }
  return Math.floor(ageMs / (24 * 60 * 60 * 1000))
}
