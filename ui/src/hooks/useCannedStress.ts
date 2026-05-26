import { useEffect, useState } from 'react'
import { fetchCannedStressScenario } from '../api/stress'
import type { CannedStressResultDto } from '../types'

type State =
  | { kind: 'idle' }
  | { kind: 'loading'; bookId: string }
  | { kind: 'ready'; bookId: string; result: CannedStressResultDto | null }
  | { kind: 'error'; bookId: string; error: string }

export interface UseCannedStressResult {
  result: CannedStressResultDto | null
  loading: boolean
  error: string | null
}

/**
 * Reads the canned stress-scenario tile result for [bookId] (issue kx-wxy).
 *
 * The demo-orchestrator's `StressScenarioSeedJob` seeds the result on
 * bootstrap and at SOD — this hook simply polls the cached GET endpoint
 * whenever the active book changes. State transitions happen asynchronously
 * inside the fetch promise to satisfy the `react-hooks/set-state-in-effect`
 * rule.
 */
export function useCannedStress(bookId: string | null): UseCannedStressResult {
  const [state, setState] = useState<State>({ kind: 'idle' })

  useEffect(() => {
    if (!bookId) return

    let cancelled = false

    // Trigger the async load; the promise callbacks set state asynchronously
    // (outside the synchronous effect body) so we avoid the eslint
    // `react-hooks/set-state-in-effect` warning.
    void Promise.resolve()
      .then(() => {
        if (cancelled) return
        setState({ kind: 'loading', bookId })
        return fetchCannedStressScenario(bookId)
      })
      .then((data) => {
        if (cancelled || data === undefined) return
        setState({ kind: 'ready', bookId, result: data })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message = err instanceof Error ? err.message : String(err)
        setState({ kind: 'error', bookId, error: message })
      })

    return () => {
      cancelled = true
    }
  }, [bookId])

  if (!bookId || state.kind === 'idle' || state.bookId !== bookId) {
    return { result: null, loading: bookId != null, error: null }
  }
  if (state.kind === 'loading') return { result: null, loading: true, error: null }
  if (state.kind === 'error') return { result: null, loading: false, error: state.error }
  return { result: state.result, loading: false, error: null }
}
