/**
 * Plan §8.6 — "Compare with snapshot" ad-hoc time-shifted compare.
 *
 * The user picks a preset relative offset from "now" (the most recent risk
 * data) on the Risk Dashboard. We then walk an existing intraday time
 * series and locate the nearest point at or before that target timestamp.
 * The dashboard renders a numeric delta (`Δ vs -1h`) against today's value
 * so the user can answer "what is different right now vs an hour ago?".
 *
 * No new backend endpoints are introduced — we reuse the data already
 * fetched by the intraday VaR timeline hook.
 */

export type SnapshotPreset = '-15m' | '-1h' | 'eod-yesterday'

export interface SnapshotPresetOption {
  id: SnapshotPreset
  label: string
}

export const SNAPSHOT_PRESETS: readonly SnapshotPresetOption[] = [
  { id: '-15m', label: '-15m' },
  { id: '-1h', label: '-1h' },
  { id: 'eod-yesterday', label: 'EOD yesterday' },
]

/**
 * Resolve a preset against a reference instant ("now") to an ISO timestamp
 * representing the target snapshot moment.
 *
 * - `-15m` / `-1h` subtract the obvious offset.
 * - `eod-yesterday` returns yesterday's close — proxied as 17:00 UTC of the
 *   previous calendar day. A more sophisticated business-calendar lookup is
 *   out of scope (no new backend), so this is a reasonable default.
 *
 * Returns `null` for any unsupported preset (e.g. when the user selects
 * "Off").
 */
export function resolveSnapshotTarget(preset: SnapshotPreset, now: Date = new Date()): string | null {
  switch (preset) {
    case '-15m':
      return new Date(now.getTime() - 15 * 60 * 1000).toISOString()
    case '-1h':
      return new Date(now.getTime() - 60 * 60 * 1000).toISOString()
    case 'eod-yesterday': {
      const yesterday = new Date(now.getTime())
      yesterday.setUTCDate(yesterday.getUTCDate() - 1)
      yesterday.setUTCHours(17, 0, 0, 0)
      return yesterday.toISOString()
    }
    default:
      return null
  }
}

interface HasTimestamp {
  timestamp: string
}

/**
 * Walk a time series and return the point whose timestamp is nearest to —
 * but not strictly after — the target. If every point in the series sits
 * after the target (or the series is empty), returns `null`.
 *
 * Time series are typically ascending but we don't assume that; a linear
 * scan is fine for the few hundred points the intraday hook emits.
 */
export function findNearestPoint<T extends HasTimestamp>(points: readonly T[], targetIso: string): T | null {
  if (points.length === 0) return null
  const targetMs = Date.parse(targetIso)
  if (Number.isNaN(targetMs)) return null

  let best: T | null = null
  let bestDelta = Number.POSITIVE_INFINITY

  for (const point of points) {
    const pointMs = Date.parse(point.timestamp)
    if (Number.isNaN(pointMs)) continue
    if (pointMs > targetMs) continue
    const delta = targetMs - pointMs
    if (delta < bestDelta) {
      bestDelta = delta
      best = point
    }
  }

  return best
}
