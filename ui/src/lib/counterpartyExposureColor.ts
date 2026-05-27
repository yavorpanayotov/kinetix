// RAG-gradient colour helper for counterparty exposure utilisation
// (kx-8m18).
//
// Counterparty risk teams watch utilisation against per-counterparty limits.
// The dashboard already shows the numeric percentage, but it's the colour
// of the bar that catches the eye on a wall of tickers. The convention is:
//
//   0%  ……………………………… 50%   → green   (#22c55e)  comfortably below limit
//   50% ……………………… 100% → amber blend   warning band, interpolated
//   ≥ 100%                  → red     (#ef4444)  limit reached / breached
//
// The helper interpolates linearly between the three palette stops in RGB
// space. RGB linear blending is acceptable here because the three palette
// stops were chosen close together in luminance and the result is judged
// at a glance, not measured. Output is a 7-character `#rrggbb` string so
// callers can drop it straight into a `style={{ backgroundColor: ... }}`
// or an SVG fill without further conversion.
//
// Non-finite inputs (NaN, Infinity) are treated as a "missing reading" and
// surface as red — the operator should notice the gap, not assume green.

const GREEN_RGB: [number, number, number] = [0x22, 0xc5, 0x5e]
const AMBER_RGB: [number, number, number] = [0xf5, 0x9e, 0x0b]
const RED_RGB: [number, number, number] = [0xef, 0x44, 0x44]

const GREEN_HEX = '#22c55e'
const RED_HEX = '#ef4444'

function rgbToHex(rgb: [number, number, number]): string {
  const [r, g, b] = rgb.map((c) =>
    Math.max(0, Math.min(255, Math.round(c)))
      .toString(16)
      .padStart(2, '0'),
  )
  return `#${r}${g}${b}`
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t
}

function lerpRgb(
  from: [number, number, number],
  to: [number, number, number],
  t: number,
): [number, number, number] {
  return [lerp(from[0], to[0], t), lerp(from[1], to[1], t), lerp(from[2], to[2], t)]
}

/**
 * Returns a hex colour (e.g. "#22c55e") for the given utilisation percentage.
 *
 * The percentage is the bar's value as a number — `73` means 73% of the
 * limit consumed. Inputs outside [0, 100] are clamped: negatives are treated
 * as zero, anything ≥ 100 returns pure red, and non-finite values
 * (NaN/Infinity) also return red so missing readings stay visible.
 */
export function counterpartyExposureColor(percentage: number): string {
  if (!Number.isFinite(percentage)) return RED_HEX
  if (percentage <= 50) return GREEN_HEX
  if (percentage >= 100) return RED_HEX

  // Warning band: blend green → amber over (50, 75] then amber → red over
  // (75, 100). Splitting at the amber midpoint keeps the curve visually
  // monotonic — without the midpoint stop the gradient would skip past the
  // unmistakable amber stop straight from green to red.
  if (percentage <= 75) {
    const t = (percentage - 50) / 25
    return rgbToHex(lerpRgb(GREEN_RGB, AMBER_RGB, t))
  }
  const t = (percentage - 75) / 25
  return rgbToHex(lerpRgb(AMBER_RGB, RED_RGB, t))
}
