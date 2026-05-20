import { useCallback, useEffect, useState } from 'react'
import { Inbox, ShieldCheck, ShieldAlert, RefreshCw } from 'lucide-react'
import {
  fetchAuditEvents,
  verifyAuditChain,
  type AuditEventDto,
  type AuditEventQuery,
  type AuditVerifyResultDto,
} from '../api/audit'
import { Badge, Card, EmptyState, ErrorCard, Spinner } from './ui'

/** Number of events requested per cursor page. */
const PAGE_SIZE = 25

/**
 * Maps an audit event type onto a {@link Badge} colour variant. Trade-lifecycle
 * events stay neutral / informational; governance and breach events earn
 * warning / critical tints so the eye lands on them first.
 */
function eventTypeVariant(eventType: string): 'critical' | 'warning' | 'info' | 'success' | 'neutral' {
  const upper = eventType.toUpperCase()
  if (upper.includes('REJECT') || upper.includes('BREACH') || upper.includes('FAIL') || upper.includes('DELETE')) {
    return 'critical'
  }
  if (upper.includes('AMEND') || upper.includes('OVERRIDE') || upper.includes('CANCEL')) {
    return 'warning'
  }
  if (upper.includes('APPROV') || upper.includes('SUBMIT') || upper.includes('PROMOTE')) {
    return 'success'
  }
  if (upper.includes('TRADE') || upper.includes('BOOK') || upper.includes('CREATE')) {
    return 'info'
  }
  return 'neutral'
}

/** Renders an audit event's primary subject — its trade or governance target. */
function eventSubject(event: AuditEventDto): string {
  if (event.tradeId) return event.tradeId
  if (event.modelName) return event.modelName
  if (event.scenarioId) return event.scenarioId
  if (event.limitId) return event.limitId
  if (event.submissionId) return event.submissionId
  return '—'
}

interface AuditLogPanelProps {
  /**
   * Initial filter values — used by cross-screen jumps (e.g. "view audit
   * trail for this book"). The user can still edit or clear each field.
   */
  initialBookId?: string
  initialTradeId?: string
}

/**
 * A paginated, filterable view of the hash-chained audit trail.
 *
 * Filters (book / trade / event-type / time-window) are forwarded to the
 * gateway audit proxy. Pagination is cursor-based: each "Load more" request
 * carries the `id` of the last loaded event as `afterId`. A chain-integrity
 * indicator surfaces the result of `verifyAuditChain()` so users can see at a
 * glance whether the trail has been tampered with.
 *
 * Plan ref: plans/audit-v2.md PR 8 §8.2.
 */
