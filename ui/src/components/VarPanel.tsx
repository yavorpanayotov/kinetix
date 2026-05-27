import { useEffect, useState } from 'react'

// VaR panel with a not-ready / bootstrap state (kx-yh8s).
//
// Until the risk engine has run the first VaR cycle of the session, the
// dashboard has nothing meaningful to show. Rather than render zeros or
// blank space (both easily mis-read as a real number), the panel switches
// into an explicit "bootstrap in progress" state with a running timer so
// the user can see that something is happening and roughly how long it has
// been waiting.
//
// Once a result is supplied, the timer interval is torn down and the value
// renders normally. The component is intentionally presentational — it does
// not fetch anything itself — so parents can wire it to a real WebSocket /
// polling source without dragging that logic into the test surface.

export interface VarResult {
  value: number
  currency: string
  confidence: number
}

interface VarPanelProps {
  result: VarResult | null
  bootstrapStartedAt: Date
}

function formatElapsed(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const mins = Math.floor(seconds / 60)
  const rem = seconds % 60
  return `${mins}m${rem}s`
}

export function VarPanel({ result, bootstrapStartedAt }: VarPanelProps) {
  const startMs = bootstrapStartedAt.getTime()
  const [elapsedSeconds, setElapsedSeconds] = useState(0)

  useEffect(() => {
    if (result !== null) return
    const id = setInterval(() => {
      const now = Date.now()
      setElapsedSeconds(Math.max(0, Math.floor((now - startMs) / 1000)))
    }, 1_000)
    return () => clearInterval(id)
  }, [result, startMs])

  if (result === null) {
    return (
      <div className="rounded border border-amber-200 bg-amber-50 dark:bg-amber-900/20 dark:border-amber-800 p-4">
        <div role="status" aria-live="polite" className="text-sm text-amber-800 dark:text-amber-200">
          VaR bootstrap in progress…
        </div>
        <div
          data-testid="var-bootstrap-timer"
          className="mt-1 font-mono text-xs text-amber-700 dark:text-amber-300"
        >
          {formatElapsed(elapsedSeconds)}
        </div>
      </div>
    )
  }

  return (
    <div className="rounded border border-slate-200 dark:border-slate-700 p-4">
      <div className="text-xs text-slate-500 dark:text-slate-400">
        VaR @ {(result.confidence * 100).toFixed(0)}%
      </div>
      <div
        data-testid="var-result-value"
        className="text-2xl font-semibold text-slate-900 dark:text-slate-100"
      >
        {result.value.toFixed(2)}
      </div>
      <div className="text-xs text-slate-500 dark:text-slate-400">{result.currency}</div>
    </div>
  )
}
