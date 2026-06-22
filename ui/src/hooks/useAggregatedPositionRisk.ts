import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchPositionRisk } from '../api/risk'
import { aggregatePositionRisk } from '../utils/aggregatePositionRisk'
import type { PositionRiskDto } from '../types'
import type { UsePositionRiskResult } from './usePositionRisk'

/**
 * Firm/aggregated position risk: fetches each book's position-risk rows in
 * parallel and merges them by instrument (see [aggregatePositionRisk]) so the
 * aggregated Risk view shows exact firm-level greeks per instrument.
 *
 * Mirrors [usePositionRisk]'s result shape so RiskTab can swap between the two
 * by scope. Pass an empty `bookIds` list to disable (single-book view).
 */
export function useAggregatedPositionRisk(
  bookIds: string[],
  valuationDate: string | null = null,
): UsePositionRiskResult {
  const [positionRisk, setPositionRisk] = useState<PositionRiskDto[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Stable key so the effect only re-runs when the set of books (or date) changes.
  const key = bookIds.join(',')

  const load = useCallback(async () => {
    if (bookIds.length === 0) {
      setPositionRisk([])
      setLoading(false)
      setError(null)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const perBook = await Promise.all(
        bookIds.map((b) => fetchPositionRisk(b, valuationDate).catch(() => [] as PositionRiskDto[])),
      )
      setPositionRisk(aggregatePositionRisk(perBook))
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setPositionRisk([])
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, valuationDate])

  const loadRef = useRef(load)
  loadRef.current = load

  useEffect(() => {
    loadRef.current()
  }, [key, valuationDate])

  const refresh = useCallback(async () => {
    await loadRef.current()
  }, [])

  return { positionRisk, loading, error, refresh }
}
