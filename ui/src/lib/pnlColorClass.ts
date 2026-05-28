// pnlColorClass — P&L saturation-scale helper (kx-rfrf).
//
// Maps a P&L value into a Tailwind background class with saturation
// matching the magnitude — light tints for <$10k, medium for $10k-$100k,
// deep for $100k+. The contrast scale lets a trader scan a desk dashboard
// and spot the outlier rows at a glance instead of every cell looking
// the same shade of green. Negatives flip to the red palette with the
// same magnitude scheme. Zero, null, and non-finite values stay neutral
// (no background).
//
// The default thresholds are USD-denominated. Callers in JPY/KRW books
// (where headline numbers run 100-1000x larger) pass a [scale] divisor
// to re-anchor the thresholds: scale=100 means $10k threshold becomes
// $1m. The function applies `|value| / scale` before the threshold
// comparison so the call shape stays the same.

export type PnlSign = 'gain' | 'loss' | 'flat'
export type PnlMagnitude = 'light' | 'medium' | 'deep'

export interface PnlColorClass {
  sign: PnlSign
  magnitude: PnlMagnitude
  className: string
}

export interface PnlColorClassOptions {
  /** Divisor applied to |value| before threshold comparison. Default 1. */
  scale?: number
}

const NEUTRAL: PnlColorClass = { sign: 'flat', magnitude: 'light', className: '' }

const GAIN_CLASSES: Record<PnlMagnitude, string> = {
  light: 'bg-emerald-50 dark:bg-emerald-950/20',
  medium: 'bg-emerald-100 dark:bg-emerald-900/30',
  deep: 'bg-emerald-200 dark:bg-emerald-800/40',
}

const LOSS_CLASSES: Record<PnlMagnitude, string> = {
  light: 'bg-red-50 dark:bg-red-950/20',
  medium: 'bg-red-100 dark:bg-red-900/30',
  deep: 'bg-red-200 dark:bg-red-800/40',
}

const MEDIUM_FROM = 10_000
const DEEP_FROM = 100_000

function magnitudeFor(absolute: number): PnlMagnitude {
  if (absolute >= DEEP_FROM) return 'deep'
  if (absolute >= MEDIUM_FROM) return 'medium'
  return 'light'
}

export function pnlColorClass(
  value: number | null | undefined,
  options: PnlColorClassOptions = {},
): PnlColorClass {
  if (value === null || value === undefined || !Number.isFinite(value) || value === 0) {
    return NEUTRAL
  }
  const { scale = 1 } = options
  const isGain = value > 0
  const absolute = Math.abs(value) / scale
  const magnitude = magnitudeFor(absolute)
  return {
    sign: isGain ? 'gain' : 'loss',
    magnitude,
    className: isGain ? GAIN_CLASSES[magnitude] : LOSS_CLASSES[magnitude],
  }
}
