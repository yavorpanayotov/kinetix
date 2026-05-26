/**
 * Classify a freshness value (in seconds) into an urgency tier.
 * Single source of truth for freshness thresholds — consumed by both
 * CitationList (badge) and CitationFootnote (inline dot).
 *
 * Thresholds (per Marcus's requirement: "90-second-old VaR is a lie"):
 *  - ``≤ 30s`` → ``'fresh'``   (neutral — no visual noise)
 *  - ``> 30 && ≤ 60s`` → ``'aging'`` (amber warning)
 *  - ``> 60s`` → ``'stale'``   (red alert — must be visually obvious)
 *
 * Non-finite or ≤ 0 values map to ``'fresh'`` (same semantics as
 * "just now" — we don't know the age so we assume live).
 */
export function freshnessUrgency(seconds: number): 'fresh' | 'aging' | 'stale' {
  if (!Number.isFinite(seconds) || seconds <= 0) return 'fresh'
  if (seconds <= 30) return 'fresh'
  if (seconds <= 60) return 'aging'
  return 'stale'
}
