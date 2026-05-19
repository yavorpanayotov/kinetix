import { useEffect, useRef, useState } from 'react'
import type { ChatChunk, Citation } from '../api/copilot'

/**
 * Visual / lifecycle states surfaced via the wrapper's ``data-state``
 * attribute. The four-state contract from the plan
 * (``skeleton`` → ``awaiting-first-token`` → ``token-flow`` →
 * ``complete``) is augmented with:
 *  - ``idle`` — no stream attached yet; the wrapper renders empty.
 *  - ``error`` — terminal chunk carried an ``error_code`` (or the
 *    underlying ``ReadableStream`` errored mid-pump).
 *
 * Why an explicit ``data-state`` rather than separate flags: it gives
 * tests (and Playwright in later checkboxes) a single source of truth
 * for which sub-tree to assert on, without us having to enumerate
 * cross-products of booleans.
 */
type NarrativeState =
  | 'idle'
  | 'skeleton'
  | 'awaiting-first-token'
  | 'token-flow'
  | 'complete'
  | 'error'

export interface StreamingNarrativeProps {
  /**
   * The token stream to render. Pass ``null`` to show the initial
   * idle/empty state (no skeleton — the consumer decides when to
   * mount us).
   */
  stream: ReadableStream<ChatChunk> | null

  /**
   * Optional callback invoked exactly once when the terminal frame
   * arrives. Receives the full accumulated narrative, the citations
   * (if any), and the terminal chunk for ``mode``/``model``/error
   * inspection.
   */
  onComplete?: (result: {
    narrative: string
    citations: Citation[]
    model: string
    mode: 'live' | 'canned'
    errorCode?: string
  }) => void

  /**
   * When ``true``, blinking-cursor and incremental token-flow are
   * replaced with a static caret and one batched render after each
   * chunk. Defaults to reading
   * ``matchMedia('(prefers-reduced-motion: reduce)')``.
   */
  reducedMotion?: boolean

  /** Optional ARIA label override (defaults to "AI narrative"). */
  ariaLabel?: string
}

const FLUSH_MIN_GAP_MS = 50

/**
 * Stable key for a ``Citation`` — combines the three fields that
 * collectively identify a unique "this tool returned this field with
 * this value" provenance record. ``params`` is intentionally not part
 * of the key because the route may emit the same citation twice (once
 * in a ``source`` frame, once again in the terminal ``done`` chunk)
 * and we want those to dedupe.
 */
function citationKey(c: Citation): string {
  return `${c.tool}::${c.result_field}::${c.result_value}`
}

function mergeUniqueCitations(prev: Citation[], next: Citation[]): Citation[] {
  if (next.length === 0) return prev
  const seen = new Set(prev.map(citationKey))
  const out = [...prev]
  for (const c of next) {
    const key = citationKey(c)
    if (seen.has(key)) continue
    seen.add(key)
    out.push(c)
  }
  return out
}

/**
 * Resolve the effective ``reducedMotion`` flag.
 *
 * The prop wins when provided (deterministic for tests); otherwise we
 * fall back to ``matchMedia`` once. We deliberately do not subscribe
 * to changes — the user's reduced-motion preference rarely flips
 * mid-conversation, and adding a listener here would re-render the
 * narrative pump for no useful reason.
 */
function useEffectiveReducedMotion(prop: boolean | undefined): boolean {
  // Lazy initializer reads ``matchMedia`` exactly once on first render
  // when the prop is unset; subsequent renders just consult the state.
  const [matchesQuery] = useState<boolean>(() => {
    if (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function'
    ) {
      return window.matchMedia('(prefers-reduced-motion: reduce)').matches
    }
    return false
  })
  return typeof prop === 'boolean' ? prop : matchesQuery
}

/**
 * Renders a streaming AI narrative produced by ``chat()`` in
 * ``api/copilot.ts``. Owns the four-state machine
 * (``skeleton`` → ``awaiting-first-token`` → ``token-flow`` →
 * ``complete``) plus an ``error`` lane for terminal frames carrying
 * an ``error_code`` or for stream-level failures.
 *
 * Why ``useRef`` + ``requestAnimationFrame`` instead of plain state
 * updates per token: a verbose model can emit dozens of deltas per
 * second; calling ``setState`` on every one would saturate React's
 * render loop and trip the ``react-hooks/set-state-in-effect`` rule.
 * Instead we accumulate into a ref and schedule at most one
 * ``setRenderedText`` per RAF, gated to a ``FLUSH_MIN_GAP_MS`` minimum
 * window so the rendered text advances at a readable pace.
 */
