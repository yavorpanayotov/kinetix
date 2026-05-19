import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchCrossBookVaR, triggerCrossBookVaR } from '../api/risk'
import type { CrossBookVaRResultDto } from '../types'

export interface UseCrossBookVaRResult {
  result: CrossBookVaRResultDto | null
  loading: boolean
  refreshing: boolean
  error: string | null
  refresh: () => Promise<void>
}

const POLL_INTERVAL = 30_000

export function useCrossBookVaR(
  bookIds: string[],
  portfolioGroupId: string | null,
): UseCrossBookVaRResult {
  const [result, setResult] = useState<CrossBookVaRResultDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const pollingRef = useRef(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])

  // Fetch cached result
  useEffect(() => {
    if (!portfolioGroupId || bookIds.length === 0) {
      setResult(null)
      return
    }

    let cancelled = false
    const load = async () => {
      setLoading(true)
      try {
        const data = await fetchCrossBookVaR(portfolioGroupId)
        if (!cancelled && mountedRef.current) {
          setResult(data)
          setError(null)
        }
      } catch (e) {
        if (!cancelled && mountedRef.current) {
          setError(e instanceof Error ? e.message : 'Failed to fetch cross-book VaR')
        }
      } finally {
        if (!cancelled && mountedRef.current) {
          setLoading(false)
        }
      }
    }
    load()

    // Poll periodically
    const interval = setInterval(() => {
      if (!pollingRef.current) {
        pollingRef.current = true
        fetchCrossBookVaR(portfolioGroupId)
          .then(data => {
            if (!cancelled && mountedRef.current && data) {
              setResult(data)
              setError(null)
            }
          })
          .catch(() => { /* swallow polling errors */ })
          .finally(() => { pollingRef.current = false })
      }
    }, POLL_INTERVAL)

    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [portfolioGroupId, bookIds.length])

  const refresh = useCallback(async () => {
    if (!portfolioGroupId || bookIds.length === 0) return
    setRefreshing(true)
    setError(null)
    try {
      const data = await triggerCrossBookVaR({
        bookIds,
        portfolioGroupId,
      })
      if (mountedRef.current) {
        setResult(data)
      }
    } catch (e) {
      if (mountedRef.current) {
        setError(e instanceof Error ? e.message : 'Failed to calculate cross-book VaR')
      }
    } finally {
      if (mountedRef.current) {
        setRefreshing(false)
      }
    }
  }, [bookIds, portfolioGroupId])

  return { result, loading, refreshing, error, refresh }
}
