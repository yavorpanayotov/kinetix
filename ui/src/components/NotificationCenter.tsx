import { useMemo, useState } from 'react'
import {
  Bell,
  Plus,
  Trash2,
  AlertTriangle,
  AlertCircle,
  Info,
  Download,
  CheckCircle,
  Check,
  ChevronDown,
  ChevronRight,
  ArrowRight,
  ArrowUpCircle,
  CheckSquare,
  Clock,
} from 'lucide-react'
import type { AlertRuleDto, AlertEventDto, CreateAlertRuleRequestDto } from '../types'
import { formatRelativeTime, formatRelativeFuture } from '../utils/format'
import { exportToCsv } from '../utils/exportCsv'
import { Card, Button, Badge, Input, Select, Spinner, ErrorCard } from './ui'
import { ConfirmDialog } from './ui/ConfirmDialog'
import { PaginationControls } from './ui/PaginationControls'
import { useCopilotContext } from '../hooks/useCopilotContext'
import { chat, type ChatChunk, type ChatRequest } from '../api/copilot'
import { ExplainButton } from './ExplainButton'
import { AIInsightPanel } from './AIInsightPanel'

/** Signature of the injectable `chatFn` — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

/**
 * Lifecycle states the Alerts queue can include/exclude. The 'ALL' chip from
 * the original single-select filter is replaced by per-status toggles (see
 * docs/plans/ui-overhaul.md §3.2).
 */
type AlertStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'ESCALATED' | 'RESOLVED'

const ALL_STATUSES: AlertStatus[] = [
  'TRIGGERED',
  'ACKNOWLEDGED',
  'ESCALATED',
  'RESOLVED',
]

/**
 * Default queue view: unresolved + unacknowledged statuses. RESOLVED is
 * hidden until the user opts in by clicking the chip — §3.2 of the plan.
 */
const DEFAULT_ACTIVE_STATUSES: AlertStatus[] = [
  'TRIGGERED',
  'ACKNOWLEDGED',
  'ESCALATED',
]

/** Auto-collapse window for RESOLVED alerts — older than 24h. */
const RESOLVED_COLLAPSE_MS = 24 * 60 * 60 * 1000

/** Triage-queue rows per page — alert cards are tall, so 25 keeps a page scannable. */
const QUEUE_PAGE_SIZE = 25

interface NotificationCenterProps {
  rules: AlertRuleDto[]
  alerts: AlertEventDto[]
  loading: boolean
  error: string | null
  onCreateRule: (request: CreateAlertRuleRequestDto) => void
  onDeleteRule: (ruleId: string) => void
  /**
   * Triage action invoked when the user submits the inline Acknowledge form.
   * If omitted, the per-alert Acknowledge button is hidden — useful for
   * read-only embeds.
   */
  onAcknowledge?: (alertId: string, notes?: string) => Promise<void> | void
  /**
   * Manual escalation action — operator decides an alert needs escalation
   * before the auto-escalation timer fires. Visible on TRIGGERED and
   * ACKNOWLEDGED alerts only (per backend transition rules). If omitted,
   * the Escalate button is hidden.
   */
  onEscalate?: (
    alertId: string,
    reason: string,
    assignee?: string,
  ) => Promise<void> | void
  /**
   * Resolve action — closes out an alert with an audit note. Visible on any
   * non-RESOLVED alert. If omitted, the Resolve button is hidden.
   */
  onResolve?: (alertId: string, resolutionText: string) => Promise<void> | void
  /**
   * Snooze action — silences the alert rule until the given ISO-8601 deadline.
   * The popover offers fixed presets (1h / 4h / 24h / Until tomorrow); see
   * plan §3.1b.4. Visible on any non-RESOLVED alert. If omitted, the Snooze
   * button is hidden.
   */
  onSnooze?: (alertId: string, snoozedUntil: string) => Promise<void> | void
  /**
   * Cross-tab navigation: jump from an alert row to the Risk tab focused on
   * the alert's affected book. Receives the alert's bookId, or null when the
   * alert is not scoped to a specific book. See docs/plans/ui-overhaul.md §2.4.
   */
  onJumpToRisk?: (bookId: string | null) => void
  /**
   * Dependency-injection seam for the streaming `chat()` client. Tests
   * substitute a fake; production callers leave it unset and the real
   * `chat` import is used (plan §9.3 — inline explainer on alert rows).
   */
  chatFn?: ChatFn
}

/**
 * Build the `page_context` for an alert-row inline explainer `/chat` call.
 *
 * Extends the ambient copilot context (`useCopilotContext()`) with the
 * clicked alert's payload — alertId, type, currentValue, threshold and
 * severity — so the model can speak to *that* specific breach (plan §9.3).
 */
function buildAlertExplainContext(
  base: Record<string, unknown>,
  alert: AlertEventDto,
): Record<string, unknown> {
  return {
    ...base,
    page: 'alerts',
    alertId: alert.id,
    type: alert.type,
    currentValue: alert.currentValue,
    threshold: alert.threshold,
    severity: alert.severity,
    book_id: alert.bookId,
  }
}

/**
 * Snooze preset definitions — fixed durations per plan §3.1b.4. Each preset
 * returns an absolute Date when invoked with the current "now" so the caller
 * can substitute a fixed clock in tests (vi.setSystemTime).
 */
const SNOOZE_PRESETS: Array<{
  id: 'snooze-preset-1h' | 'snooze-preset-4h' | 'snooze-preset-24h' | 'snooze-preset-tomorrow'
  shortLabel: string
  label: string
  compute: (now: Date) => Date
}> = [
  {
    id: 'snooze-preset-1h',
    shortLabel: '1h',
    label: '1 hour',
    compute: (now) => new Date(now.getTime() + 60 * 60 * 1000),
  },
  {
    id: 'snooze-preset-4h',
    shortLabel: '4h',
    label: '4 hours',
    compute: (now) => new Date(now.getTime() + 4 * 60 * 60 * 1000),
  },
  {
    id: 'snooze-preset-24h',
    shortLabel: '24h',
    label: '24 hours',
    compute: (now) => new Date(now.getTime() + 24 * 60 * 60 * 1000),
  },
  {
    id: 'snooze-preset-tomorrow',
    shortLabel: 'Until tomorrow',
    label: 'Until tomorrow (09:00)',
    // 09:00 local time on the next calendar day.
    compute: (now) => {
      const d = new Date(now)
      d.setDate(d.getDate() + 1)
      d.setHours(9, 0, 0, 0)
      return d
    },
  },
]

