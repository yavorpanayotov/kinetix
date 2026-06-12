/**
 * Age past which an undismissed alert stops rendering at full alarm intensity.
 * A 15-hour-old breach styled identically to a fresh one trains users to
 * ignore red — the stale treatment keeps the fact visible without the scream.
 */
export const STALE_AFTER_MS = 4 * 60 * 60 * 1000

/** Whether an alert is old enough for the muted "stale" visual treatment. */
export function isStaleAlert(triggeredAt: string): boolean {
  return Date.now() - new Date(triggeredAt).getTime() > STALE_AFTER_MS
}
