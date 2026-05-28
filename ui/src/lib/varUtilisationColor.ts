// varUtilisationColor — VaR-limit utilisation cue (kx-z2wf).
//
// Maps a utilisation ratio (current exposure / VaR limit) into a tone
// (comfort|warning|breach|unknown), a Tailwind colour class, and an
// aria-label so the limit blotter signals the same green/orange/red
// triad across every dashboard. Threshold: below 80% comfort, 80%-100%
// warning, at-or-above 100% breach. Non-finite / null collapses to
// "unknown" (slate-500).

export type VarUtilisationTone = 'comfort' | 'warning' | 'breach' | 'unknown'

export interface VarUtilisationColor {
  tone: VarUtilisationTone
  className: string
  ariaLabel: string
}

const WARNING_FROM = 0.8
const BREACH_FROM = 1

function formatPercent(ratio: number): string {
  const pct = ratio * 100
  return Number.isInteger(pct) ? `${pct}%` : `${pct.toString()}%`
}

export function varUtilisationColor(ratio: number | null | undefined): VarUtilisationColor {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) {
    return {
      tone: 'unknown',
      className: 'text-slate-500 dark:text-slate-400',
      ariaLabel: 'Utilisation unavailable',
    }
  }
  if (ratio >= BREACH_FROM) {
    return {
      tone: 'breach',
      className: 'text-red-700 dark:text-red-300',
      ariaLabel: `Limit breached: ${formatPercent(ratio)} utilisation`,
    }
  }
  if (ratio >= WARNING_FROM) {
    return {
      tone: 'warning',
      className: 'text-orange-700 dark:text-orange-300',
      ariaLabel: `Approaching breach: ${formatPercent(ratio)} utilisation`,
    }
  }
  const safe = Math.max(0, ratio)
  return {
    tone: 'comfort',
    className: 'text-emerald-700 dark:text-emerald-300',
    ariaLabel: `Within limit: ${formatPercent(safe)} utilisation`,
  }
}
