// greeksSignCue — visual-cue helper for negative Greek cells (kx-kbze).
//
// Returns a tone tag ("negative"|"neutral") plus a Tailwind className for
// the cell background. Negative values flag light-red (legible on light
// and dark themes); positives, zero, and non-finite values stay neutral.
// Useful in Greeks tables where a short-gamma row or a deeply-negative
// vega number is the most urgent thing on the page and the eye should
// jump straight to it.

export type GreeksSign = 'negative' | 'neutral'

export interface GreeksSignCue {
  sign: GreeksSign
  className: string
}

const NEGATIVE_CUE: GreeksSignCue = {
  sign: 'negative',
  className: 'bg-red-50 dark:bg-red-950/30',
}

const NEUTRAL_CUE: GreeksSignCue = { sign: 'neutral', className: '' }

export function greeksSignCue(value: number | null | undefined): GreeksSignCue {
  if (value === null || value === undefined) return NEUTRAL_CUE
  if (!Number.isFinite(value)) return NEUTRAL_CUE
  return value < 0 ? NEGATIVE_CUE : NEUTRAL_CUE
}