export function AuditLogPanel({ initialBookId = '', initialTradeId = '' }: AuditLogPanelProps = {}) {
  const [bookId, setBookId] = useState(initialBookId)
  const [tradeId, setTradeId] = useState(initialTradeId)
  const [eventType, setEventType] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  const [events, setEvents] = useState<AuditEventDto[]>([])
  const [loading, setLoading] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(false)

  const [verification, setVerification] = useState<AuditVerifyResultDto | null>(null)
  const [verifying, setVerifying] = useState(false)
  const [verifyError, setVerifyError] = useState<string | null>(null)

  /** Builds the active filter query, omitting blank fields. */
  const buildQuery = useCallback((): AuditEventQuery => {
    const query: AuditEventQuery = { limit: PAGE_SIZE }
    if (bookId.trim()) query.bookId = bookId.trim()
    if (tradeId.trim()) query.tradeId = tradeId.trim()
    if (eventType.trim()) query.eventType = eventType.trim()
    if (from.trim()) query.from = new Date(from).toISOString()
    if (to.trim()) query.to = new Date(to).toISOString()
    return query
  }, [bookId, tradeId, eventType, from, to])

  /** Loads the first cursor page for the current filters. */
  const load = useCallback(
    async (signal?: { cancelled: boolean }) => {
      setLoading(true)
      setError(null)
      try {
        const page = await fetchAuditEvents(buildQuery())
        if (signal?.cancelled) return
        setEvents(page)
        setHasMore(page.length === PAGE_SIZE)
      } catch (err) {
        if (signal?.cancelled) return
        setError(err instanceof Error ? err.message : String(err))
      } finally {
        if (!signal?.cancelled) setLoading(false)
      }
    },
    [buildQuery],
  )

  useEffect(() => {
    const signal = { cancelled: false }
    void load(signal)
    return () => {
      signal.cancelled = true
    }
  }, [load])

  const verify = useCallback(async (signal?: { cancelled: boolean }) => {
    setVerifying(true)
    setVerifyError(null)
    try {
      const result = await verifyAuditChain()
      if (signal?.cancelled) return
      setVerification(result)
    } catch (err) {
      if (signal?.cancelled) return
      setVerifyError(err instanceof Error ? err.message : String(err))
    } finally {
      if (!signal?.cancelled) setVerifying(false)
    }
  }, [])

  useEffect(() => {
    const signal = { cancelled: false }
    void verify(signal)
    return () => {
      signal.cancelled = true
    }
  }, [verify])

  /** Fetches the next cursor page, anchored on the last loaded event's id. */
  const loadMore = useCallback(async () => {
    const last = events[events.length - 1]
    if (!last) return
    setLoadingMore(true)
    setError(null)
    try {
      const next = await fetchAuditEvents({ ...buildQuery(), afterId: last.id })
      setEvents((prev) => [...prev, ...next])
      setHasMore(next.length === PAGE_SIZE)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoadingMore(false)
    }
  }, [events, buildQuery])

  return (
    <div data-testid="audit-log-panel" className="space-y-4">
      {/* Header: title + chain-integrity indicator */}
      <div className="flex items-center justify-between gap-4">
        <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
          Activity &amp; Audit Trail
        </h3>
        <ChainIntegrityIndicator
          verification={verification}
          verifying={verifying}
          error={verifyError}
          onRecheck={() => void verify()}
        />
      </div>

      {/* Filter bar */}
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
          Book
          <input
            data-testid="audit-filter-book"
            type="text"
            value={bookId}
            placeholder="Book ID"
            onChange={(e) => setBookId(e.target.value)}
            className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
          Trade
          <input
            data-testid="audit-filter-trade"
            type="text"
            value={tradeId}
            placeholder="Trade ID"
            onChange={(e) => setTradeId(e.target.value)}
            className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
          Event type
          <input
            data-testid="audit-filter-event-type"
            type="text"
            value={eventType}
            placeholder="e.g. TRADE_BOOKED"
            onChange={(e) => setEventType(e.target.value)}
            className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
          From
          <input
            data-testid="audit-filter-from"
            type="datetime-local"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
          To
          <input
            data-testid="audit-filter-to"
            type="datetime-local"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="border border-slate-300 dark:border-surface-600 rounded-md px-3 py-1.5 text-sm bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
          />
        </label>
      </div>

      {/* Body: loading / error / empty / table */}
      {loading ? (
        <div data-testid="audit-loading" className="flex items-center gap-2 text-sm text-slate-500">
          <Spinner size="sm" />
          Loading audit events...
        </div>
      ) : error ? (
        <ErrorCard message={error} onRetry={() => void load()} data-testid="audit-error" />
      ) : events.length === 0 ? (
        <Card>
          <EmptyState
            icon={<Inbox className="h-10 w-10" />}
            title="No audit events match your filters."
            description="Adjust the book, trade, event-type or time-window filters above."
          />
        </Card>
      ) : (
        <>
          <Card>
            <div className="-mx-4 -my-4 overflow-x-auto">
              <table
                data-testid="audit-events-table"
                className="min-w-full divide-y divide-slate-200 dark:divide-surface-700"
              >
                <thead>
                  <tr className="bg-slate-50 dark:bg-surface-800">
                    <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Time</th>
                    <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Event</th>
                    <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Subject</th>
                    <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">Book</th>
                    <th className="px-4 py-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-300">User</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-surface-700">
                  {events.map((event) => (
                    <tr
                      key={event.id}
                      data-testid={`audit-row-${event.id}`}
                      className="hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
                    >
                      <td className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 whitespace-nowrap">
                        {new Date(event.receivedAt).toLocaleString()}
                      </td>
                      <td className="px-4 py-2 text-sm">
                        <Badge
                          variant={eventTypeVariant(event.eventType)}
                          data-testid={`audit-event-badge-${event.id}`}
                        >
                          {event.eventType}
                        </Badge>
                      </td>
                      <td className="px-4 py-2 text-sm font-mono text-slate-700 dark:text-slate-300">
                        {eventSubject(event)}
                      </td>
                      <td className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                        {event.bookId ?? '—'}
                      </td>
                      <td className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                        {event.userId ?? '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          {hasMore && (
            <div className="flex justify-center">
              <button
                data-testid="audit-load-more"
                onClick={() => void loadMore()}
                disabled={loadingMore}
                className="inline-flex items-center gap-1.5 px-4 py-1.5 text-sm font-medium text-slate-700 dark:text-slate-300 border border-slate-300 dark:border-surface-600 rounded-md hover:bg-slate-50 dark:hover:bg-surface-700 disabled:opacity-50 transition-colors"
              >
                {loadingMore ? <Spinner size="sm" /> : <RefreshCw className="h-3.5 w-3.5" />}
                Load more
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

interface ChainIntegrityIndicatorProps {
  verification: AuditVerifyResultDto | null
  verifying: boolean
  error: string | null
  onRecheck: () => void
}

/**
 * A compact badge showing whether the hash chain verified. Green when valid,
 * red when broken; both states report the event count that was checked.
 */
function ChainIntegrityIndicator({ verification, verifying, error, onRecheck }: ChainIntegrityIndicatorProps) {
  if (verifying) {
    return (
      <span
        data-testid="audit-chain-verifying"
        className="inline-flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400"
      >
        <Spinner size="sm" />
        Verifying chain...
      </span>
    )
  }

  if (error) {
    return (
      <button
        type="button"
        data-testid="audit-chain-error"
        onClick={onRecheck}
        className="inline-flex items-center gap-1.5 px-2 py-1 text-xs font-medium rounded bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300 hover:underline"
      >
        <ShieldAlert className="h-3.5 w-3.5" />
        Chain check failed — retry
      </button>
    )
  }

  if (!verification) return null

  if (verification.valid) {
    return (
      <span
        data-testid="audit-chain-valid"
        className="inline-flex items-center gap-1.5 px-2 py-1 text-xs font-medium rounded bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300"
      >
        <ShieldCheck className="h-3.5 w-3.5" />
        Chain verified · {verification.eventCount} event{verification.eventCount === 1 ? '' : 's'}
      </span>
    )
  }

  return (
    <span
      data-testid="audit-chain-broken"
      role="alert"
      className="inline-flex items-center gap-1.5 px-2 py-1 text-xs font-medium rounded bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300"
    >
      <ShieldAlert className="h-3.5 w-3.5" />
      Chain broken · {verification.eventCount} event{verification.eventCount === 1 ? '' : 's'}
    </span>
  )
}
