import { X } from 'lucide-react'
import type { InsightResponse } from '../api/insights'

export interface AIInsightPanelProps {
  loading?: boolean
  error?: string | null
  insight?: InsightResponse | null
  title?: string
  onClose?: () => void
}

/**
 * Reusable slide-over / card component for AI-generated insights.
 *
 * Pure-presentational: the parent owns fetching and passes one of
 * {loading, error, insight} at a time. Renders a narrative paragraph,
 * a bullet list, and a footer with the model name plus a "Demo mode"
 * badge when the insight was produced from a canned (offline) response
 * rather than a live model call.
 */
export function AIInsightPanel({
  loading = false,
  error = null,
  insight = null,
  title = 'AI Insight',
  onClose,
}: AIInsightPanelProps) {
  return (
    <section
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

      {!loading && !error && insight && (
        <div data-testid="ai-insight-content">
          <p className="text-sm leading-relaxed text-slate-700 dark:text-slate-200 mb-3">
            {insight.narrative}
          </p>
          {insight.bullets.length > 0 && (
            <ul className="list-disc pl-5 space-y-1 text-sm text-slate-700 dark:text-slate-200 mb-3">
              {insight.bullets.map((b, i) => (
                <li key={i}>{b}</li>
              ))}
            </ul>
          )}
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

      {!loading && !error && !insight && (
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
