import { useEffect, useMemo, useRef, useState } from 'react'
import { Bookmark, Search, Sparkles } from 'lucide-react'
import type { ChatChunk } from '../api/copilot'
import { chat } from '../api/copilot'
import { StreamingNarrative } from './StreamingNarrative'
import { CitationList } from './CitationList'
import { SavedQueryChip } from './SavedQueryChip'
import type { Citation } from '../api/copilot'
import {
  deleteUserSavedQuery,
  loadSavedQueries,
  saveUserQuery,
} from '../api/savedQueries'
import type { SavedQuery } from '../api/savedQueries'

/**
 * Plan §7.1 — Cmd+K command palette.
 *
 * Surfaces tabs, sub-tabs, books, instruments, counterparties, and scenarios
 * behind a single keyboard-driven launcher. Each command is opaque to the
 * palette: callers supply an `id`, `group`, `label`, and `onActivate`
 * callback; the palette handles filtering, keyboard navigation, recent-item
 * persistence, and activation.
 *
 * Plan §5.6 — copilot mode. When `copilotMode` is enabled the palette
 * becomes dual-purpose: a query that matches a command still navigates,
 * but a free-form question that matches nothing is routed to the v2
 * copilot `chat()` endpoint and answered inline via <StreamingNarrative>
 * + <CitationList>, with a multi-turn follow-up textarea.
 */
export interface CommandItem {
  /** Stable identity used for recents and keying. */
  id: string
  /** Group heading. The palette renders sections in original group order. */
  group: string
  /** Visible primary text. The fuzzy filter scores against this. */
  label: string
  /** Optional secondary description shown to the right of the label. */
  description?: string
  /** Invoked when the user activates this command. */
  onActivate: () => void
}

interface CommandPaletteProps {
  open: boolean
  onClose: () => void
  items: CommandItem[]
  /**
   * Plan §5.6 — when `true`, the palette doubles as an inline copilot:
   * a free-form question that matches no command is routed to `chat()`
   * and answered below the command list. Defaults to `false`, in which
   * case the component renders and behaves exactly as before.
   */
  copilotMode?: boolean
  /**
   * Dependency-injection seam for the streaming `chat()` client. Tests
   * inject a fake that returns a canned `ReadableStream<ChatChunk>` so
   * the copilot zone can be exercised without a real network call.
   * Defaults to the real `chat` import.
   */
  chatFn?: typeof chat
  /**
   * Plan §8.3 — a saved query handed in from outside the palette (e.g. a
   * built-in chip clicked in the notification inbox). When this changes
   * to a non-null value while the palette is open, its prompt is routed
   * to the copilot immediately. The parent clears it after the palette
   * closes. Ignored when `copilotMode` is `false`.
   */
  pendingSavedQuery?: SavedQuery | null
}

const RECENT_KEY = 'kinetix:command-palette:recent'
const RECENT_MAX = 5

/**
 * Tiny dependency-free fuzzy matcher.
 *
 * - 100 for case-insensitive substring match
 * - 50 for ordered subsequence ("mch" → "MARKET_CRASH")
 * - 0 otherwise
 *
 * Substring is preferred over subsequence so that natural typing
 * ("tra" → Trades) outranks coincidental subsequences.
 */
function fuzzyScore(query: string, label: string): number {
  if (query.length === 0) return 1
  const q = query.toLowerCase()
  const l = label.toLowerCase()
  if (l.includes(q)) return 100
  // Ordered subsequence
  let i = 0
  for (let j = 0; j < l.length && i < q.length; j++) {
    if (l[j] === q[i]) i++
  }
  return i === q.length ? 50 : 0
}

function loadRecent(): string[] {
  try {
    const raw = window.localStorage.getItem(RECENT_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      return parsed.filter((x): x is string => typeof x === 'string').slice(0, RECENT_MAX)
    }
    return []
  } catch {
    return []
  }
}

function saveRecent(id: string): void {
  try {
    const current = loadRecent()
    const next = [id, ...current.filter((existing) => existing !== id)].slice(0, RECENT_MAX)
    window.localStorage.setItem(RECENT_KEY, JSON.stringify(next))
  } catch {
    // localStorage may be unavailable (private mode, quota). Swallow — recents
    // are a nice-to-have, not a functional requirement.
  }
}

