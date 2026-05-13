import { useEffect, useState } from 'react'
import { fetchTapeReplayStatus, type TapeReplayStatus } from '../api/tapeReplay'

const POLL_INTERVAL = 30_000

export interface UseTapeReplayStatusResult {
  status: TapeReplayStatus | null
  loading: boolean
  error: string | null
}

// Tape replay state can flip between resets (operators toggle the env flag on
// the position-service container), so poll on the same cadence as scenario.
export function useTapeReplayStatus(): UseTapeReplayStatusResult {
  const [status, setStatus] = useState<TapeReplayStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const result = await fetchTapeReplayStatus()
        if (!cancelled) {
          setStatus(result.status)
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

  return { status, loading, error }
}
