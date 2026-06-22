import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchChartData, type ChartDataPoint } from '../api/jobHistory'
import { resolveTimeRange } from '../utils/resolveTimeRange'
import { aggregateGreeksHistory } from '../utils/aggregateGreeksHistory'
import type { TimeRange } from '../types'
import type { VaRHistoryEntry } from './useVaR'

export interface UseAggregatedGreeksHistoryResult {
  history: VaRHistoryEntry[]
  loading: boolean
}

/**
 * Firm/aggregated greeks time-series: fetches each book's job-chart buckets in
 * parallel and sums the additive greeks per bucket (see [aggregateGreeksHistory]).
 * Feeds the Greeks-trend chart in the aggregated Risk view.
 *
 * Pass an empty `bookIds` to disable (single-book view drives the chart from
 * its own `useVaR` history instead).
 */
export function useAggregatedGreeksHistory(
  bookIds: string[],
  timeRange: TimeRange,
): UseAggregatedGreeksHistoryResult {
  const [history, setHistory] = useState<VaRHistoryEntry[]>([])
  const [loading, setLoading] = useState(false)

  const key = `${bookIds.join(',')}|${timeRange}`

  const load = useCallback(async () => {
    if (bookIds.length === 0) {
      setHistory([])
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      const { from, to } = resolveTimeRange(timeRange)
      const perBook = await Promise.all(
        bookIds.map((b) =>
          fetchChartData(b, from, to)
            .then((r) => r.points)
            .catch(() => [] as ChartDataPoint[]),
        ),
      )
      setHistory(aggregateGreeksHistory(perBook))
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const loadRef = useRef(load)
  loadRef.current = load

  useEffect(() => {
    loadRef.current()
  }, [key])

  return { history, loading }
}
