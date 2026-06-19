// The staleness level shared between the mobile freshness banner and the data
// card it sits above. Both surfaces must agree on what "red-stale" means, so the
// threshold→level computation lives here as a single pure function rather than
// being duplicated as magic numbers in each component.
//
// Thresholds match the desktop LastUpdatedIndicator:
//   neutral < 5 min, amber 5–15 min, red ≥ 15 min.

export type FreshnessLevel = 'fresh' | 'amber' | 'red'

export function freshnessLevel(timestamp: string): FreshnessLevel {
  const diffMinutes = (Date.now() - new Date(timestamp).getTime()) / 60_000
  if (diffMinutes >= 15) return 'red'
  if (diffMinutes >= 5) return 'amber'
  return 'fresh'
}
