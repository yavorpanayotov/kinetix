import { useEffect, useState } from 'react'
import { fetchStressWindows } from '../api/stressWindows'
import type { StressWindowDto } from '../api/stressWindows'

export interface UseStressWindowsResult {
  windows: StressWindowDto[]
  loading: boolean
  error: string | null
}

// Stress windows are static for the demo (driven by RegimeCalendar.DEFAULT_AS_OF
// on the gateway), so we fetch once on mount and don't poll.
export function useStressWindows(): UseStressWindowsResult {
  const [windows, setWindows] = useState<StressWindowDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchStressWindows()
      .then((result) => {
        if (!cancelled) {
          setWindows(result)
          setError(null)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err))
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { windows, loading, error }
}
