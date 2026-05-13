import { useEffect, useState } from 'react'
import { fetchTraders, type TraderDto } from '../api/traders'

export interface UseTradersResult {
  traders: TraderDto[]
  loading: boolean
  error: string | null
}

// Trader catalogue is small (3-8 per desk × 8 desks) and reference data, so a
// single fetch on mount is sufficient; no polling required.
export function useTraders(): UseTradersResult {
  const [traders, setTraders] = useState<TraderDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const result = await fetchTraders()
        if (!cancelled) {
          setTraders(result)
          setError(null)
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err))
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [])

  return { traders, loading, error }
}
