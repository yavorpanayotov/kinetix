import { useEffect, useRef, useState } from 'react'
import { fetchIntradayPnl } from '../api/intradayPnl'
import type { IntradayPnlSnapshotDto } from '../types'

interface UseIntradayPnlSeriesWithFallbackResult {
  snapshots: IntradayPnlSnapshotDto[]
  loading: boolean
  error: string | null
  /**
   * When the chart is showing data from a past session (because today has no
   * snapshots), this is the ISO date string of that session (e.g. "2026-05-30").
   * Null when today has data or when there is no data at all.
   */
  sessionDate: string | null
}

/**
 * Builds the ISO date string (YYYY-MM-DD) from a snapshot timestamp.
 * Uses the UTC calendar day so results are consistent regardless of timezone.
 */
function snapshotDateUtc(snapshotAt: string): string {
  return snapshotAt.slice(0, 10)
}

/**
 * Given a list of snapshots, find the most-recent calendar day (UTC) that has
 * at least one snapshot and return those snapshots together with their date.
 * Returns null when the list is empty.
 */
function latestDayWithData(
  snapshots: IntradayPnlSnapshotDto[],
): { date: string; snapshots: IntradayPnlSnapshotDto[] } | null {
  if (snapshots.length === 0) return null

  const latestDate = snapshots
    .map((s) => snapshotDateUtc(s.snapshotAt))
    .sort()
    .at(-1)!

  return {
    date: latestDate,
    snapshots: snapshots.filter((s) => snapshotDateUtc(s.snapshotAt) === latestDate),
  }
}

/**
 * Returns the intraday P&L series for the given book and calendar date,
 * falling back to the most-recent day that has data (up to 7 days back) when
 * the requested date has no snapshots.
 *
 * `today` must be an ISO date string (YYYY-MM-DD, e.g. "2026-06-01").
 */
export function useIntradayPnlSeriesWithFallback(
  bookId: string | null,
  today: string,
): UseIntradayPnlSeriesWithFallbackResult {
  const [snapshots, setSnapshots] = useState<IntradayPnlSnapshotDto[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sessionDate, setSessionDate] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const loadKey = bookId ? `${bookId}|${today}` : ''
  const [prevLoadKey, setPrevLoadKey] = useState('')

  if (loadKey !== prevLoadKey) {
    setPrevLoadKey(loadKey)
    if (loadKey) {
      setLoading(true)
      setError(null)
      setSessionDate(null)
    } else {
      setSnapshots([])
      setLoading(false)
      setError(null)
      setSessionDate(null)
    }
  }

  useEffect(() => {
    if (!bookId) return

    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    const todayFrom = `${today}T00:00:00Z`
    const todayTo = `${today}T23:59:59Z`

    fetchIntradayPnl(bookId, todayFrom, todayTo)
      .then(async (data) => {
        if (controller.signal.aborted) return

        if (data.snapshots.length > 0) {
          // Today has data — use it directly, no fallback indicator needed.
          setSnapshots(data.snapshots)
          setSessionDate(null)
          return
        }

        // Today has no snapshots — widen the lookback window to 7 days.
        const sevenDaysAgo = new Date(today)
        sevenDaysAgo.setUTCDate(sevenDaysAgo.getUTCDate() - 7)
        const fallbackFrom = `${sevenDaysAgo.toISOString().slice(0, 10)}T00:00:00Z`
        const fallbackTo = `${today}T23:59:59Z`

        const fallbackData = await fetchIntradayPnl(bookId, fallbackFrom, fallbackTo)
        if (controller.signal.aborted) return

        const latest = latestDayWithData(fallbackData.snapshots)
        if (latest) {
          setSnapshots(latest.snapshots)
          setSessionDate(latest.date)
        } else {
          setSnapshots([])
          setSessionDate(null)
        }
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return
        setError(err instanceof Error ? err.message : String(err))
        setSnapshots([])
        setSessionDate(null)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })

    return () => {
      controller.abort()
    }
  }, [bookId, today])

  return { snapshots, loading, error, sessionDate }
}
