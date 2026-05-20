/**
 * Plan §8.3 — saved copilot queries.
 *
 * A *saved query* is a named, reusable copilot prompt. Two kinds exist:
 *
 * - **Built-in defaults** — five undeletable templates (`builtin: true`)
 *   whose ids mirror the server-side JSON templates shipped under
 *   `ai-insights-service/src/kinetix_insights/queries/` (§8.1). They are
 *   rendered with a Lock icon and can never be removed.
 * - **User queries** — free-form prompts the operator saves from the
 *   Cmd+K palette. They live in `localStorage` (no server table — see the
 *   plan's "Decisions applied" section), capped at twelve.
 *
 * This module owns the data model and the `localStorage` persistence
 * layer; the `<SavedQueryChip>` component and its consumers
 * (`<NotificationInbox>`, `<CommandPalette>`) only render and dispatch.
 */

/** A copilot saved query — either a built-in default or a user query. */
export interface SavedQuery {
  /** Stable identity. Built-ins match the server template ids; user
   * queries get a generated `user-<timestamp>` id. */
  id: string
  /** Visible pill text. */
  label: string
  /** The free-form copilot prompt this query runs. */
  prompt: string
  /** `true` for the five undeletable built-in defaults. */
  builtin: boolean
}

/**
 * The five built-in saved-query defaults. The ids match the server-side
 * templates in `ai-insights-service/src/kinetix_insights/queries/`; the
 * prompts are the free-form copilot questions the chips run via `chat()`
 * (the `{book_id}`-templated server route is a separate, parameterised
 * path — these chips drive the conversational copilot directly).
 */
export const BUILTIN_SAVED_QUERIES: readonly SavedQuery[] = [
  {
    id: 'limit-breaches',
    label: 'Limit breaches today',
    prompt: 'Which risk limits are in breach today?',
    builtin: true,
  },
  {
    id: 'pnl-vs-yesterday',
    label: 'P&L vs yesterday',
    prompt: 'How does my P&L compare to yesterday?',
    builtin: true,
  },
  {
    id: 'var-week-drivers',
    label: 'VaR drivers this week',
    prompt: 'What drove my VaR change over the past week?',
    builtin: true,
  },
  {
    id: 'top-positions-risk-contribution',
    label: 'Top positions by risk contribution',
    prompt: 'Which positions contribute the most to portfolio risk?',
    builtin: true,
  },
  {
    id: 'vol-dislocations',
    label: 'Volatility dislocations',
    prompt: 'Where has implied volatility dislocated from its recent range?',
    builtin: true,
  },
]

/** localStorage key for user-saved copilot queries. */
export const SAVED_QUERIES_KEY = 'kinetix:copilot:saved-queries'

/** Maximum number of user-saved queries. Built-ins do not count. */
export const MAX_USER_SAVED_QUERIES = 12

/** A `localStorage`-persisted user query is always `builtin: false`. */
interface StoredUserQuery {
  id: string
  label: string
  prompt: string
}

/**
 * Read the user-saved queries from `localStorage`. Tolerates a missing
 * or malformed value by returning an empty list — mirrors the defensive
 * `loadRecent` / `loadDismissed` idioms elsewhere in the UI. The result
 * is always clamped to `MAX_USER_SAVED_QUERIES` so a tampered store can
 * never exceed the cap.
 */
export function loadUserSavedQueries(): SavedQuery[] {
  try {
    const raw = window.localStorage.getItem(SAVED_QUERIES_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    const valid: SavedQuery[] = []
    for (const entry of parsed) {
      if (
        entry &&
        typeof entry === 'object' &&
        typeof (entry as StoredUserQuery).id === 'string' &&
        typeof (entry as StoredUserQuery).label === 'string' &&
        typeof (entry as StoredUserQuery).prompt === 'string'
      ) {
        const e = entry as StoredUserQuery
        valid.push({ id: e.id, label: e.label, prompt: e.prompt, builtin: false })
      }
    }
    return valid.slice(0, MAX_USER_SAVED_QUERIES)
  } catch {
    return []
  }
}

/**
 * The full list shown to the user: the five built-in defaults first,
 * then the user-saved queries.
 */
export function loadSavedQueries(): SavedQuery[] {
  return [...BUILTIN_SAVED_QUERIES, ...loadUserSavedQueries()]
}

function persistUserQueries(queries: SavedQuery[]): void {
  try {
    const stored: StoredUserQuery[] = queries.map((q) => ({
      id: q.id,
      label: q.label,
      prompt: q.prompt,
    }))
    window.localStorage.setItem(SAVED_QUERIES_KEY, JSON.stringify(stored))
  } catch {
    // localStorage may be unavailable (private mode, quota). Swallow —
    // saved queries are a convenience, not a functional requirement.
  }
}

/** The outcome of a {@link saveUserQuery} call. */
export type SaveQueryResult =
  | { ok: true; query: SavedQuery }
  | { ok: false; reason: 'limit' | 'empty' }

/**
 * Save a free-form prompt as a new user query. The label defaults to
 * the prompt text. Returns `{ ok: false, reason: 'limit' }` when the
 * twelve-query cap is already reached, and `{ ok: false, reason:
 * 'empty' }` for a blank prompt — neither writes to `localStorage`.
 */
export function saveUserQuery(prompt: string, label?: string): SaveQueryResult {
  const trimmedPrompt = prompt.trim()
  if (trimmedPrompt.length === 0) return { ok: false, reason: 'empty' }

  const existing = loadUserSavedQueries()
  if (existing.length >= MAX_USER_SAVED_QUERIES) {
    return { ok: false, reason: 'limit' }
  }

  const trimmedLabel = label?.trim()
  const query: SavedQuery = {
    id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    label: trimmedLabel && trimmedLabel.length > 0 ? trimmedLabel : trimmedPrompt,
    prompt: trimmedPrompt,
    builtin: false,
  }
  persistUserQueries([...existing, query])
  return { ok: true, query }
}

/**
 * Delete a user-saved query by id. Built-in defaults are never present
 * in the persisted list, so calling this with a built-in id is a no-op
 * — the five defaults are undeletable by construction.
 */
export function deleteUserSavedQuery(id: string): void {
  const remaining = loadUserSavedQueries().filter((q) => q.id !== id)
  persistUserQueries(remaining)
}
