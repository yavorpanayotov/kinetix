import { useEffect, useState } from 'react'

// Plan §mobile — a full-width data-freshness banner for the phone surface.
// It reuses the SAME staleness thresholds as the desktop LastUpdatedIndicator
// (neutral < 5 min, amber 5–15 min, red ≥ 15 min) so the two stay consistent,
// but renders a loud full-width strip instead of a small inline label. At the
// red threshold it shows a "VERIFY BEFORE ACTING" warning so a stale screen on
// a phone can't be mistaken for a live one.

interface MobileFreshnessBannerProps {
  // ISO-8601 timestamp of the data being shown, or null when there is no data
  // yet — matching the input contract of LastUpdatedIndicator.
  dataAsOf: string | null
}

type Staleness = 'fresh' | 'amber' | 'red'

function staleness(timestamp: string): Staleness {
  const diffMinutes = (Date.now() - new Date(timestamp).getTime()) / 60_000
  if (diffMinutes >= 15) return 'red'
  if (diffMinutes >= 5) return 'amber'
  return 'fresh'
}

function formatRelative(timestamp: string): string {
  const asOf = new Date(timestamp)
  const diffSeconds = Math.floor((Date.now() - asOf.getTime()) / 1000)
  if (diffSeconds < 60) return 'just now'

  const diffMinutes = Math.floor(diffSeconds / 60)
  if (diffMinutes < 60) return `${diffMinutes} ${diffMinutes === 1 ? 'minute' : 'minutes'} ago`

  const diffHours = Math.floor(diffMinutes / 60)
  // Once the gap reaches a full day a relative count stops being meaningful
  // (e.g. very old seed data would read "12481 hours ago"), so fall back to an
  // absolute date. "Data as of Dec 3, 2023" reads naturally alongside the
  // banner copy, the same as the relative phrasings above.
  if (diffHours >= 24) {
    return asOf.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    })
  }

  return `${diffHours} ${diffHours === 1 ? 'hour' : 'hours'} ago`
}

const BANNER_CLASSES: Record<Staleness, string> = {
  fresh: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300',
  amber: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
  red: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200',
}

export function MobileFreshnessBanner({ dataAsOf }: MobileFreshnessBannerProps) {
  const [, setTick] = useState(0)

  useEffect(() => {
    if (!dataAsOf) return
    const id = setInterval(() => setTick((t) => t + 1), 30_000)
    return () => clearInterval(id)
  }, [dataAsOf])

  if (!dataAsOf) return null

  const level = staleness(dataAsOf)

  return (
    <div
      data-testid="mobile-freshness-banner"
      data-staleness={level}
      role="status"
      aria-live="polite"
      className={`w-full px-4 py-2 text-sm font-medium ${BANNER_CLASSES[level]}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span>Data as of {formatRelative(dataAsOf)}</span>
        {level === 'red' && (
          <span className="font-bold uppercase tracking-wide">
            Verify before acting
          </span>
        )}
      </div>
    </div>
  )
}