const statusBadgeClass: Record<string, string> = {
  TRIGGERED: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  ACKNOWLEDGED: 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300',
  ESCALATED: 'bg-orange-100 text-orange-800 dark:bg-orange-900/40 dark:text-orange-300',
  RESOLVED: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300',
}

const severityBadgeVariant: Record<string, 'critical' | 'warning' | 'info'> = {
  CRITICAL: 'critical',
  WARNING: 'warning',
  INFO: 'info',
}

const severityBorderColor: Record<string, string> = {
  CRITICAL: 'border-red-500',
  WARNING: 'border-yellow-500',
  INFO: 'border-blue-500',
}

const severityIcon: Record<string, typeof AlertTriangle> = {
  CRITICAL: AlertCircle,
  WARNING: AlertTriangle,
  INFO: Info,
}

const severityOrder: Record<string, number> = {
  CRITICAL: 0,
  WARNING: 1,
  INFO: 2,
}

export function NotificationCenter({
  rules,
  alerts,
  loading,
  error,
  onCreateRule,
  onDeleteRule,
  onAcknowledge,
  onEscalate,
  onResolve,
  onSnooze,
  onJumpToRisk,
  chatFn = chat,
}: NotificationCenterProps) {
  const copilotContext = useCopilotContext()
  const [name, setName] = useState('')
  const [type, setType] = useState('VAR_BREACH')
  const [threshold, setThreshold] = useState('')
  const [operator, setOperator] = useState('GREATER_THAN')
  const [severity, setSeverity] = useState('CRITICAL')
  const [channels, setChannels] = useState<string[]>(['IN_APP'])
  /**
   * Per-field validation errors for the create-rule form. We surface these
   * inline with aria-describedby + aria-invalid so screen-reader users get
   * the same feedback as sighted users — see docs/plans/ui-overhaul.md §6.4.
   * Browser-native `required` was insufficient: it never announces *which*
   * field failed and renders a transient bubble that screen readers ignore.
   */
  const [nameError, setNameError] = useState<string | null>(null)
  const [thresholdError, setThresholdError] = useState<string | null>(null)
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
  const [activeStatuses, setActiveStatuses] = useState<Set<AlertStatus>>(
    () => new Set(DEFAULT_ACTIVE_STATUSES),
  )
  const [olderResolvedExpanded, setOlderResolvedExpanded] = useState(false)
  const [queuePage, setQueuePage] = useState(1)
  const [ackOpenId, setAckOpenId] = useState<string | null>(null)
  const [ackNote, setAckNote] = useState('')
  const [ackSubmitting, setAckSubmitting] = useState(false)
  // Inline escalate form state — single-row at a time keeps the layout calm
  // and prevents the user from accidentally fanning out parallel form submits.
  const [escalateOpenId, setEscalateOpenId] = useState<string | null>(null)
  const [escalateReason, setEscalateReason] = useState('')
  const [escalateAssignee, setEscalateAssignee] = useState('')
  const [escalateSubmitting, setEscalateSubmitting] = useState(false)
  const [escalateReasonError, setEscalateReasonError] = useState<string | null>(
    null,
  )
  // Inline resolve form state — same single-row contract as escalate.
  const [resolveOpenId, setResolveOpenId] = useState<string | null>(null)
  const [resolveText, setResolveText] = useState('')
  const [resolveSubmitting, setResolveSubmitting] = useState(false)
  const [resolveTextError, setResolveTextError] = useState<string | null>(null)
  // Snooze popover state — only one row's popover open at a time so the row
  // layout stays compact. §3.1b.4 spec: fixed presets, no custom datepicker.
  const [snoozeOpenId, setSnoozeOpenId] = useState<string | null>(null)
  // Inline explainer state (plan §9.3). At most one alert-row explainer is
  // ever open — `explainAlertId` identifies which row's panel is showing and
  // `explainStream` is the live `/chat` token stream feeding the panel.
  const [explainAlertId, setExplainAlertId] = useState<string | null>(null)
  const [explainStream, setExplainStream] =
    useState<ReadableStream<ChatChunk> | null>(null)
  // Batch-acknowledge state (trader review §20). Selection is keyed by
  // alertId so an out-of-order list refresh can't break the mapping. Only
  // TRIGGERED rows are selectable; non-TRIGGERED ids are filtered out before
  // every batch fan-out so a stale selection can't ack an already-resolved
  // alert.
  const [selectedAlertIds, setSelectedAlertIds] = useState<Set<string>>(
    () => new Set(),
  )
  const [batchAckSubmitting, setBatchAckSubmitting] = useState(false)

  /**
   * Count of alerts per status — drives the chip badges so the operator can
   * see backlog at a glance even when a status is filtered out.
   */
  const statusCounts = useMemo(() => {
    const counts: Record<AlertStatus, number> = {
      TRIGGERED: 0,
      ACKNOWLEDGED: 0,
      ESCALATED: 0,
      RESOLVED: 0,
    }
    for (const a of alerts) {
      if (a.status in counts) {
        counts[a.status as AlertStatus] += 1
      }
    }
    return counts
  }, [alerts])

  /**
   * Queue ordering (§3.2): CRITICAL > WARNING > INFO first, then newest
   * triggeredAt within bucket. This is the opposite of a flat time-ordered
   * list — a trader wants the worst-severity unresolved item at the top.
   */
  const sortedAlerts = useMemo(() => {
    const filtered = alerts.filter((a) =>
      activeStatuses.has(a.status as AlertStatus),
    )
    return [...filtered].sort((a, b) => {
      const sevDelta =
        (severityOrder[a.severity] ?? 99) -
        (severityOrder[b.severity] ?? 99)
      if (sevDelta !== 0) return sevDelta
      return (
        new Date(b.triggeredAt).getTime() - new Date(a.triggeredAt).getTime()
      )
    })
  }, [alerts, activeStatuses])

  /**
   * Split the sorted queue into rows that render immediately and a tail of
   * RESOLVED alerts >24h old that collapse into a single summary row.
   *
   * "age" reference: resolvedAt if present (when the alert actually closed),
   * otherwise triggeredAt as a conservative fallback for old data missing
   * resolvedAt.
   */
  const { visibleAlerts, olderResolved } = useMemo(() => {
    const now = Date.now()
    const visible: AlertEventDto[] = []
    const older: AlertEventDto[] = []
    for (const a of sortedAlerts) {
      if (a.status === 'RESOLVED') {
        const ageRef = a.resolvedAt ?? a.triggeredAt
        const ageMs = now - new Date(ageRef).getTime()
        if (ageMs > RESOLVED_COLLAPSE_MS) {
          older.push(a)
          continue
        }
      }
      visible.push(a)
    }
    return { visibleAlerts: visible, olderResolved: older }
  }, [sortedAlerts])

  // Paginate the triage queue — a 50+ alert backlog rendered as one endless
  // column produced a 6,000px page (UX review). 25 rows per page; the pager
  // resets when the status filter changes the underlying list.
  const queueTotalPages = Math.ceil(visibleAlerts.length / QUEUE_PAGE_SIZE)
  const clampedQueuePage = Math.min(queuePage, Math.max(1, queueTotalPages))
  const pagedAlerts = visibleAlerts.slice(
    (clampedQueuePage - 1) * QUEUE_PAGE_SIZE,
    clampedQueuePage * QUEUE_PAGE_SIZE,
  )

  function toggleStatusFilter(status: AlertStatus) {
    setActiveStatuses((prev) => {
      const next = new Set(prev)
      if (next.has(status)) {
        next.delete(status)
      } else {
        next.add(status)
      }
      return next
    })
  }

  function handleChannelToggle(ch: string) {
    setChannels((prev) =>
      prev.includes(ch) ? prev.filter((c) => c !== ch) : [...prev, ch],
    )
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    // Explicit JS validation is the source of truth (§6.4) — HTML5 `required`
    // alone leaves screen-reader users without a clear "this field failed"
    // signal. Errors here drive the inline messages + aria-invalid below.
    const trimmedName = name.trim()
    const trimmedThreshold = threshold.trim()
    const nextNameError = trimmedName === '' ? 'Rule name is required.' : null
    const nextThresholdError =
      trimmedThreshold === '' ? 'Threshold is required.' : null
    setNameError(nextNameError)
    setThresholdError(nextThresholdError)
    if (nextNameError !== null || nextThresholdError !== null) {
      return
    }
    onCreateRule({
      name: trimmedName,
      type,
      threshold: Number(trimmedThreshold),
      operator,
      severity,
      channels,
    })
    setName('')
    setThreshold('')
  }

  function openAcknowledge(alertId: string) {
    setAckOpenId(alertId)
    setAckNote('')
  }

  function closeAcknowledge() {
    setAckOpenId(null)
    setAckNote('')
  }

  async function submitAcknowledge(alertId: string) {
    if (!onAcknowledge) return
    setAckSubmitting(true)
    try {
      const trimmed = ackNote.trim()
      await onAcknowledge(alertId, trimmed === '' ? undefined : trimmed)
    } catch {
      // Parent (useNotifications) surfaces the error and rolls back the
      // optimistic state. We swallow here so the form can close cleanly —
      // the rolled-back status badge and the inline error banner give the
      // user the feedback they need.
    } finally {
      setAckSubmitting(false)
      // Close regardless of outcome so the user sees the rolled-back state
      // or the new acknowledged status. They can click Acknowledge again
      // to retry on failure.
      closeAcknowledge()
    }
  }

  function openEscalate(alertId: string) {
    setEscalateOpenId(alertId)
    setEscalateReason('')
    setEscalateAssignee('')
    setEscalateReasonError(null)
  }

  function closeEscalate() {
    setEscalateOpenId(null)
    setEscalateReason('')
    setEscalateAssignee('')
    setEscalateReasonError(null)
  }

  async function submitEscalate(alertId: string) {
    if (!onEscalate) return
    const trimmedReason = escalateReason.trim()
    if (trimmedReason === '') {
      // Reason is required by the backend (HTTP 400 on blank). Surface inline
      // so the user fixes it without ever hitting the API.
      setEscalateReasonError('Reason is required.')
      return
    }
    setEscalateSubmitting(true)
    const trimmedAssignee = escalateAssignee.trim()
    try {
      await onEscalate(
        alertId,
        trimmedReason,
        trimmedAssignee === '' ? undefined : trimmedAssignee,
      )
    } catch {
      // Parent rolls back; close the form and let the badge/error banner do
      // the talking.
    } finally {
      setEscalateSubmitting(false)
      closeEscalate()
    }
  }

  function openResolve(alertId: string) {
    setResolveOpenId(alertId)
    setResolveText('')
    setResolveTextError(null)
  }

  function closeResolve() {
    setResolveOpenId(null)
    setResolveText('')
    setResolveTextError(null)
  }

  async function submitResolve(alertId: string) {
    if (!onResolve) return
    const trimmed = resolveText.trim()
    if (trimmed === '') {
      // Backend rejects blank resolutionText with HTTP 400.
      setResolveTextError('Resolution is required.')
      return
    }
    setResolveSubmitting(true)
    try {
      await onResolve(alertId, trimmed)
    } catch {
      // Parent rolls back optimistic state.
    } finally {
      setResolveSubmitting(false)
      closeResolve()
    }
  }

  function toggleSnoozePopover(alertId: string) {
    setSnoozeOpenId((current) => (current === alertId ? null : alertId))
  }

  function closeSnoozePopover() {
    setSnoozeOpenId(null)
  }

  async function applySnoozePreset(
    alertId: string,
    compute: (now: Date) => Date,
  ) {
    if (!onSnooze) return
    const iso = compute(new Date()).toISOString()
    closeSnoozePopover()
    try {
      await onSnooze(alertId, iso)
    } catch {
      // Parent (useNotifications) surfaces the error and rolls back the
      // optimistic update. The popover is already closed; the user can
      // re-open and retry.
    }
  }

  /**
   * Open the inline explainer for a specific alert row.
   *
   * Double-click protection: a second click on the alert whose panel is
   * already open is a no-op — neither a duplicate panel nor a duplicate
   * `/chat` request is created. Opening a *different* alert's explainer
   * replaces the current one ("only one panel open"), plan §9.3.
   */
  function handleExplainAlert(alert: AlertEventDto) {
    if (explainAlertId === alert.id) return
    const stream = chatFn({
      message: `Explain this ${alert.type} alert — why did it breach?`,
      page_context: buildAlertExplainContext(copilotContext, alert),
    })
    setExplainAlertId(alert.id)
    setExplainStream(stream)
  }

  function closeExplainAlert() {
    setExplainAlertId(null)
    setExplainStream(null)
  }

  /**
   * Rows the operator can batch-select right now. Filtering on `visibleAlerts`
   * — not the unfiltered list — means a TRIGGERED alert hidden by the status
   * chips is never silently acknowledged by a `Select all visible` action.
   * The name reflects exactly that constraint.
   */
  const triggeredVisibleAlerts = useMemo(
    () => visibleAlerts.filter((a) => a.status === 'TRIGGERED'),
    [visibleAlerts],
  )

  function toggleRowSelected(alertId: string) {
    setSelectedAlertIds((prev) => {
      const next = new Set(prev)
      if (next.has(alertId)) {
        next.delete(alertId)
      } else {
        next.add(alertId)
      }
      return next
    })
  }

  function selectAllVisibleTriggered() {
    setSelectedAlertIds((prev) => {
      const next = new Set(prev)
      for (const a of triggeredVisibleAlerts) next.add(a.id)
      return next
    })
  }

  function clearSelection() {
    setSelectedAlertIds(new Set())
  }

  /**
   * Fan out one acknowledge POST per selected alert. The batch action is
   * deliberately a client-side fan-out: no new backend route is added —
   * existing `/alerts/{id}/acknowledge` is reused — so a partial failure
   * surfaces per-row via the same error path as the inline Acknowledge form.
   * After the fan-out completes the selection is cleared so the operator
   * can't double-submit.
   */
  async function acknowledgeSelected() {
    if (!onAcknowledge) return
    // Re-check status at submit-time: a refresh between selection and submit
    // could move an alert out of TRIGGERED; we must never ack non-TRIGGERED
    // rows because the backend rejects that and it would surface as N error
    // toasts.
    const triggeredIds = triggeredVisibleAlerts
      .filter((a) => selectedAlertIds.has(a.id))
      .map((a) => a.id)
    if (triggeredIds.length === 0) return
    setBatchAckSubmitting(true)
    try {
      await Promise.all(
        triggeredIds.map(async (id) => {
          try {
            await onAcknowledge(id)
          } catch {
            // Parent (useNotifications) surfaces individual failures via the
            // shared error banner and rolls back the optimistic update for
            // that row. We swallow per-id so one failure does not block the
            // rest of the batch.
          }
        }),
      )
    } finally {
      setBatchAckSubmitting(false)
      clearSelection()
    }
  }

  const selectionCount = selectedAlertIds.size
  const canBatchAcknowledge =
    onAcknowledge !== undefined &&
    triggeredVisibleAlerts.length > 0 &&
    selectionCount > 0 &&
    !batchAckSubmitting

  function renderAlertRow(alert: AlertEventDto) {
    const SevIcon = severityIcon[alert.severity] ?? Info
    const canAcknowledge =
      onAcknowledge !== undefined && alert.status === 'TRIGGERED'
    // Escalate is available while the operator can still escalate manually:
    // TRIGGERED or ACKNOWLEDGED. Once ESCALATED or RESOLVED, the backend
    // rejects further escalation, so we hide the button.
    const canEscalate =
      onEscalate !== undefined &&
      (alert.status === 'TRIGGERED' || alert.status === 'ACKNOWLEDGED')
    // Resolve is available on every non-terminal state.
    const canResolve = onResolve !== undefined && alert.status !== 'RESOLVED'
    // Snooze: any non-RESOLVED alert (§3.1b.4). The popover is a sibling of
    // the action cluster — we keep it open even when other inline forms close.
    const canSnooze = onSnooze !== undefined && alert.status !== 'RESOLVED'
    const ackFormOpen = ackOpenId === alert.id
    const escalateFormOpen = escalateOpenId === alert.id
    const resolveFormOpen = resolveOpenId === alert.id
    const snoozePopoverOpen = snoozeOpenId === alert.id
    const anyFormOpen = ackFormOpen || escalateFormOpen || resolveFormOpen
    // Snoozed-until badge: render when snoozedUntil is in the future. Past
    // values are dropped silently — the badge is purely informational and
    // not meaningful once the deadline has lapsed.
    const snoozedActive =
      typeof alert.snoozedUntil === 'string' &&
      alert.snoozedUntil !== '' &&
      new Date(alert.snoozedUntil).getTime() > Date.now()
    // Per-row batch-select checkbox: only rendered for TRIGGERED rows so the
    // operator can't accidentally batch-action an alert that is already past
    // the actionable state. We don't render a checkbox at all when there is
    // no `onAcknowledge` callback wired — without an action it would just
    // produce dead chrome.
    const canBatchSelect =
      onAcknowledge !== undefined && alert.status === 'TRIGGERED'
    const isSelected = selectedAlertIds.has(alert.id)
    return (
      <div
        key={alert.id}
        className={`flex items-start gap-2 p-2 bg-slate-50 dark:bg-surface-800 rounded text-sm border-l-4 ${severityBorderColor[alert.severity] ?? 'border-gray-300'}`}
      >
        {canBatchSelect && (
          <input
            type="checkbox"
            data-testid={`alerts-row-select-${alert.id}`}
            aria-label={`Select alert ${alert.message}`}
            checked={isSelected}
            onChange={() => toggleRowSelected(alert.id)}
            className="mt-1 shrink-0"
          />
        )}
        <SevIcon className="h-4 w-4 mt-0.5 shrink-0 text-slate-500 dark:text-slate-400" />
        <span
          data-testid={`severity-badge-${alert.id}`}
          className={`px-2 py-0.5 rounded text-xs font-medium ${
            alert.severity === 'CRITICAL'
              ? 'bg-red-100 text-red-800'
              : alert.severity === 'WARNING'
              ? 'bg-yellow-100 text-yellow-800'
              : 'bg-blue-100 text-blue-800'
          }`}
        >
          {alert.severity}
        </span>
        <div className="flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-slate-800 dark:text-slate-100">{alert.message}</span>
            <span
              data-testid={`status-badge-${alert.id}`}
              className={`px-1.5 py-0.5 text-xs font-semibold rounded ${
                statusBadgeClass[alert.status] ?? 'bg-slate-100 text-slate-700 dark:bg-surface-700 dark:text-slate-300'
              }`}
            >
              {alert.status}
            </span>
            {alert.status === 'ESCALATED' && (
              <span
                data-testid={`escalation-badge-${alert.id}`}
                className="px-1.5 py-0.5 text-xs font-semibold bg-orange-100 text-orange-800 rounded"
              >
                ESCALATED
              </span>
            )}
            {snoozedActive && alert.snoozedUntil && (
              <span
                data-testid={`snoozed-until-badge-${alert.id}`}
                title={new Date(alert.snoozedUntil).toLocaleString()}
                className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs font-medium bg-amber-100 text-amber-800 rounded"
              >
                <Clock className="h-3 w-3" />
                Snoozed until {formatRelativeFuture(alert.snoozedUntil)}
              </span>
            )}
            {/*
              Action cluster: all triage buttons sit in a single ml-auto group
              so the row layout stays compact regardless of which combination
              of callbacks is wired. The "anyFormOpen" gate hides the buttons
              for the row whose inline form is active to keep focus on the
              form itself.
            */}
            <div className="ml-auto flex items-center gap-1">
              {canAcknowledge && !anyFormOpen && (
                <button
                  data-testid={`acknowledge-btn-${alert.id}`}
                  onClick={() => openAcknowledge(alert.id)}
                  className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium text-blue-700 bg-blue-50 border border-blue-200 rounded hover:bg-blue-100 transition-colors"
                >
                  <Check className="h-3 w-3" />
                  Acknowledge
                </button>
              )}
              {canEscalate && !anyFormOpen && (
                <button
                  data-testid={`escalate-btn-${alert.id}`}
                  onClick={() => openEscalate(alert.id)}
                  className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium text-orange-700 bg-orange-50 border border-orange-200 rounded hover:bg-orange-100 transition-colors"
                >
                  <ArrowUpCircle className="h-3 w-3" />
                  Escalate
                </button>
              )}
              {canResolve && !anyFormOpen && (
                <button
                  data-testid={`resolve-btn-${alert.id}`}
                  onClick={() => openResolve(alert.id)}
                  className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium text-green-700 bg-green-50 border border-green-200 rounded hover:bg-green-100 transition-colors"
                >
                  <CheckSquare className="h-3 w-3" />
                  Resolve
                </button>
              )}
              {canSnooze && !anyFormOpen && (
                <div className="relative">
                  <button
                    data-testid={`snooze-btn-${alert.id}`}
                    onClick={() => toggleSnoozePopover(alert.id)}
                    aria-haspopup="menu"
                    aria-expanded={snoozePopoverOpen}
                    className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium text-amber-700 bg-amber-50 border border-amber-200 rounded hover:bg-amber-100 transition-colors"
                  >
                    <Clock className="h-3 w-3" />
                    Snooze
                  </button>
                  {snoozePopoverOpen && (
                    <div
                      data-testid={`snooze-popover-${alert.id}`}
                      role="menu"
                      className="absolute right-0 z-10 mt-1 w-44 rounded border border-slate-200 dark:border-surface-600 bg-white dark:bg-surface-800 shadow-lg p-1 flex flex-col gap-0.5"
                    >
                      {SNOOZE_PRESETS.map((preset) => (
                        <button
                          key={preset.id}
                          type="button"
                          role="menuitem"
                          data-testid={`${preset.id}-${alert.id}`}
                          onClick={() =>
                            applySnoozePreset(alert.id, preset.compute)
                          }
                          className="text-left px-2 py-1 text-xs text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-surface-700 rounded"
                        >
                          {preset.label}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
              {onJumpToRisk && (
                <button
                  data-testid={`jump-to-risk-${alert.id}`}
                  onClick={() => onJumpToRisk(alert.bookId ? alert.bookId : null)}
                  className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium text-indigo-700 bg-indigo-50 border border-indigo-200 rounded hover:bg-indigo-100 transition-colors"
                  title="Open the affected book on the Risk tab"
                  aria-label={`Jump to Risk tab for ${alert.bookId || 'unknown book'}`}
                >
                  <ArrowRight className="h-3 w-3" />
                  Go to Risk
                </button>
              )}
              {/* Inline explainer affordance — plan §9.3. Stays visible even
                  while a triage form is open so the operator can ask the
                  copilot mid-triage. */}
              <ExplainButton
                data-testid={`explain-alert-${alert.id}`}
                label="Explain"
                ariaLabel={`Explain alert ${alert.message}`}
                onClick={() => handleExplainAlert(alert)}
                className="px-2 py-0.5 text-xs"
              />
            </div>
          </div>
          <div className="text-xs text-slate-500 dark:text-slate-400">
            Book: {alert.bookId} | {formatRelativeTime(alert.triggeredAt)}
            {alert.status === 'RESOLVED' && alert.resolvedAt && (
              <span className="ml-2 text-green-600">
                <CheckCircle className="inline h-3 w-3 mr-0.5" />
                Resolved {formatRelativeTime(alert.resolvedAt)}
              </span>
            )}
            {alert.status === 'ACKNOWLEDGED' && (
              <span className="ml-2 text-slate-500 dark:text-slate-400">
                <CheckCircle className="inline h-3 w-3 mr-0.5" />
                Acknowledged
              </span>
            )}
            {alert.status === 'ESCALATED' && alert.escalatedTo && (
              <span className="ml-2 text-orange-700">
                Escalated to:{' '}
                <span data-testid={`escalated-to-${alert.id}`}>
                  {alert.escalatedTo}
                </span>
                {alert.escalatedAt && (
                  <span
                    data-testid={`escalated-at-${alert.id}`}
                    className="ml-1"
                  >
                    ({formatRelativeTime(alert.escalatedAt)})
                  </span>
                )}
              </span>
            )}
          </div>
          {ackFormOpen && canAcknowledge && (
            <form
              data-testid={`acknowledge-form-${alert.id}`}
              onSubmit={(e) => {
                e.preventDefault()
                submitAcknowledge(alert.id)
              }}
              className="mt-2 flex items-center gap-2"
            >
              <Input
                data-testid={`acknowledge-note-${alert.id}`}
                placeholder="Optional note"
                value={ackNote}
                onChange={(e) => setAckNote(e.target.value)}
                className="flex-1"
              />
              <Button
                data-testid={`acknowledge-submit-${alert.id}`}
                type="submit"
                variant="primary"
                size="sm"
                disabled={ackSubmitting}
              >
                Submit
              </Button>
              <Button
                data-testid={`acknowledge-cancel-${alert.id}`}
                type="button"
                variant="secondary"
                size="sm"
                onClick={closeAcknowledge}
                disabled={ackSubmitting}
              >
                Cancel
              </Button>
            </form>
          )}
          {escalateFormOpen && canEscalate && (
            <form
              data-testid={`escalate-form-${alert.id}`}
              onSubmit={(e) => {
                e.preventDefault()
                submitEscalate(alert.id)
              }}
              noValidate
              className="mt-2 space-y-1"
            >
              <div className="flex items-center gap-2">
                <Input
                  data-testid={`escalate-reason-${alert.id}`}
                  placeholder="Reason (required)"
                  value={escalateReason}
                  onChange={(e) => {
                    setEscalateReason(e.target.value)
                    if (escalateReasonError !== null && e.target.value.trim() !== '') {
                      setEscalateReasonError(null)
                    }
                  }}
                  aria-invalid={escalateReasonError !== null ? 'true' : undefined}
                  aria-describedby={
                    escalateReasonError !== null
                      ? `escalate-reason-error-${alert.id}`
                      : undefined
                  }
                  className="flex-1"
                />
                <Input
                  data-testid={`escalate-assignee-${alert.id}`}
                  placeholder="Assignee (optional)"
                  value={escalateAssignee}
                  onChange={(e) => setEscalateAssignee(e.target.value)}
                  className="flex-1"
                />
                <Button
                  data-testid={`escalate-submit-${alert.id}`}
                  type="submit"
                  variant="primary"
                  size="sm"
                  disabled={escalateSubmitting}
                >
                  Submit
                </Button>
                <Button
                  data-testid={`escalate-cancel-${alert.id}`}
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={closeEscalate}
                  disabled={escalateSubmitting}
                >
                  Cancel
                </Button>
              </div>
              {escalateReasonError !== null && (
                <p
                  id={`escalate-reason-error-${alert.id}`}
                  data-testid={`escalate-reason-error-${alert.id}`}
                  role="alert"
                  className="text-xs text-red-600 dark:text-red-400"
                >
                  {escalateReasonError}
                </p>
              )}
            </form>
          )}
          {resolveFormOpen && canResolve && (
            <form
              data-testid={`resolve-form-${alert.id}`}
              onSubmit={(e) => {
                e.preventDefault()
                submitResolve(alert.id)
              }}
              noValidate
              className="mt-2 space-y-1"
            >
              <div className="flex items-center gap-2">
                <Input
                  data-testid={`resolve-text-${alert.id}`}
                  placeholder="Resolution (required)"
                  value={resolveText}
                  onChange={(e) => {
                    setResolveText(e.target.value)
                    if (resolveTextError !== null && e.target.value.trim() !== '') {
                      setResolveTextError(null)
                    }
                  }}
                  aria-invalid={resolveTextError !== null ? 'true' : undefined}
                  aria-describedby={
                    resolveTextError !== null
                      ? `resolve-text-error-${alert.id}`
                      : undefined
                  }
                  className="flex-1"
                />
                <Button
                  data-testid={`resolve-submit-${alert.id}`}
                  type="submit"
                  variant="primary"
                  size="sm"
                  disabled={resolveSubmitting}
                >
                  Submit
                </Button>
                <Button
                  data-testid={`resolve-cancel-${alert.id}`}
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={closeResolve}
                  disabled={resolveSubmitting}
                >
                  Cancel
                </Button>
              </div>
              {resolveTextError !== null && (
                <p
                  id={`resolve-text-error-${alert.id}`}
                  data-testid={`resolve-text-error-${alert.id}`}
                  role="alert"
                  className="text-xs text-red-600 dark:text-red-400"
                >
                  {resolveTextError}
                </p>
              )}
            </form>
          )}
          {/* Inline AI explainer panel — plan §9.3. At most one alert row's
              panel is open at a time; opening another replaces this one. */}
          {explainAlertId === alert.id && explainStream && (
            <div
              data-testid={`alert-explain-panel-${alert.id}`}
              className="mt-2"
            >
              <AIInsightPanel
                stream={explainStream}
                title={`Explain — ${alert.message}`}
                onClose={closeExplainAlert}
              />
            </div>
          )}
        </div>
      </div>
    )
  }

  return (
    <Card
      data-testid="notification-center"
      header={<span className="flex items-center gap-1.5"><Bell className="h-4 w-4" />Notification Center</span>}
    >
      {loading && (
        <div data-testid="notification-loading" className="flex items-center gap-2 text-slate-500 dark:text-slate-400 text-sm">
          <Spinner size="sm" />
          Loading notifications...
        </div>
      )}

      {error && (
        <div className="mb-3">
          <ErrorCard message={error} data-testid="notification-error" />
        </div>
      )}

      {/* Create Rule Form */}
      <div data-testid="create-rule-form" className="mb-4 p-3 bg-slate-50 dark:bg-surface-800 rounded-lg">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">Create Alert Rule</h3>
        <form onSubmit={handleSubmit} noValidate className="grid grid-cols-3 gap-2 text-sm">
          <div>
            <Input
              data-testid="rule-name-input"
              placeholder="Rule name"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
                if (nameError !== null && e.target.value.trim() !== '') {
                  setNameError(null)
                }
              }}
              required
              aria-invalid={nameError !== null ? 'true' : undefined}
              aria-describedby={nameError !== null ? 'rule-name-error' : undefined}
            />
            {nameError !== null && (
              <p
                id="rule-name-error"
                data-testid="rule-name-error"
                role="alert"
                className="mt-1 text-xs text-red-600 dark:text-red-400"
              >
                {nameError}
              </p>
            )}
          </div>
          <Select
            data-testid="rule-type-select"
            value={type}
            onChange={(e) => setType(e.target.value)}
          >
            <option value="VAR_BREACH">VaR Breach</option>
            <option value="PNL_THRESHOLD">P&amp;L Threshold</option>
            <option value="RISK_LIMIT">Risk Limit</option>
            <option value="DELTA_BREACH">Delta Breach</option>
            <option value="VEGA_BREACH">Vega Breach</option>
            <option value="CONCENTRATION">Concentration</option>
            <option value="MARGIN_BREACH">Margin Breach</option>
          </Select>
          <div>
            <Input
              data-testid="rule-threshold-input"
              type="number"
              placeholder="Threshold"
              value={threshold}
              onChange={(e) => {
                setThreshold(e.target.value)
                if (thresholdError !== null && e.target.value.trim() !== '') {
                  setThresholdError(null)
                }
              }}
              required
              aria-invalid={thresholdError !== null ? 'true' : undefined}
              aria-describedby={thresholdError !== null ? 'rule-threshold-error' : undefined}
            />
            {thresholdError !== null && (
              <p
                id="rule-threshold-error"
                data-testid="rule-threshold-error"
                role="alert"
                className="mt-1 text-xs text-red-600 dark:text-red-400"
              >
                {thresholdError}
              </p>
            )}
          </div>
          <Select
            data-testid="rule-operator-select"
            value={operator}
            onChange={(e) => setOperator(e.target.value)}
          >
            {/*
              EQUALS removed: "Equal to" for floating-point risk values never fires.
              Backend still accepts EQUALS for pre-existing rules, but users can no
              longer create new rules with it. See docs/plans/ui-overhaul.md §3.3.
            */}
            <option value="GREATER_THAN">Above</option>
            <option value="LESS_THAN">Below</option>
          </Select>
          <Select
            data-testid="rule-severity-select"
            value={severity}
            onChange={(e) => setSeverity(e.target.value)}
          >
            <option value="CRITICAL">CRITICAL</option>
            <option value="WARNING">WARNING</option>
            <option value="INFO">INFO</option>
          </Select>
          <div className="col-span-3 flex flex-wrap items-center gap-4">
            <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">Channels:</span>
            {['IN_APP', 'EMAIL', 'WEBHOOK'].map((ch) => (
              <label key={ch} className="flex items-center gap-1 text-xs text-slate-700 dark:text-slate-300">
                <input
                  type="checkbox"
                  data-testid={`channel-${ch}`}
                  checked={channels.includes(ch)}
                  onChange={() => handleChannelToggle(ch)}
                />
                {ch}
              </label>
            ))}
          </div>
          <Button
            data-testid="create-rule-btn"
            type="submit"
            variant="primary"
            size="md"
            icon={<Plus className="h-3.5 w-3.5" />}
            className="col-span-3"
          >
            Create Rule
          </Button>
        </form>
      </div>

      {/* Alert Rules Table */}
      <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">Alert Rules</h3>
      <table data-testid="rules-table" className="w-full text-sm mb-4">
        <thead>
          <tr className="border-b dark:border-surface-700 text-left text-slate-600 dark:text-slate-300">
            <th className="py-2">Name</th>
            <th className="py-2">Type</th>
            <th className="py-2 text-right">Threshold</th>
            <th className="py-2">Severity</th>
            <th className="py-2">Enabled</th>
            <th className="py-2"></th>
          </tr>
        </thead>
        <tbody>
          {rules.map((rule) => (
            <tr key={rule.id} className="border-b dark:border-surface-700 hover:bg-slate-50 dark:hover:bg-surface-800 transition-colors">
              <td className="py-1.5">{rule.name}</td>
              <td className="py-1.5">{rule.type}</td>
              <td className="py-1.5 text-right">{rule.threshold.toLocaleString()}</td>
              <td className="py-1.5">
                <Badge variant={severityBadgeVariant[rule.severity] ?? 'neutral'}>
                  {rule.severity}
                </Badge>
              </td>
              <td className="py-1.5">{rule.enabled ? 'Yes' : 'No'}</td>
              <td className="py-1.5">
                <button
                  data-testid={`delete-rule-${rule.id}`}
                  onClick={() => setPendingDeleteId(rule.id)}
                  className="text-red-500 hover:text-red-700 transition-colors"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Recent Alerts */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">Recent Alerts</h3>
          <div data-testid="alert-status-filters" className="flex gap-1">
            {ALL_STATUSES.map((s) => {
              const active = activeStatuses.has(s)
              const labelText = s.charAt(0) + s.slice(1).toLowerCase()
              const activeClass =
                s === 'ESCALATED'
                  ? 'bg-orange-100 text-orange-800 font-medium'
                  : 'bg-blue-100 text-blue-800 font-medium'
              return (
                <button
                  key={s}
                  type="button"
                  role="switch"
                  aria-checked={active}
                  data-testid={`status-filter-${s.toLowerCase()}`}
                  onClick={() => toggleStatusFilter(s)}
                  className={`px-2 py-0.5 text-xs rounded-full transition-colors ${
                    active ? activeClass : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-surface-700 dark:text-slate-300 dark:hover:bg-surface-600'
                  }`}
                >
                  <span>{labelText}</span>
                  <span
                    data-testid={`status-filter-count-${s.toLowerCase()}`}
                    className="ml-1 font-mono"
                  >
                    {statusCounts[s]}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
        {sortedAlerts.length > 0 && (
          <button
            data-testid="alerts-csv-export"
            onClick={() => {
              const headers = ['Severity', 'Type', 'Message', 'Book', 'Value', 'Threshold', 'Status', 'Time']
              const rows = sortedAlerts.map((a) => [
                a.severity,
                a.type,
                a.message,
                a.bookId,
                String(a.currentValue),
                String(a.threshold),
                a.status,
                a.triggeredAt,
              ])
              exportToCsv('alerts.csv', headers, rows)
            }}
            className="inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-slate-500 dark:text-slate-400 border border-slate-300 dark:border-surface-600 rounded hover:bg-slate-50 dark:hover:bg-surface-700 transition-colors"
          >
            <Download className="h-3.5 w-3.5" />
            Export CSV
          </button>
        )}
      </div>
      {/*
        Batch-acknowledge toolbar (trader review §20). Always rendered above
        the queue so the "Select all visible" affordance has a stable home;
        per-row checkboxes only appear for TRIGGERED rows. The action button
        is disabled until at least one row is selected so an operator never
        fires an empty batch.
      */}
      {onAcknowledge !== undefined && (
        <div
          data-testid="alerts-batch-toolbar"
          className="mb-2 flex items-center gap-2 text-xs text-slate-600 dark:text-slate-300"
        >
          <label className="flex items-center gap-1.5">
            <input
              type="checkbox"
              data-testid="alerts-select-all-visible"
              aria-label="Select all visible triggered alerts"
              checked={
                triggeredVisibleAlerts.length > 0 &&
                triggeredVisibleAlerts.every((a) => selectedAlertIds.has(a.id))
              }
              onChange={(e) => {
                if (e.target.checked) {
                  selectAllVisibleTriggered()
                } else {
                  clearSelection()
                }
              }}
              disabled={triggeredVisibleAlerts.length === 0}
            />
            <span>Select all visible</span>
          </label>
          <span className="text-slate-500 dark:text-slate-400">
            Selected:{' '}
            <span
              data-testid="alerts-selection-count"
              className="font-mono font-medium text-slate-700 dark:text-slate-200"
            >
              {selectionCount}
            </span>
          </span>
          <Button
            data-testid="alerts-acknowledge-selected"
            type="button"
            variant="primary"
            size="sm"
            onClick={acknowledgeSelected}
            disabled={!canBatchAcknowledge}
            icon={<Check className="h-3 w-3" />}
            className="ml-auto"
          >
            Acknowledge selected
          </Button>
        </div>
      )}
      <div data-testid="alerts-list" className="space-y-2">
        {pagedAlerts.map((alert) => renderAlertRow(alert))}
        {queueTotalPages > 1 && (
          <PaginationControls
            currentPage={clampedQueuePage}
            totalPages={queueTotalPages}
            onPageChange={setQueuePage}
          />
        )}
        {olderResolved.length > 0 && (
          <div
            data-testid="older-resolved-summary"
            className="rounded border border-slate-200 dark:border-surface-700 bg-slate-50 dark:bg-surface-800 text-sm"
          >
            <button
              type="button"
              data-testid="older-resolved-toggle"
              aria-expanded={olderResolvedExpanded}
              onClick={() => setOlderResolvedExpanded((v) => !v)}
              className="flex w-full items-center gap-2 px-2 py-1.5 text-left text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-surface-700 transition-colors"
            >
              {olderResolvedExpanded ? (
                <ChevronDown className="h-4 w-4" />
              ) : (
                <ChevronRight className="h-4 w-4" />
              )}
              <span>
                Older resolved (<span data-testid="older-resolved-count">{olderResolved.length}</span>)
              </span>
              <span className="text-xs text-slate-400">resolved &gt; 24h ago</span>
            </button>
            {olderResolvedExpanded && (
              <div className="space-y-2 px-2 pb-2 pt-1">
                {olderResolved.map((alert) => renderAlertRow(alert))}
              </div>
            )}
          </div>
        )}
      </div>

      <ConfirmDialog
        open={pendingDeleteId !== null}
        title="Delete Alert Rule"
        message="Are you sure you want to delete this alert rule? This action cannot be undone."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        variant="danger"
        onConfirm={() => {
          if (pendingDeleteId) onDeleteRule(pendingDeleteId)
          setPendingDeleteId(null)
        }}
        onCancel={() => setPendingDeleteId(null)}
      />
    </Card>
  )
}
