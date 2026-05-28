// dataQualityDot — per-row data-quality dot helper (kx-xogb).
//
// Risk and position tables show data that refreshes at heterogeneous
// cadences. Without an inline freshness cue, the "last printed" cell is
// indistinguishable from a six-hour-stale snapshot. This helper turns a
// `lastUpdate` timestamp into a tone (fresh|stale), a colour token
// (green|gray), a short label ("Data updated 3m ago") and an aria label
// for screen readers. The freshness threshold is a caller-supplied
// duration in milliseconds.

export type DataQualityTone = 'fresh' | 'stale'
export type DataQualityColor = 'green' | 'gray'

export interface DataQualityDot {
  tone: DataQualityTone
  color: DataQualityColor
  label: string
  ariaLabel: string
}

export interface DataQualityDotOptions {
  /** Reference "now" for freshness comparison. Defaults to `new Date()`. */
  now?: Date
  /** Inclusive freshness threshold in milliseconds. */
  freshMs: number
}

const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE

function formatLag(lagMs: number): string {
  if (lagMs < MINUTE) {
    return `${Math.max(0, Math.floor(lagMs / SECOND))}s ago`
  }
  if (lagMs < HOUR) {
    return `${Math.floor(lagMs / MINUTE)}m ago`
  }
  return `${Math.floor(lagMs / HOUR)}h ago`
}

function formatLagWords(lagMs: number): string {
  if (lagMs < MINUTE) {
    const s = Math.max(0, Math.floor(lagMs / SECOND))
    return `${s} second${s === 1 ? '' : 's'} ago`
  }
  if (lagMs < HOUR) {
    const m = Math.floor(lagMs / MINUTE)
    return `${m} minute${m === 1 ? '' : 's'} ago`
  }
  const h = Math.floor(lagMs / HOUR)
  return `${h} hour${h === 1 ? '' : 's'} ago`
}

export function dataQualityDot(
  lastUpdate: Date | null,
  options: DataQualityDotOptions,
): DataQualityDot {
  if (lastUpdate === null) {
    return {
      tone: 'stale',
      color: 'gray',
      label: 'No data',
      ariaLabel: 'Data not yet received',
    }
  }
  const now = options.now ?? new Date()
  const lagMs = Math.max(0, now.getTime() - lastUpdate.getTime())
  const isFresh = lagMs < options.freshMs
  return {
    tone: isFresh ? 'fresh' : 'stale',
    color: isFresh ? 'green' : 'gray',
    label: `Data updated ${formatLag(lagMs)}`,
    ariaLabel: `Data is ${isFresh ? 'fresh' : 'stale'}; updated ${formatLagWords(lagMs)}`,
  }
}
