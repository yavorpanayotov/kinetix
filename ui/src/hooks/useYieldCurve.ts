import { useEffect, useState } from 'react'
import { fetchYieldCurve } from '../api/yieldCurve'
import type { YieldCurve } from '../api/yieldCurve'

export interface UseYieldCurveResult {
  curve: YieldCurve | null
  loading: boolean
  error: string | null
}

export function useYieldCurve(currency: string | null): UseYieldCurveResult {
  const [curve, setCurve] = useState<YieldCurve | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [prevCurrency, setPrevCurrency] = useState<string | null>(null)

  // Reset state synchronously when the requested currency changes; the effect
  // below then drives the network fetch. This mirrors the prev-prop pattern
  // used elsewhere (e.g. useKrd) to avoid setState-in-effect cascades.
  if (currency !== prevCurrency) {
    setPrevCurrency(currency)
    if (!currency) {
      setCurve(null)
      setLoading(false)
      setError(null)
    } else {
      setLoading(true)
      setError(null)
    }
  }

  useEffect(() => {
    if (!currency) return

    let cancelled = false

    fetchYieldCurve(currency)
      .then((data) => {
        if (cancelled) return
        setCurve(data)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof Error ? err.message : String(err))
        setCurve(null)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [currency])

  return { curve, loading, error }
}