interface ScoredItem extends CommandItem {
  score: number
}

export function CommandPalette({
  open,
  onClose,
  items,
  copilotMode = false,
  chatFn = chat,
  pendingSavedQuery = null,
}: CommandPaletteProps) {
  const [query, setQuery] = useState('')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement | null>(null)
  // Recents are loaded once on open and re-read after each activation so the
  // displayed "Recent" group stays in sync with the persisted list.
  const [recentIds, setRecentIds] = useState<string[]>(() => loadRecent())

  // Copilot zone state (§5.6). `copilotStream` is the live token stream
  // currently being rendered; null until the user asks something. Each
  // ask replaces it with a fresh stream so <StreamingNarrative> remounts
  // and resets cleanly. `copilotAnswered` flips true once the first
  // stream has completed — it gates the follow-up textarea. `followup`
  // holds the multi-turn textarea's draft text.
  const [copilotStream, setCopilotStream] = useState<ReadableStream<ChatChunk> | null>(null)
  const [copilotCitations, setCopilotCitations] = useState<Citation[]>([])
  const [copilotAnswered, setCopilotAnswered] = useState(false)
  // `copilotMode'` here is the *completion* mode reported by the terminal
  // chunk (`live` | `canned`), distinct from the `copilotMode` prop. It
  // gates the demo-mode badge — a canned (offline) answer is flagged so
  // the operator knows the response did not come from a live model.
  const [copilotCompletionMode, setCopilotCompletionMode] = useState<
    'live' | 'canned' | null
  >(null)
  const [followup, setFollowup] = useState('')

  // Saved copilot queries (§8.3). The "Copilot" group in the empty-query
  // state renders these as chips; saving a free-form query from the
  // palette refreshes the list. `savedQueryNotice` surfaces a transient
  // message — e.g. when the twelve-query limit is hit.
  const [savedQueries, setSavedQueries] = useState<SavedQuery[]>(() =>
    loadSavedQueries(),
  )
  const [savedQueryNotice, setSavedQueryNotice] = useState<string | null>(null)

  // Reset state every time the palette opens. The DOM is mounted by the time
  // this effect fires, so focus can move synchronously.
  //
  // The copilot stream is also torn down when the palette *closes*. The
  // component itself stays mounted (it renders `null` while closed), so
  // `copilotStream` state would otherwise survive a close/reopen cycle —
  // and the stale, already-consumed `ReadableStream` would be handed to
  // a freshly-mounted <StreamingNarrative> on the next open's first
  // render (before this effect re-runs), throwing "stream is locked".
  // Clearing it on close guarantees the reopen render starts clean.
  useEffect(() => {
    if (open) {
      setQuery('')
      setSelectedIndex(0)
      setRecentIds(loadRecent())
      setCopilotStream(null)
      setCopilotCitations([])
      setCopilotAnswered(false)
      setCopilotCompletionMode(null)
      setFollowup('')
      setSavedQueries(loadSavedQueries())
      setSavedQueryNotice(null)
      inputRef.current?.focus()
    } else {
      setCopilotStream(null)
      setCopilotCitations([])
      setCopilotAnswered(false)
      setCopilotCompletionMode(null)
      setFollowup('')
    }
  }, [open])

  // Build the filtered + grouped command list.
  const { groupedFiltered, flatFiltered, recentItems } = useMemo(() => {
    const filtered: ScoredItem[] = []
    for (const item of items) {
      const score = fuzzyScore(query, item.label)
      if (score > 0) filtered.push({ ...item, score })
    }
    // Stable sort by score desc; ties preserve original order.
    filtered.sort((a, b) => b.score - a.score)

    // Preserve original group order — group by first-seen group name.
    const groupOrder: string[] = []
    const byGroup = new Map<string, ScoredItem[]>()
    for (const item of filtered) {
      if (!byGroup.has(item.group)) {
        groupOrder.push(item.group)
        byGroup.set(item.group, [])
      }
      byGroup.get(item.group)!.push(item)
    }

    // Recents only show when the query is empty.
    const recentList: CommandItem[] =
      query.trim().length === 0
        ? recentIds
            .map((id) => items.find((it) => it.id === id))
            .filter((it): it is CommandItem => it !== undefined)
        : []

    // The flat list for keyboard navigation must put Recent first
    // (when shown) so ArrowDown / Enter from index 0 starts at the
    // first Recent item.
    const flat: CommandItem[] = [...recentList]
    for (const groupName of groupOrder) {
      flat.push(...(byGroup.get(groupName) ?? []))
    }

    return {
      groupedFiltered: groupOrder.map((g) => ({ name: g, items: byGroup.get(g)! })),
      flatFiltered: flat,
      recentItems: recentList,
    }
  }, [items, query, recentIds])

  // Clamp the selected index whenever filtered results change.
  useEffect(() => {
    if (selectedIndex >= flatFiltered.length) {
      setSelectedIndex(0)
    }
  }, [flatFiltered.length, selectedIndex])

  // §8.3 — when a saved query is handed in from outside (a built-in chip
  // clicked in the notification inbox) route its prompt to the copilot
  // as soon as the palette is open in copilot mode.
  useEffect(() => {
    if (!open || !copilotMode || !pendingSavedQuery) return
    const prompt = pendingSavedQuery.prompt.trim()
    if (prompt.length === 0) return
    setCopilotCitations([])
    setCopilotAnswered(false)
    setCopilotCompletionMode(null)
    setCopilotStream(chatFn({ message: prompt, page_context: {} }))
    // chatFn is stable across renders (a prop default or a test fake);
    // re-running only when the pending query itself changes is correct.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, copilotMode, pendingSavedQuery])

  if (!open) return null

  const activate = (item: CommandItem) => {
    saveRecent(item.id)
    setRecentIds(loadRecent())
    item.onActivate()
    onClose()
  }

  /**
   * Fire a copilot `chat()` call and route its token stream into the
   * copilot zone.
   *
   * conversation_id threading: <StreamingNarrative> owns stream
   * consumption and only surfaces an `onComplete` summary — it does not
   * expose the terminal frame's `conversation_id`. For §5.6 we keep it
   * simple: every ask (initial and follow-up) starts a fresh, stateless
   * `chat()` with no `conversation_id`. The follow-up textarea is still
   * a real multi-turn UX affordance; true server-side conversation
   * threading is deferred to a follow-up checkbox.
   */
  const askCopilot = (message: string) => {
    const trimmed = message.trim()
    if (trimmed.length === 0) return
    setCopilotCitations([])
    setCopilotAnswered(false)
    setCopilotCompletionMode(null)
    setCopilotStream(chatFn({ message: trimmed, page_context: {} }))
  }

  /**
   * Run a saved query (§8.3) — route its stored prompt to the copilot,
   * exactly as a free-form ask would.
   */
  const runSavedQuery = (savedQuery: SavedQuery) => {
    askCopilot(savedQuery.prompt)
  }

  /**
   * Save the current free-form query as a new user saved query. Refreshes
   * the chip list on success; surfaces a notice when the twelve-query cap
   * blocks the save or the query is blank.
   */
  const handleSaveCurrentQuery = () => {
    const result = saveUserQuery(query)
    if (result.ok) {
      setSavedQueries(loadSavedQueries())
      setSavedQueryNotice('Saved.')
    } else if (result.reason === 'limit') {
      setSavedQueryNotice('Saved-query limit reached (12). Delete one first.')
    } else {
      setSavedQueryNotice('Type a query to save.')
    }
  }

  /** Delete a user saved query and refresh the chip list. */
  const handleDeleteSavedQuery = (savedQuery: SavedQuery) => {
    deleteUserSavedQuery(savedQuery.id)
    setSavedQueries(loadSavedQueries())
    setSavedQueryNotice(null)
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setSelectedIndex((i) => Math.min(i + 1, Math.max(flatFiltered.length - 1, 0)))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setSelectedIndex((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const target = flatFiltered[selectedIndex]
      if (target) {
        // A matching command always wins — typing "Trades" + Enter still
        // navigates, copilot mode or not.
        activate(target)
      } else if (copilotMode && flatFiltered.length === 0 && query.trim().length > 0) {
        // No command matched and the query is non-empty: route the
        // free-form question to the copilot instead of doing nothing.
        askCopilot(query)
      }
    } else if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    }
  }

  /**
   * Follow-up textarea key handling: Enter sends, Shift+Enter inserts a
   * newline (the browser's default for a textarea, so we simply do not
   * intercept it).
   */
  const handleFollowupKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (followup.trim().length === 0) return
      askCopilot(followup)
      setFollowup('')
    }
  }

  let runningIndex = 0
  const showRecent = recentItems.length > 0
  // The "Copilot" group of saved-query chips shows only in copilot mode
  // while the query input is empty (§8.3 — the Cmd+K empty-query state).
  const showSavedQueries = copilotMode && query.trim().length === 0
  // The save-current-query affordance appears in copilot mode once the
  // user has typed something — it captures the free-form draft.
  const showSaveAffordance = copilotMode && query.trim().length > 0

  return (
    <>
      <div
        data-testid="command-palette-backdrop"
        className="fixed inset-0 z-40 bg-black/40"
        onClick={onClose}
      />
      <div
        data-testid="command-palette"
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        className="fixed left-1/2 top-[20%] z-50 -translate-x-1/2 w-[min(560px,92vw)] max-h-[70vh] flex flex-col bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-700 rounded-lg shadow-2xl focus:outline-none"
      >
        <div className="flex items-center gap-2 px-3 py-2.5 border-b border-slate-200 dark:border-surface-700">
          <Search className="h-4 w-4 text-slate-400" aria-hidden="true" />
          <input
            ref={inputRef}
            data-testid="command-palette-input"
            type="text"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setSelectedIndex(0)
            }}
            onKeyDown={handleKeyDown}
            placeholder={
              copilotMode
                ? 'Ask the copilot, or type to jump to a tab...'
                : 'Type a tab, book, instrument, counterparty, or scenario...'
            }
            aria-label="Command palette search"
            className="flex-1 bg-transparent text-sm text-slate-800 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:outline-none"
          />
          {showSaveAffordance && (
            <button
              type="button"
              data-testid="command-palette-save-query"
              onClick={handleSaveCurrentQuery}
              aria-label="Save this query"
              title="Save this query"
              className="inline-flex items-center gap-1 rounded border border-slate-300 px-1.5 py-0.5 text-[10px] font-medium text-slate-500 hover:bg-slate-100 hover:text-slate-700 dark:border-surface-600 dark:text-slate-400 dark:hover:bg-surface-700 dark:hover:text-slate-200"
            >
              <Bookmark className="h-3 w-3" aria-hidden="true" />
              Save
            </button>
          )}
          <kbd className="inline-block px-1.5 py-0.5 text-[10px] font-mono font-medium text-slate-500 dark:text-slate-400 bg-slate-100 dark:bg-surface-900 border border-slate-300 dark:border-surface-600 rounded">
            Esc
          </kbd>
        </div>
        {savedQueryNotice && (
          <div
            data-testid="command-palette-saved-query-notice"
            className="px-3 py-1 text-[10px] text-slate-500 dark:text-slate-400 border-b border-slate-100 dark:border-surface-700"
          >
            {savedQueryNotice}
          </div>
        )}
        <div
          data-testid="command-palette-list"
          className="flex-1 overflow-y-auto py-1"
          role="listbox"
          aria-label="Command palette results"
        >
          {flatFiltered.length === 0 && (
            <div
              data-testid="command-palette-empty"
              className="px-4 py-6 text-sm text-center text-slate-500 dark:text-slate-400"
            >
              No matches.
            </div>
          )}

          {showRecent && (
            <div data-testid="command-palette-group-Recent">
              <div className="px-3 pt-2 pb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                Recent
              </div>
              <ul>
                {recentItems.map((item) => {
                  const index = runningIndex++
                  const selected = index === selectedIndex
                  return (
                    <li key={`recent-${item.id}`}>
                      <button
                        data-testid={`command-palette-item-recent-${item.id}`}
                        data-selected={selected ? 'true' : 'false'}
                        type="button"
                        onClick={() => activate(item)}
                        onMouseEnter={() => setSelectedIndex(index)}
                        className={`w-full flex items-center justify-between gap-3 px-3 py-1.5 text-sm text-left ${
                          selected
                            ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300'
                            : 'text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-surface-700'
                        }`}
                      >
                        <span className="truncate">{item.label}</span>
                        <span className="text-[10px] text-slate-400 dark:text-slate-500 flex-shrink-0">
                          {item.group}
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            </div>
          )}

          {showSavedQueries && (
            <div data-testid="command-palette-group-Copilot">
              <div className="px-3 pt-2 pb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                Copilot
              </div>
              <div
                data-testid="command-palette-saved-queries"
                className="flex flex-wrap gap-1.5 px-3 pb-2"
              >
                {savedQueries.map((savedQuery) => (
                  <SavedQueryChip
                    key={savedQuery.id}
                    query={savedQuery}
                    onSelect={runSavedQuery}
                    onDelete={handleDeleteSavedQuery}
                  />
                ))}
              </div>
            </div>
          )}

          {groupedFiltered.map((group) => (
            <div
              key={group.name}
              data-testid={`command-palette-group-${group.name}`}
            >
              <div className="px-3 pt-2 pb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
                {group.name}
              </div>
              <ul>
                {group.items.map((item) => {
                  const index = runningIndex++
                  const selected = index === selectedIndex
                  return (
                    <li key={item.id}>
                      <button
                        data-testid={`command-palette-item-${item.id}`}
                        data-selected={selected ? 'true' : 'false'}
                        type="button"
                        onClick={() => activate(item)}
                        onMouseEnter={() => setSelectedIndex(index)}
                        className={`w-full flex items-center justify-between gap-3 px-3 py-1.5 text-sm text-left ${
                          selected
                            ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300'
                            : 'text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-surface-700'
                        }`}
                      >
                        <span className="truncate">{item.label}</span>
                        {item.description && (
                          <span className="text-[10px] text-slate-400 dark:text-slate-500 flex-shrink-0 truncate">
                            {item.description}
                          </span>
                        )}
                      </button>
                    </li>
                  )
                })}
              </ul>
            </div>
          ))}
        </div>

        {copilotMode && (
          <div
            data-testid="command-palette-copilot-zone"
            className="border-t border-slate-200 dark:border-surface-700 px-3 py-3 max-h-[40vh] overflow-y-auto"
          >
            {!copilotStream && (
              <div
                data-testid="command-palette-copilot-hint"
                className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500"
              >
                <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />
                Press Enter to ask the copilot.
              </div>
            )}

            {copilotStream && (
              <div data-testid="command-palette-copilot-response">
                <StreamingNarrative
                  stream={copilotStream}
                  ariaLabel="Copilot answer"
                  onComplete={({ citations, mode }) => {
                    setCopilotCitations(citations)
                    setCopilotAnswered(true)
                    setCopilotCompletionMode(mode)
                  }}
                />
                {copilotCompletionMode === 'canned' && (
                  <span
                    data-testid="command-palette-copilot-demo-badge"
                    className="mt-2 inline-block rounded bg-amber-100 dark:bg-amber-900/30 px-1.5 py-0.5 text-[10px] font-medium text-amber-800 dark:text-amber-300"
                  >
                    Demo mode
                  </span>
                )}
                {copilotCitations.length > 0 && (
                  <div data-testid="command-palette-copilot-citations">
                    <CitationList citations={copilotCitations} />
                  </div>
                )}
              </div>
            )}

            {copilotAnswered && (
              <textarea
                data-testid="command-palette-copilot-followup"
                value={followup}
                onChange={(e) => setFollowup(e.target.value)}
                onKeyDown={handleFollowupKeyDown}
                rows={2}
                placeholder="Ask a follow-up — Enter to send, Shift+Enter for a newline"
                aria-label="Copilot follow-up question"
                className="mt-3 w-full resize-none rounded border border-slate-200 dark:border-surface-700 bg-transparent px-2 py-1.5 text-sm text-slate-800 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:outline-none focus:ring-2 focus:ring-primary-500"
              />
            )}
          </div>
        )}
      </div>
    </>
  )
}
