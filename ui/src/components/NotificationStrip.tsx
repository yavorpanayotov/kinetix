import { useEffect, useMemo, useRef, useState } from 'react'
import { Bell, ChevronDown, ChevronUp, X } from 'lucide-react'
import { formatRelativeTime } from '../utils/format'
import type { MorningBrief } from '../api/brief'
import type { CopilotPushEvent } from '../api/copilot'
import {
  CHIP_CLASS,
  DOT_CLASS,
  SEVERITY_ORDER,
  pushSeverity,
} from '../utils/notificationSeverity'
import type { NotificationSeverity } from '../utils/notificationSeverity'
import { MorningBriefCard } from './MorningBriefCard'
import { IntradayPushItem } from './IntradayPushItem'

// Re-export the severity vocabulary so existing importers of
// `./NotificationStrip` keep working (this is a type-only re-export, so
// it does not trip the react-refresh "components only" rule).
export type { NotificationSeverity }

/**
 * Plan §6.9 — copilot notification strip.
 *
 * A 36px collapsed bar that summarises current notifications (severity chips
 * + unread count) and expands into a scrollable inbox. It sits between
 * <SystemStatusBanner> and <RiskTickerStrip> in the App layout, but 6.9
 * ships only the self-contained presentational component + tests; the
 * App.tsx placement is a later concern.
 *
 * Later checkboxes hang more off this strip: the morning brief
 * (<MorningBriefCard>, 6.10) and intraday push items (PR 7). For 6.9 it is
 * driven entirely by props — the caller owns the notification list and the
 * strip only filters out items the user has dismissed.
 *
 * The severity vocabulary (`NotificationSeverity`, the chip/dot colour
 * maps) lives in `../utils/notificationSeverity` so the intraday-push
 * row (<IntradayPushItem>, §7.9) shares the exact same mapping.
 */
export interface NotificationItem {
  id: string
  severity: NotificationSeverity
  title: string
  body?: string
  timestamp: string // ISO 8601
  /** Optional source label, e.g. "Morning brief" or "Intraday alert". */
  source?: string
}

export interface NotificationStripProps {
  /** All current notifications (caller-owned; the strip filters out dismissed ones). */
  items: NotificationItem[]
  /** Render an error state instead of the normal bar. */
  error?: string | null
  /** Optional controlled-expansion. When omitted, the strip owns its own open/closed state. */
  expanded?: boolean
  onExpandedChange?: (expanded: boolean) => void
  /**
   * Optional morning brief (plan §6.10). When present it renders at the
   * top of the inbox and — on the first inbox open of the trading day —
   * auto-expands the strip. A brief alone counts as content: the strip
   * is expandable and non-empty even with zero notification items.
   */
  morningBrief?: MorningBrief | null
  /**
   * Intraday Copilot push events (plan §7.9 / PR 7), as surfaced by
   * `useCopilotWebSocket()` — newest first. They render as `Zap`-marked
   * inbox rows below the morning brief; more than five collapse the
   * excess into a single "N more" badge. Like a brief, the presence of
   * pushes alone makes the strip non-empty and expandable; their
   * severities count toward the collapsed-bar chips. Defaults to an
   * empty list.
   */
  intradayPushes?: CopilotPushEvent[]
}

const DISMISSED_KEY = 'kinetix:copilot-inbox:dismissed'
/** localStorage key tracking the last UTC date the brief was auto-shown. */
const BRIEF_SEEN_KEY = 'kinetix:morning-brief:last-seen-date'
const INBOX_ID = 'notification-inbox-panel'

/** Today's date as a UTC `YYYY-MM-DD` string. */
function todayUtcDate(): string {
  return new Date().toISOString().slice(0, 10)
}

/**
 * Read the last date the morning brief was auto-shown. Returns null on
 * a missing value or any localStorage error — mirrors the defensive
 * `loadDismissed` idiom below.
 */
function loadBriefSeenDate(): string | null {
  try {
    return window.localStorage.getItem(BRIEF_SEEN_KEY)
  } catch {
    return null
  }
}

/** Persist today's date as the last brief-seen date. Swallows errors. */
function saveBriefSeenDate(date: string): void {
  try {
    window.localStorage.setItem(BRIEF_SEEN_KEY, date)
  } catch {
    // localStorage unavailable — swallow.
  }
}

/**
 * Maximum number of intraday-push rows rendered before the rest collapse
 * into a single "N more" overflow badge (plan §7.9).
 */
const MAX_INTRADAY_PUSH_ROWS = 5

/**
 * Read the dismissed-id set from localStorage. Tolerates a missing or
 * malformed value by returning an empty set — mirrors the defensive
 * `loadRecent` idiom in <CommandPalette>.
 */
