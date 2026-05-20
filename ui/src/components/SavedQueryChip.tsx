import { Lock, X } from 'lucide-react'
import type { SavedQuery } from '../api/savedQueries'

/**
 * Plan §8.3 — a single saved-copilot-query pill.
 *
 * Renders exactly ONE chip; lists/groups of chips are composed by the
 * consumers (`<NotificationInbox>` for the five built-in defaults at the
 * top of the inbox, `<CommandPalette>` for the "Copilot" group in the
 * Cmd+K empty-query state).
 *
 * A built-in default (`query.builtin === true`) shows a Lock icon and
 * has no delete control — the five defaults are undeletable. A user
 * query shows a delete (✕) control that calls `onDelete`. Clicking the
 * chip body always calls `onSelect` to run/open the query.
 */
export interface SavedQueryChipProps {
  /** The saved query this chip represents. */
  query: SavedQuery
  /** Invoked when the user activates (clicks) the chip to run the query. */
  onSelect: (query: SavedQuery) => void
  /**
   * Invoked when the user deletes a *user* query. Ignored for built-in
   * defaults — the delete control is not rendered for them.
   */
  onDelete?: (query: SavedQuery) => void
}

export function SavedQueryChip({ query, onSelect, onDelete }: SavedQueryChipProps) {
  return (
    <span
      data-testid={`saved-query-chip-${query.id}`}
      data-builtin={query.builtin ? 'true' : 'false'}
      className="inline-flex items-center gap-1 rounded-full border border-slate-200 bg-slate-50 pl-2 pr-1 py-0.5 text-xs text-slate-700 dark:border-surface-700 dark:bg-surface-700 dark:text-slate-200"
    >
      <button
        type="button"
        data-testid={`saved-query-chip-run-${query.id}`}
        onClick={() => onSelect(query)}
        title={query.prompt}
        className="inline-flex items-center gap-1 font-medium hover:text-primary-600 dark:hover:text-primary-300 focus:outline-none focus:ring-2 focus:ring-primary-500 rounded"
      >
        {query.builtin && (
          <Lock
            data-testid={`saved-query-chip-lock-${query.id}`}
            className="h-3 w-3 flex-shrink-0 text-slate-400"
            aria-hidden="true"
          />
        )}
        <span className="truncate max-w-[14rem]">{query.label}</span>
      </button>
      {!query.builtin && (
        <button
          type="button"
          data-testid={`saved-query-chip-delete-${query.id}`}
          aria-label={`Delete saved query ${query.label}`}
          onClick={() => onDelete?.(query)}
          className="flex-shrink-0 rounded p-0.5 text-slate-400 hover:bg-slate-200 hover:text-slate-600 dark:hover:bg-surface-600 dark:hover:text-slate-200 focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          <X className="h-3 w-3" aria-hidden="true" />
        </button>
      )}
    </span>
  )
}

export default SavedQueryChip
