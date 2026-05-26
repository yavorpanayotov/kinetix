import { useEffect, useState, type ReactNode } from 'react'

/**
 * DemoBootstrapGate (kx-abw) — wraps a route subtree and surfaces a
 * non-blocking splash overlay while the demo-orchestrator is still
 * initialising book data.
 *
 * Behaviour:
 *   - Polls `GET /demo/bootstrap-status` every 2s with exponential backoff
 *     on transient failures (cap at 30s).
 *   - While `state` is NOT_STARTED or IN_PROGRESS, renders a thin overlay
 *     banner that does NOT block interaction with the children below it
 *     ("Demo environment initializing… (N of 8 books ready)").
 *   - When `state` becomes READY, the overlay is permanently dismissed.
 *   - When `state` becomes FAILED, the overlay is dismissed and the failure
 *     is logged to `console.error` so an operator can see it. Children
 *     continue to render — the UI must never be hard-blocked by demo
 *     bootstrap failure.
 *   - When the endpoint returns 404 or the fetch throws (non-demo
 *     environments where demo-orchestrator isn't deployed), the gate
 *     permanently dismisses on the very first poll so non-demo users never
 *     see a stuck splash.
 *
 * The gate intentionally does NOT block route rendering. It is a *gate* in
 * the sense of "gate-keeper informing the user that data is loading," not
 * in the sense of "wall preventing the user from interacting." The
 * downstream components already render empty / loading states gracefully;
 * a hard-block would degrade the demo experience more than a soft overlay.
 */

const BOOTSTRAP_STATUS_URL = '/demo/bootstrap-status'
const INITIAL_POLL_MS = 2_000
const MAX_POLL_MS = 30_000
const TOTAL_BOOKS = 8

type BootstrapState = 'NOT_STARTED' | 'IN_PROGRESS' | 'READY' | 'FAILED'

interface BootstrapStatus {
  state: BootstrapState
  successCount?: number | null
  failureCount?: number | null
  failedBooks?: string[] | null
}

interface DemoBootstrapGateProps {
  children: ReactNode
}

export function DemoBootstrapGate({ children }: DemoBootstrapGateProps) {
  const [status, setStatus] = useState<BootstrapStatus | null>(null)
  const [dismissed, setDismissed] = useState(false)

  useEffect(() => {
    if (dismissed) return

    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let backoffMs = INITIAL_POLL_MS

    const schedule = (delay: number) => {
      if (cancelled) return
      timer = setTimeout(poll, delay)
    }

    async function poll(): Promise<void> {
      try {
        const res = await fetch(BOOTSTRAP_STATUS_URL)
        if (cancelled) return

        // 404 → the orchestrator simply isn't deployed (non-demo env).
        // Anything else 4xx/5xx → treat as transient and back off.
        if (res.status === 404) {
          setDismissed(true)
          return
        }
        if (!res.ok) {
          backoffMs = Math.min(backoffMs * 2, MAX_POLL_MS)
          schedule(backoffMs)
          return
        }

        const body = (await res.json()) as BootstrapStatus
        if (cancelled) return

        // Reset backoff on a successful read.
        backoffMs = INITIAL_POLL_MS
        setStatus(body)

        if (body.state === 'READY') {
          setDismissed(true)
          return
        }
        if (body.state === 'FAILED') {
          // Log so an operator running the demo can see what went wrong,
          // but don't block the UI — they still need to be able to click
          // around the rest of the app.
          console.error(
            '[DemoBootstrapGate] demo bootstrap failed',
            {
              successCount: body.successCount,
              failureCount: body.failureCount,
              failedBooks: body.failedBooks,
            },
          )
          setDismissed(true)
          return
        }

        // Still NOT_STARTED / IN_PROGRESS — poll again at the base cadence.
        schedule(INITIAL_POLL_MS)
      } catch {
        if (cancelled) return
        // Network / parse error. On the very first call this almost
        // certainly means the demo-orchestrator endpoint isn't reachable
        // (e.g. non-demo deployment, dev proxy down). Permanently
        // dismissing here keeps non-demo users from seeing a stuck splash.
        console.warn(
          '[DemoBootstrapGate] bootstrap-status unreachable — gate disabled',
        )
        setDismissed(true)
      }
    }

    void poll()

    return () => {
      cancelled = true
      if (timer !== null) clearTimeout(timer)
    }
  }, [dismissed])

  const showSplash =
    !dismissed &&
    status !== null &&
    (status.state === 'NOT_STARTED' || status.state === 'IN_PROGRESS')

  const successCount = status?.successCount ?? 0

  return (
    <>
      {showSplash && (
        <div
          data-testid="demo-bootstrap-splash"
          role="status"
          aria-live="polite"
          aria-label="Demo environment initializing"
          className="bg-primary-500/10 border-b border-primary-500/25 text-primary-200 dark:text-primary-300 px-6 py-2 text-sm flex items-center gap-2"
        >
          <span
            data-testid="demo-bootstrap-splash-spinner"
            aria-hidden="true"
            className="inline-block h-3 w-3 rounded-full border-2 border-primary-300 border-t-transparent animate-spin"
          />
          <span>
            Demo environment initializing… ({successCount} of {TOTAL_BOOKS}{' '}
            books ready)
          </span>
        </div>
      )}
      {children}
    </>
  )
}