function loadDismissed(): Set<string> {
  try {
    const raw = window.localStorage.getItem(DISMISSED_KEY)
    if (!raw) return new Set()
    const parsed: unknown = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      return new Set(parsed.filter((x): x is string => typeof x === 'string'))
    }
    return new Set()
  } catch {
    return new Set()
  }
}

/**
 * Persist the dismissed-id set. Swallows errors — localStorage may be
 * unavailable in private mode or over quota, and dismissal persistence is
 * a nice-to-have, not a functional requirement.
 */
function saveDismissed(ids: Set<string>): void {
  try {
    window.localStorage.setItem(DISMISSED_KEY, JSON.stringify([...ids]))
  } catch {
    // localStorage unavailable — swallow.
  }
}

export interface NotificationInboxProps {
  items: NotificationItem[] // already-filtered visible items
  onDismiss: (id: string) => void
  onDismissAll: () => void
  /**
   * Optional morning brief (plan §6.10). When present it renders at the
   * top of the inbox, above the notification rows, and counts as
   * content — the "All caught up" empty state only shows when there are
   * no items AND no brief. The `briefRef` is forwarded so the parent
   * strip can scroll the brief into view after an auto-expand.
   */
  morningBrief?: MorningBrief | null
  briefRef?: React.RefObject<HTMLDivElement | null>
  /**
   * Intraday Copilot push events (plan §7.9 / §7.10). Rendered below the
   * morning brief, above the notification rows; more than five collapse
   * into a single "N more" badge. Like a brief, their presence keeps the
   * inbox non-empty. Defaults to an empty list.
   */
  intradayPushes?: CopilotPushEvent[]
  /** Dismiss a single intraday push, keyed by its ``session_id``. */
  onDismissPush?: (sessionId: string) => void
}

