// Greeks-table trend chevron + color helper (kx-w2hk).
//
// Greeks tables refresh frequently. Traders want to see at a glance whether
// a Greek went up, down, or held steady since the last tick. A chevron next
// to the value is the standard convention:
//
//   ▲  green   value increased        the Greek moved up
//   ▼  red     value decreased        the Greek moved down
//   –  slate   value (effectively) unchanged
//
// "Effectively unchanged" is bounded by a small epsilon (default 1e-6) so
// that floating-point jitter does not flicker the indicator on every refresh.
// Callers compute `current - previous` and pass that as the delta; the helper
// returns the glyph plus a hex color so it can be dropped straight into a
// `<span style={{ color }}>{chevron}</span>` without further mapping.
//
// Non-finite deltas (NaN, ±Infinity) — e.g. when the previous value is
// missing — collapse to the flat indicator. We deliberately do not surface
// them as a "missing" state here: the formatter for the numeric cell already
// handles that (see format.ts); the chevron's job is solely to show direction
// when both readings exist.

const UP_GLYPH = '▲'
const DOWN_GLYPH = '▼'
const FLAT_GLYPH = '–'

const UP_COLOR = '#22c55e'
const DOWN_COLOR = '#ef4444'
const FLAT_COLOR = '#94a3b8'

const DEFAULT_EPSILON = 1e-6

export interface GreeksTrendOptions {
  /**
   * Threshold below which the delta is considered "flat". Defaults to 1e-6
   * — small enough to ignore floating-point noise but large enough to catch
   * any real Greek movement.
   */
  epsilon?: number
}

export interface GreeksTrendIndicator {
  chevron: string
  color: string
}

/**
 * Returns the chevron glyph and hex color appropriate for the given
 * delta-from-previous. Positive deltas point up (green), negative deltas
 * point down (red), and anything within `epsilon` of zero (including
 * non-finite values) renders as a neutral flat dash.
 */
export function greeksTrendColor(
  delta: number,
  options: GreeksTrendOptions = {},
): GreeksTrendIndicator {
  const { epsilon = DEFAULT_EPSILON } = options
  if (!Number.isFinite(delta) || Math.abs(delta) <= epsilon) {
    return { chevron: FLAT_GLYPH, color: FLAT_COLOR }
  }
  if (delta > 0) {
    return { chevron: UP_GLYPH, color: UP_COLOR }
  }
  return { chevron: DOWN_GLYPH, color: DOWN_COLOR }
}
