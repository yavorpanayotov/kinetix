import { useEffect, useState } from 'react'
import { fetchActiveScenario } from '../api/scenario'

const POLL_INTERVAL = 30_000

export interface UseActiveScenarioResult {
  scenario: string | null
  loading: boolean
  error: string | null
}

// The active scenario can change when an operator runs a demo-reset with a
// different scenario flag, so we poll periodically rather than fetch once.
export function useActiveScenario(): UseActiveScenarioResult {
  const [scenario, setScenario] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const result = await fetchActiveScenario()
        if (!cancelled) {
          setScenario(result.scenario)
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
    const id = window.setInterval(() => void load(), POLL_INTERVAL)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [])

  return { scenario, loading, error }
}