export function NotificationInbox({
  items,
  onDismiss,
  onDismissAll,
  morningBrief = null,
  briefRef,
  intradayPushes = [],
  onDismissPush = () => {},
}: NotificationInboxProps) {
  // More than five pushes: render the first five and collapse the rest
  // into a single "N more" badge (plan §7.9).
  const visiblePushes = intradayPushes.slice(0, MAX_INTRADAY_PUSH_ROWS)
  const pushOverflow = intradayPushes.length - visiblePushes.length

  return (
    <div
      id={INBOX_ID}
      data-testid="notification-inbox"
      className="max-h-80 overflow-y-auto border-b border-slate-200 dark:border-surface-700 bg-white dark:bg-surface-800"
    >
      {morningBrief && (
        <div data-testid="notification-inbox-brief" ref={briefRef}>
          <MorningBriefCard brief={morningBrief} />
        </div>
      )}
      {intradayPushes.length > 0 && (
        <ul data-testid="intraday-push-list">
          {visiblePushes.map((push) => (
            <IntradayPushItem
              key={push.session_id}
              push={push}
              onDismiss={onDismissPush}
            />
          ))}
          {pushOverflow > 0 && (
            <li className="flex justify-center px-3 py-1.5 border-b border-slate-100 dark:border-surface-700">
              <span
                data-testid="intraday-push-overflow"
                className="inline-flex items-center rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-600 dark:bg-surface-700 dark:text-slate-300"
              >
                {pushOverflow} more
              </span>
            </li>
          )}
        </ul>
      )}
      {items.length === 0 ? (
        // "All caught up" only when there's nothing at all — a brief or an
        // intraday push alone is enough content to keep the inbox non-empty.
        morningBrief || intradayPushes.length > 0 ? null : (
          <div
            data-testid="notification-inbox-empty"
            className="px-4 py-6 text-sm text-center text-slate-400 dark:text-slate-500"
          >
            All caught up.
          </div>
        )
      ) : (
        <>
          <div className="flex items-center justify-end px-3 py-1.5 border-b border-slate-100 dark:border-surface-700">
            <button
              type="button"
              data-testid="notification-dismiss-all"
              onClick={onDismissAll}
              className="text-xs font-medium text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
            >
              Dismiss all
            </button>
          </div>
          <ul>
            {items.map((item) => (
              <li
                key={item.id}
                data-testid={`notification-item-${item.id}`}
                className="flex items-start gap-2.5 px-3 py-2 border-b border-slate-100 dark:border-surface-700 last:border-b-0"
              >
                <span
                  className={`mt-1 h-2 w-2 flex-shrink-0 rounded-full ${DOT_CLASS[item.severity]}`}
                  aria-hidden="true"
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
                      {item.title}
                    </span>
                    <span className="flex-shrink-0 text-[10px] font-mono text-slate-400 dark:text-slate-500">
                      {formatRelativeTime(item.timestamp)}
                    </span>
                  </div>
                  {item.body && (
                    <p className="mt-0.5 text-xs text-slate-600 dark:text-slate-300">
                      {item.body}
                    </p>
                  )}
                  {item.source && (
                    <span className="mt-0.5 inline-block text-[10px] uppercase tracking-wide text-slate-400 dark:text-slate-500">
                      {item.source}
                    </span>
                  )}
                </div>
                <button
                  type="button"
                  data-testid={`notification-dismiss-${item.id}`}
                  aria-label="Dismiss notification"
                  onClick={() => onDismiss(item.id)}
                  className="flex-shrink-0 rounded p-0.5 text-slate-400 hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-surface-700 dark:hover:text-slate-200"
                >
                  <X className="h-3.5 w-3.5" aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

export function NotificationStrip({
  items,
  error = null,
  expanded,
  onExpandedChange,
  morningBrief = null,
  intradayPushes = [],
}: NotificationStripProps) {
  const [dismissed, setDismissed] = useState<Set<string>>(() => loadDismissed())
  // Dismissed intraday-push session ids. Kept separate from the
  // notification-item `dismissed` set: pushes are a transient WebSocket
  // stream, so their dismissals are session-scoped (not persisted to
  // localStorage) and never collide with caller-owned item ids.
  const [dismissedPushes, setDismissedPushes] = useState<Set<string>>(
    () => new Set(),
  )
  const [internalExpanded, setInternalExpanded] = useState(false)
  const briefRef = useRef<HTMLDivElement | null>(null)
  // Guards the once-per-mount auto-expand so a brief that lands
  // asynchronously (App.tsx fetches it after first paint) still triggers
  // exactly one auto-expand and never re-triggers on later re-renders.
  const autoExpandDoneRef = useRef(false)

  // Controlled when `expanded` is supplied; uncontrolled otherwise.
  const isControlled = expanded !== undefined
  const isExpanded = isControlled ? expanded : internalExpanded

  const visible = useMemo(
    () => items.filter((item) => !dismissed.has(item.id)),
    [items, dismissed],
  )

  // Intraday pushes with the locally-dismissed ones filtered out.
  const visiblePushes = useMemo(
    () => intradayPushes.filter((push) => !dismissedPushes.has(push.session_id)),
    [intradayPushes, dismissedPushes],
  )

  const setExpanded = (next: boolean) => {
    if (!isControlled) setInternalExpanded(next)
    onExpandedChange?.(next)
  }

  // Auto-expand on the first inbox open of the trading day. When a
  // morning brief is present and `BRIEF_SEEN_KEY` is not today's date,
  // open the strip, stamp today's date, and scroll the brief into view.
  // Subsequent mounts the same day are no-ops. Controlled-expansion
  // callers own their own state, so the auto-expand is skipped for them.
  //
  // The effect depends on `morningBrief` because callers (App.tsx) fetch
  // the brief asynchronously — it is null on first paint and arrives
  // later. `autoExpandDoneRef` clamps this to exactly one auto-expand
  // for the component's lifetime regardless of how often it re-renders.
  useEffect(() => {
    if (autoExpandDoneRef.current) return
    if (!morningBrief || isControlled) return
    autoExpandDoneRef.current = true
    if (loadBriefSeenDate() === todayUtcDate()) return
    saveBriefSeenDate(todayUtcDate())
    setInternalExpanded(true)
    onExpandedChange?.(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [morningBrief])

  // After an auto-expand, scroll the brief wrapper into view. Guarded
  // for jsdom, where `scrollIntoView` is not implemented.
  useEffect(() => {
    if (!isExpanded || !morningBrief) return
    const el = briefRef.current
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ block: 'nearest' })
    }
  }, [isExpanded, morningBrief])

  const handleDismiss = (id: string) => {
    setDismissed((prev) => {
      const next = new Set(prev)
      next.add(id)
      saveDismissed(next)
      return next
    })
  }

  const handleDismissAll = () => {
    setDismissed((prev) => {
      const next = new Set(prev)
      for (const item of visible) next.add(item.id)
      saveDismissed(next)
      return next
    })
  }

  const handleDismissPush = (sessionId: string) => {
    setDismissedPushes((prev) => {
      const next = new Set(prev)
      next.add(sessionId)
      return next
    })
  }

  // Error takes precedence over both the empty and populated bars. The
  // `notification-strip` element is itself the 36px bar (h-9) — the bar IS
  // the strip's identity.
  if (error) {
    return (
      <div
        role="region"
        aria-label="Notifications"
        data-testid="notification-strip"
        data-expanded="false"
        className="h-9"
      >
        <div
          data-testid="notification-strip-error"
          role="alert"
          className="flex h-9 items-center gap-2 px-4 text-sm font-medium bg-red-50 dark:bg-red-900/20 border-b border-red-200 dark:border-red-800 text-red-700 dark:text-red-300"
        >
          <Bell className="h-3.5 w-3.5 flex-shrink-0" aria-hidden="true" />
          <span>{error}</span>
        </div>
      </div>
    )
  }

  // Empty state — a muted bar. It is not expandable via the toggle (no
  // toggle is rendered), but if the strip is *already* expanded — e.g. the
  // last visible item was just dismissed from the open inbox — the inbox
  // stays mounted so the "All caught up" message is visible.
  //
  // A morning brief or an intraday push counts as content: when either is
  // present the strip takes the populated path below (expandable,
  // non-empty bar) even with zero notification items.
  if (visible.length === 0 && !morningBrief && visiblePushes.length === 0) {
    return (
      <div
        role="region"
        aria-label="Notifications"
        data-testid="notification-strip"
        data-expanded={isExpanded ? 'true' : 'false'}
        className={isExpanded ? undefined : 'h-9'}
      >
        <div
          data-testid="notification-strip-empty"
          className="flex h-9 items-center gap-2 px-4 text-xs bg-slate-50 dark:bg-surface-800 border-b border-slate-200 dark:border-surface-700 text-slate-400 dark:text-slate-500"
        >
          <Bell className="h-3.5 w-3.5 flex-shrink-0" aria-hidden="true" />
          <span>No notifications</span>
        </div>
        {isExpanded && (
          <NotificationInbox
            items={visible}
            onDismiss={handleDismiss}
            onDismissAll={handleDismissAll}
          />
        )}
      </div>
    )
  }

  // Severity counts for the chips — render a chip only for severities
  // present. Intraday pushes (plan §7.9) count alongside notification
  // items so the collapsed bar reflects the full severity picture; their
  // free-form wire severity is narrowed via `pushSeverity`.
  const severityCounts: Record<NotificationSeverity, number> = {
    critical: 0,
    warning: 0,
    info: 0,
  }
  for (const item of visible) severityCounts[item.severity] += 1
  for (const push of visiblePushes) severityCounts[pushSeverity(push.severity)] += 1

  return (
    <div
      role="region"
      aria-label="Notifications"
      data-testid="notification-strip"
      data-expanded={isExpanded ? 'true' : 'false'}
      className={isExpanded ? undefined : 'h-9'}
    >
      <div
        className="flex h-9 items-center gap-3 px-4 text-sm bg-slate-50 dark:bg-surface-800 border-b border-slate-200 dark:border-surface-700"
        onClick={() => setExpanded(!isExpanded)}
        role="presentation"
      >
        <Bell className="h-3.5 w-3.5 flex-shrink-0 text-slate-400" aria-hidden="true" />

        <div className="flex items-center gap-1.5">
          {SEVERITY_ORDER.filter((s) => severityCounts[s] > 0).map((severity) => (
            <span
              key={severity}
              data-testid={`notification-chip-${severity}`}
              className={`inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-semibold ${CHIP_CLASS[severity]}`}
            >
              {severityCounts[severity]} {severity}
            </span>
          ))}
        </div>

        {/*
          Unread count is the notification-item count only — a morning
          brief is not an "unread notification", it is a standing daily
          digest, so it never inflates this number. With a brief but
          zero items the bar still reads "0 unread" and remains
          expandable (the brief is the content behind the toggle).
        */}
        <span
          data-testid="notification-unread-count"
          className="text-xs font-medium text-slate-600 dark:text-slate-300"
        >
          {visible.length} unread
        </span>

        <button
          type="button"
          data-testid="notification-strip-toggle"
          aria-expanded={isExpanded}
          aria-controls={INBOX_ID}
          aria-label={isExpanded ? 'Collapse notifications' : 'Expand notifications'}
          onClick={(e) => {
            // Stop the click bubbling to the bar's own toggle handler.
            e.stopPropagation()
            setExpanded(!isExpanded)
          }}
          className="ml-auto rounded p-0.5 text-slate-400 hover:bg-slate-200 hover:text-slate-600 dark:hover:bg-surface-700 dark:hover:text-slate-200"
        >
          {isExpanded ? (
            <ChevronUp className="h-4 w-4" aria-hidden="true" />
          ) : (
            <ChevronDown className="h-4 w-4" aria-hidden="true" />
          )}
        </button>
      </div>

      {isExpanded && (
        <NotificationInbox
          items={visible}
          onDismiss={handleDismiss}
          onDismissAll={handleDismissAll}
          morningBrief={morningBrief}
          briefRef={briefRef}
          intradayPushes={visiblePushes}
          onDismissPush={handleDismissPush}
        />
      )}
    </div>
  )
}
