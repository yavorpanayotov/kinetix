import { useEffect, useRef, useState } from 'react'
import { fetchIntradayVaRTimeline } from '../api/intradayVaRTimeline'
import type { IntradayVaRPointDto, TradeAnnotationDto } from '../types'

interface UseIntradayVaRTimelineWithFallbackResult {
  varPoints: IntradayVaRPointDto[]
  tradeAnnotations: TradeAnnotationDto[]
  loading: boolean
  error: string | null
  /**
   * When the chart is showing data from a past session (because today has no
   * VaR points), this is the ISO date string of that session (e.g. "2026-05-30").
   * Null when today has data or when there is no data at all.
   */
  sessionDate: string | null
}

/**
 * Returns the UTC calendar date string (YYYY-MM-DD) from an ISO timestamp.
 */
function pointDateUtc(timestamp: string): string {
  return timestamp.slice(0, 10)
}

/**
 * Given a list of VaR points, find the most-recent calendar day (UTC) that has
 * at least one point and return those points together with their date.
 * Returns null when the list is empty.
 */
function latestDayWithData(
  varPoints: IntradayVaRPointDto[],
): { date: string; varPoints: IntradayVaRPointDto[] } | null {
  if (varPoints.length === 0) return null

  const latestDate = varPoints
    .map((p) => pointDateUtc(p.timestamp))
    .sort()
    .at(-1)!

  return {
    date: latestDate,
    varPoints: varPoints.filter((p) => pointDateUtc(p.timestamp) === latestDate),
  }
}

/**
 * Returns the intraday VaR timeline for the given book and calendar date,
 * falling back to the most-recent day that has data (up to 7 days back) when
 * the requested date has no VaR points.
 *
 * `today` must be an ISO date string (YYYY-MM-DD, e.g. "2026-06-01").
 */
export function useIntradayVaRTimelineWithFallback(
  bookId: string | null,
  today: string,
): UseIntradayVaRTimelineWithFallbackResult {
  const [varPoints, setVarPoints] = useState<IntradayVaRPointDto[]>([])
  const [tradeAnnotations, setTradeAnnotations] = useState<TradeAnnotationDto[]>([])
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
      setVarPoints([])
      setTradeAnnotations([])
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

    fetchIntradayVaRTimeline(bookId, todayFrom, todayTo)
      .then(async (data) => {
        if (controller.signal.aborted) return

        if (data.varPoints.length > 0) {
          // Today has data — use it directly, no fallback indicator needed.
          setVarPoints(data.varPoints)
          setTradeAnnotations(data.tradeAnnotations)
          setSessionDate(null)
          return
        }

        // Today has no points — widen the lookback window to 7 days.
        const sevenDaysAgo = new Date(today)
        sevenDaysAgo.setUTCDate(sevenDaysAgo.getUTCDate() - 7)
        const fallbackFrom = `${sevenDaysAgo.toISOString().slice(0, 10)}T00:00:00Z`
        const fallbackTo = `${today}T23:59:59Z`

        const fallbackData = await fetchIntradayVaRTimeline(bookId, fallbackFrom, fallbackTo)
        if (controller.signal.aborted) return

        const latest = latestDayWithData(fallbackData.varPoints)
        if (latest) {
          // Filter annotations to only those on the fallback date too.
          const datePrefix = latest.date
          const filteredAnnotations = fallbackData.tradeAnnotations.filter(
            (a) => a.timestamp.startsWith(datePrefix),
          )
          setVarPoints(latest.varPoints)
          setTradeAnnotations(filteredAnnotations)
          setSessionDate(latest.date)
        } else {
          setVarPoints([])
          setTradeAnnotations([])
          setSessionDate(null)
        }
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return
        setError(err instanceof Error ? err.message : String(err))
        setVarPoints([])
        setTradeAnnotations([])
        setSessionDate(null)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })

    return () => {
      controller.abort()
    }
  }, [bookId, today])

  return { varPoints, tradeAnnotations, loading, error, sessionDate }
}