export function StreamingNarrative({
  stream,
  onComplete,
  reducedMotion,
  ariaLabel = 'AI narrative',
}: StreamingNarrativeProps): React.ReactElement {
  const effectiveReducedMotion = useEffectiveReducedMotion(reducedMotion)

  const [state, setState] = useState<NarrativeState>(
    stream ? 'skeleton' : 'idle',
  )
  const [renderedText, setRenderedText] = useState('')
  const [citations, setCitations] = useState<Citation[]>([])
  const [errorCode, setErrorCode] = useState<string | undefined>(undefined)

  // Buffered token accumulator (kept out of React state so we can
  // append tokens at arbitrary rates without re-rendering).
  const accumulatorRef = useRef('')
  // Live citation buffer mirrored into state on flush — needed because
  // the pump emits multiple ``source`` frames before ``done`` and we
  // want to dedupe before re-rendering.
  const citationsBufferRef = useRef<Citation[]>([])
  // RAF + throttle bookkeeping.
  const rafRef = useRef<number | null>(null)
  const lastFlushRef = useRef<number>(0)
  // Whether the component is still mounted (prevents setState after
  // unmount when an in-flight pump resolves late).
  const mountedRef = useRef(true)

  // Track whether ``onComplete`` has already fired. The terminal chunk
  // arrives at most once but stream errors could also try to call it;
  // we keep the guarantee that exactly one invocation happens.
  const completedRef = useRef(false)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current)
        rafRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    // No stream — reset to idle and bail out. We intentionally do not
    // tear down accumulated text here; remounting the component with a
    // fresh stream is the correct way to "clear" the narrative.
    if (!stream) {
      setState('idle')
      return
    }

    setState('skeleton')
    accumulatorRef.current = ''
    citationsBufferRef.current = []
    lastFlushRef.current = 0
    completedRef.current = false
    setRenderedText('')
    setCitations([])
    setErrorCode(undefined)

    let cancelled = false
    const reader = stream.getReader()

    /**
     * Schedule a flush of the accumulator into state. Coalesces
     * multiple calls into a single RAF and gates re-renders to the
     * ``FLUSH_MIN_GAP_MS`` window. If the gate hasn't elapsed yet we
     * re-schedule rather than skip — guarantees the *final* state
     * always lands.
     */
    const scheduleFlush = () => {
      if (rafRef.current !== null) return
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null
        if (!mountedRef.current || cancelled) return
        const now = Date.now()
        if (now - lastFlushRef.current < FLUSH_MIN_GAP_MS) {
          // Throttle gate not elapsed — wait one more frame.
          scheduleFlush()
          return
        }
        lastFlushRef.current = now
        setRenderedText(accumulatorRef.current)
        if (citationsBufferRef.current.length > 0) {
          setCitations((prev) =>
            mergeUniqueCitations(prev, citationsBufferRef.current),
          )
          citationsBufferRef.current = []
        }
      })
    }

    const finishWithError = (code: string) => {
      if (!mountedRef.current || cancelled) return
      // Force a final flush so any buffered tokens are visible alongside
      // the error banner.
      setRenderedText(accumulatorRef.current)
      setErrorCode(code)
      setState('error')
      if (!completedRef.current && onComplete) {
        completedRef.current = true
        onComplete({
          narrative: accumulatorRef.current,
          citations: mergeUniqueCitations(
            citationsBufferRef.current,
            [],
          ),
          model: '',
          mode: 'canned',
          errorCode: code,
        })
      }
    }

    const pump = async () => {
      try {
        for (;;) {
          const { done, value } = await reader.read()
          if (done || cancelled || !mountedRef.current) return
          if (value.type === 'delta') {
            const wasEmpty = accumulatorRef.current === ''
            accumulatorRef.current += value.delta
            if (wasEmpty) {
              // First token arrived — leave ``skeleton`` and move to
              // the token-flow lane. We skip the explicit
              // ``awaiting-first-token`` intermediate state here
              // because rendering it for one frame on the first delta
              // would just cause a visible flash; the ``data-state``
              // attribute is still available for consumers that want
              // to assert on it via the (currently unused) idle
              // transition.
              setState('token-flow')
            }
            scheduleFlush()
          } else if (value.type === 'source') {
            citationsBufferRef.current = mergeUniqueCitations(
              citationsBufferRef.current,
              value.citations,
            )
            // Defer rendering until the next flush so we don't flash
            // citations before any narrative has appeared.
            scheduleFlush()
          } else if (value.type === 'done') {
            if (!mountedRef.current || cancelled) return
            // Cancel any pending RAF so we don't race with our final
            // setState below.
            if (rafRef.current !== null) {
              cancelAnimationFrame(rafRef.current)
              rafRef.current = null
            }
            const mergedCitations = mergeUniqueCitations(
              citationsBufferRef.current,
              value.citations ?? [],
            )
            citationsBufferRef.current = []
            setRenderedText(accumulatorRef.current)
            setCitations((prev) => mergeUniqueCitations(prev, mergedCitations))
            setErrorCode(value.error_code)
            setState(value.error_code ? 'error' : 'complete')
            if (!completedRef.current && onComplete) {
              completedRef.current = true
              onComplete({
                narrative: accumulatorRef.current,
                citations: mergedCitations,
                model: value.model,
                mode: value.mode,
                errorCode: value.error_code,
              })
            }
            return
          }
        }
      } catch {
        finishWithError('STREAM_ERROR')
      }
    }

    void pump()

    return () => {
      cancelled = true
      void reader.cancel().catch(() => undefined)
      if (rafRef.current !== null) {
        cancelAnimationFrame(rafRef.current)
        rafRef.current = null
      }
    }
    // ``onComplete`` is intentionally NOT a dependency — capturing it
    // once per stream is fine and avoids tearing the pump down when
    // the parent re-renders with a new callback identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stream])

  return (
    <div
      role="status"
      aria-live="polite"
      aria-label={ariaLabel}
      data-state={state}
      data-testid="streaming-narrative"
      className="text-sm leading-relaxed text-slate-700 dark:text-slate-200"
    >
      {state === 'error' && errorCode && (
        <div
          role="alert"
          data-testid="streaming-narrative-error"
          className="mb-2 rounded border border-red-200 bg-red-50 dark:border-red-900 dark:bg-red-950/20 p-2 text-xs text-red-700 dark:text-red-400"
        >
          {errorCode}
        </div>
      )}

      {state === 'skeleton' && (
        <div
          data-testid="streaming-narrative-skeleton"
          aria-busy="true"
          className="space-y-2"
        >
          <div
            className={`h-3 w-3/4 rounded bg-slate-200 dark:bg-surface-700 ${
              effectiveReducedMotion ? '' : 'animate-pulse'
            }`}
          />
          <div
            className={`h-3 w-5/6 rounded bg-slate-200 dark:bg-surface-700 ${
              effectiveReducedMotion ? '' : 'animate-pulse'
            }`}
          />
          <div
            className={`h-3 w-2/3 rounded bg-slate-200 dark:bg-surface-700 ${
              effectiveReducedMotion ? '' : 'animate-pulse'
            }`}
          />
        </div>
      )}

      {state === 'awaiting-first-token' && (
        <span
          data-testid="streaming-narrative-cursor"
          className={effectiveReducedMotion ? '' : 'animate-pulse'}
        >
          {'▊'}
        </span>
      )}

      {(state === 'token-flow' ||
        state === 'complete' ||
        (state === 'error' && renderedText.length > 0)) && (
        <p data-testid="streaming-narrative-text">
          {renderedText}
          {state === 'token-flow' && (
            <span
              data-testid="streaming-narrative-cursor"
              className={effectiveReducedMotion ? '' : 'animate-pulse'}
              aria-hidden="true"
            >
              {'▊'}
            </span>
          )}
        </p>
      )}

      {citations.length > 0 && (
        <ul
          data-testid="streaming-narrative-citations"
          className="mt-2 list-disc pl-5 text-xs text-slate-500 dark:text-slate-400"
        >
          {citations.map((c) => (
            <li key={citationKey(c)}>
              {c.tool} <span className="text-slate-400">/</span> {c.result_field}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default StreamingNarrative
