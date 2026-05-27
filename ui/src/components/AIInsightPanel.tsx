import { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'
import type { ChatChunk, Citation } from '../api/copilot'
import type { InsightResponse } from '../api/insights'
import { AIMarkdown } from './AIMarkdown'
import { StreamingNarrative } from './StreamingNarrative'
import { CitationList } from './CitationList'

/**
 * Build a single markdown source for the buffered (non-streaming)
 * insight branch by appending the structured bullets to the narrative.
 * The model emits narrative as a paragraph and bullets as discrete
 * strings (which themselves may contain inline markdown such as
 * ``**bold**`` or `` `tickers` ``). Re-assembling them into one
 * markdown blob lets ``AIMarkdown`` apply consistent typography to
 * both, instead of having a hand-built ``<ul>`` for bullets that
 * silently strips inline emphasis.
 */
function buildBufferedMarkdown(insight: InsightResponse): string {
  if (insight.bullets.length === 0) return insight.narrative
  const body = insight.narrative.trim()
  const list = insight.bullets.map((b) => `- ${b}`).join('\n')
  return body.length > 0 ? `${body}\n\n${list}` : list
}

export interface AIInsightPanelProps {
  loading?: boolean
  error?: string | null
  insight?: InsightResponse | null
  title?: string
  onClose?: () => void
  /**
   * Streaming variant. When provided and non-null, the panel composes
   * <StreamingNarrative> instead of rendering a buffered insight. Takes
   * precedence over `insight` when both are supplied — the stream IS
   * the in-flight insight. `loading`/`error` still take precedence
   * over the stream so callers can short-circuit before the stream
   * opens.
   */
  stream?: ReadableStream<ChatChunk> | null

  /**
   * Fires once when the streaming response terminates (clean or
   * error). Used by callers that maintain a "busy" affordance on the
   * triggering Explain button — they flip it off when this fires so
   * the button stops spinning once the model has answered.
   */
  onStreamComplete?: () => void
}

interface StreamResult {
  citations: Citation[]
  model: string
  mode: 'live' | 'canned'
}

/**
 * Reusable slide-over / card component for AI-generated insights.
 *
 * Pure-presentational: the parent owns fetching and passes one of
 * {loading, error, insight, stream} at a time. The buffered `insight`
 * path renders a narrative paragraph + bullet list (v1 explainers).
 * The `stream` path composes <StreamingNarrative> for token-flow and,
 * once the stream finishes, a <CitationList> footer plus the model /
 * "Demo mode" chrome.
 */
export function AIInsightPanel({
  loading = false,
  error = null,
  insight = null,
  title = 'AI Insight',
  onClose,
  stream = null,
  onStreamComplete,
}: AIInsightPanelProps) {
  const [streamResult, setStreamResult] = useState<StreamResult | null>(null)
  const sectionRef = useRef<HTMLElement | null>(null)

  const showStream = !loading && !error && stream != null

  // Per-row Explain buttons in long tables render this panel at a
  // location the user often cannot see (bottom of a 20-row position
  // table, header section above the fold, etc.). Without an explicit
  // scroll the user clicks Explain and sees nothing — the click
  // registers but the result lands off-screen, giving the impression
  // the button is broken. ``scrollIntoView({block:'nearest'})`` is a
  // no-op when the panel is already on-screen and pulls it into view
  // otherwise, with smooth motion respected by ``prefers-reduced-motion``
  // at the OS / browser level.
  useEffect(() => {
    const node = sectionRef.current
    if (!node) return
    if (!loading && !error && stream == null && insight == null) return
    // jsdom (used by Vitest) does not implement scrollIntoView; guard
    // so unit tests don't crash on the feature-detection edge.
    if (typeof node.scrollIntoView !== 'function') return
    node.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [stream, insight, loading, error])

  return (
    <section
      ref={sectionRef}
      data-testid="ai-insight-panel"
      aria-label={title}
      className="rounded-lg border border-slate-200 dark:border-surface-700 bg-white dark:bg-surface-800 p-4 shadow-sm"
    >
      <header className="flex items-center justify-between mb-3">
        <h3 className="text-base font-semibold text-slate-800 dark:text-slate-100">
          {title}
        </h3>
        {onClose && (
          <button
            type="button"
            data-testid="ai-insight-close"
            aria-label="Close insight panel"
            onClick={onClose}
            className="flex-shrink-0 p-1 rounded text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <X className="h-4 w-4" />
          </button>
        )}
      </header>

      {loading && (
        <div data-testid="ai-insight-loading" className="space-y-2" aria-busy="true">
          <div className="h-4 w-3/4 animate-pulse rounded bg-slate-200 dark:bg-surface-700" />
          <div className="h-4 w-5/6 animate-pulse rounded bg-slate-200 dark:bg-surface-700" />
          <div className="h-4 w-2/3 animate-pulse rounded bg-slate-200 dark:bg-surface-700" />
        </div>
      )}

      {!loading && error && (
        <div
          data-testid="ai-insight-error"
          role="alert"
          className="rounded border border-red-200 bg-red-50 dark:border-red-900 dark:bg-red-950/20 p-3 text-sm text-red-700 dark:text-red-400"
        >
          {error}
        </div>
      )}

      {showStream && (
        <div data-testid="ai-insight-streaming">
          <StreamingNarrative
            stream={stream}
            onComplete={({ citations, model, mode }) => {
              setStreamResult({ citations, model, mode })
              onStreamComplete?.()
            }}
          />
          {streamResult && streamResult.citations.length > 0 && (
            <div data-testid="ai-insight-citations" className="mt-3">
              <CitationList citations={streamResult.citations} />
            </div>
          )}
          {streamResult && (
            <footer className="mt-3 flex items-center justify-between border-t border-slate-100 dark:border-surface-700 pt-2 text-xs text-slate-500 dark:text-slate-400">
              <span data-testid="ai-insight-model">{streamResult.model}</span>
              {streamResult.mode === 'canned' && (
                <span
                  data-testid="ai-insight-demo-badge"
                  className="rounded bg-amber-100 dark:bg-amber-900/30 px-2 py-0.5 text-amber-800 dark:text-amber-300"
                >
                  Demo mode
                </span>
              )}
            </footer>
          )}
        </div>
      )}

      {!loading && !error && !showStream && insight && (
        <div data-testid="ai-insight-content">
          <AIMarkdown source={buildBufferedMarkdown(insight)} className="mb-3" />
          <footer className="flex items-center justify-between border-t border-slate-100 dark:border-surface-700 pt-2 text-xs text-slate-500 dark:text-slate-400">
            <span data-testid="ai-insight-model">{insight.model}</span>
            {insight.mode === 'canned' && (
              <span
                data-testid="ai-insight-demo-badge"
                className="rounded bg-amber-100 dark:bg-amber-900/30 px-2 py-0.5 text-amber-800 dark:text-amber-300"
              >
                Demo mode
              </span>
            )}
          </footer>
        </div>
      )}

      {!loading && !error && !showStream && !insight && (
        <p
          data-testid="ai-insight-empty"
          className="text-sm italic text-slate-500 dark:text-slate-400"
        >
          No insight available.
        </p>
      )}
    </section>
  )
}

export default AIInsightPanel
