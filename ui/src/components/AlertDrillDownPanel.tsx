import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowUpCircle, Check, CheckSquare, Clock, X } from 'lucide-react'
import type { AlertEventDto } from '../types'
import { fetchAlertContributors, type PositionContributor } from '../api/alertContributors'
import { formatCurrency, formatRelativeFuture } from '../utils/format'
import { Button, EmptyState, Input, Spinner } from './ui'

/**
 * Snooze preset definitions — fixed durations per plan §3.1b.4. Duplicated
 * (intentionally — small constant) with NotificationCenter so the drill-down
 * panel stays a self-contained component that can be reused elsewhere.
 */
const SNOOZE_PRESETS: Array<{
  id: 'drill-down-snooze-preset-1h' | 'drill-down-snooze-preset-4h' | 'drill-down-snooze-preset-24h' | 'drill-down-snooze-preset-tomorrow'
  label: string
  compute: (now: Date) => Date
}> = [
  {
    id: 'drill-down-snooze-preset-1h',
    label: '1 hour',
    compute: (now) => new Date(now.getTime() + 60 * 60 * 1000),
  },
  {
    id: 'drill-down-snooze-preset-4h',
    label: '4 hours',
    compute: (now) => new Date(now.getTime() + 4 * 60 * 60 * 1000),
  },
  {
    id: 'drill-down-snooze-preset-24h',
    label: '24 hours',
    compute: (now) => new Date(now.getTime() + 24 * 60 * 60 * 1000),
  },
  {
    id: 'drill-down-snooze-preset-tomorrow',
    label: 'Until tomorrow (09:00)',
    compute: (now) => {
      const d = new Date(now)
      d.setDate(d.getDate() + 1)
      d.setHours(9, 0, 0, 0)
      return d
    },
  },
]

function useAlertContributors(alertId: string) {
  const [contributors, setContributors] = useState<PositionContributor[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchAlertContributors(alertId)
      setContributors(data)
    } catch {
      setContributors([])
    } finally {
      setLoading(false)
    }
  }, [alertId])

  useEffect(() => { load() }, [load])

  return { contributors, loading }
}

interface AlertDrillDownPanelProps {
  alert: AlertEventDto
  onClose: () => void
  /**
   * Acknowledge action. When provided and the alert is in TRIGGERED state, the
   * drill-down panel renders an inline Acknowledge form.
   */
  onAcknowledge?: (alertId: string, notes?: string) => Promise<void> | void
  /**
   * Manual escalate action. Visible on TRIGGERED + ACKNOWLEDGED alerts only
   * (backend transition rules). If omitted, the Escalate button is hidden.
   */
  onEscalate?: (
    alertId: string,
    reason: string,
    assignee?: string,
  ) => Promise<void> | void
  /**
   * Resolve action. Visible on any non-RESOLVED alert. If omitted, the
   * Resolve button is hidden.
   */
  onResolve?: (alertId: string, resolutionText: string) => Promise<void> | void
  /**
   * Snooze action. Fires with the alert id + an ISO-8601 deadline computed
   * from the preset (1h / 4h / 24h / next-day 09:00 local). Visible on any
   * non-RESOLVED alert. If omitted, the Snooze button is hidden. See plan
   * §3.1b.4.
   */
  onSnooze?: (alertId: string, snoozedUntil: string) => Promise<void> | void
}

const statusBadgeClass: Record<string, string> = {
  TRIGGERED: 'bg-red-100 text-red-800',
  ACKNOWLEDGED: 'bg-blue-100 text-blue-800',
  ESCALATED: 'bg-orange-100 text-orange-800',
  RESOLVED: 'bg-green-100 text-green-800',
}

export function AlertDrillDownPanel({
  alert,
  onClose,
  onAcknowledge,
  onEscalate,
  onResolve,
  onSnooze,
}: AlertDrillDownPanelProps) {
  const { contributors, loading } = useAlertContributors(alert.id)
  const [expanded, setExpanded] = useState(false)
  const [ackOpen, setAckOpen] = useState(false)
  const [ackNote, setAckNote] = useState('')
  const [ackSubmitting, setAckSubmitting] = useState(false)
  const [escalateOpen, setEscalateOpen] = useState(false)
  const [escalateReason, setEscalateReason] = useState('')
  const [escalateAssignee, setEscalateAssignee] = useState('')
  const [escalateSubmitting, setEscalateSubmitting] = useState(false)
  const [escalateReasonError, setEscalateReasonError] = useState<string | null>(
    null,
  )
  const [resolveOpen, setResolveOpen] = useState(false)
  const [resolveText, setResolveText] = useState('')
  const [resolveSubmitting, setResolveSubmitting] = useState(false)
  const [resolveTextError, setResolveTextError] = useState<string | null>(null)
  const [snoozeOpen, setSnoozeOpen] = useState(false)
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    closeRef.current?.focus()
  }, [])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    },
    [onClose],
  )

  const visible = expanded ? contributors : contributors.slice(0, 5)
  const remaining = contributors.length - 5

  const breachMagnitude = alert.currentValue - alert.threshold
  const canAcknowledge = onAcknowledge !== undefined && alert.status === 'TRIGGERED'
  // Backend allows manual escalation only from TRIGGERED or ACKNOWLEDGED.
  const canEscalate =
    onEscalate !== undefined &&
    (alert.status === 'TRIGGERED' || alert.status === 'ACKNOWLEDGED')
  // Resolve is available on every non-terminal state.
  const canResolve = onResolve !== undefined && alert.status !== 'RESOLVED'
  // Snooze: any non-RESOLVED alert (§3.1b.4).
  const canSnooze = onSnooze !== undefined && alert.status !== 'RESOLVED'
  const anyFormOpen = ackOpen || escalateOpen || resolveOpen
  const snoozedActive =
    typeof alert.snoozedUntil === 'string' &&
    alert.snoozedUntil !== '' &&
    new Date(alert.snoozedUntil).getTime() > Date.now()

  async function submitAcknowledge() {
    if (!onAcknowledge) return
    setAckSubmitting(true)
    try {
      const trimmed = ackNote.trim()
      await onAcknowledge(alert.id, trimmed === '' ? undefined : trimmed)
    } catch {
      // Parent surfaces the error; we close the form so the user can see
      // the rolled-back state and the inline error banner.
    } finally {
      setAckSubmitting(false)
      setAckOpen(false)
      setAckNote('')
    }
  }

  async function submitEscalate() {
    if (!onEscalate) return
    const trimmedReason = escalateReason.trim()
    if (trimmedReason === '') {
      // Backend rejects blank reason with HTTP 400 — surface inline first.
      setEscalateReasonError('Reason is required.')
      return
    }
    setEscalateSubmitting(true)
    const trimmedAssignee = escalateAssignee.trim()
    try {
      await onEscalate(
        alert.id,
        trimmedReason,
        trimmedAssignee === '' ? undefined : trimmedAssignee,
      )
    } catch {
      // Parent rolls back optimistic state.
    } finally {
      setEscalateSubmitting(false)
      setEscalateOpen(false)
      setEscalateReason('')
      setEscalateAssignee('')
      setEscalateReasonError(null)
    }
  }

  async function submitResolve() {
    if (!onResolve) return
    const trimmed = resolveText.trim()
    if (trimmed === '') {
      setResolveTextError('Resolution is required.')
      return
    }
    setResolveSubmitting(true)
    try {
      await onResolve(alert.id, trimmed)
    } catch {
      // Parent rolls back optimistic state.
    } finally {
      setResolveSubmitting(false)
      setResolveOpen(false)
      setResolveText('')
      setResolveTextError(null)
    }
  }

  async function applySnoozePreset(compute: (now: Date) => Date) {
    if (!onSnooze) return
    const iso = compute(new Date()).toISOString()
    setSnoozeOpen(false)
    try {
      await onSnooze(alert.id, iso)
    } catch {
      // Parent rolls back optimistic state.
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="false"
      aria-label="Alert drill-down"
      data-testid="alert-drill-down-panel"
      onKeyDown={handleKeyDown}
      className="fixed right-0 top-0 h-full w-[480px] bg-white dark:bg-slate-900 shadow-2xl border-l border-slate-200 dark:border-slate-700 z-50 flex flex-col animate-slide-in-right"
    >
      {/* Header */}
      <div className="p-4 border-b border-slate-200 dark:border-slate-700">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold text-slate-800 dark:text-slate-200">Alert Investigation</h2>
            <span
              data-testid="drill-down-status-badge"
              className={`px-1.5 py-0.5 text-xs font-semibold rounded ${
                statusBadgeClass[alert.status] ?? 'bg-slate-100 text-slate-700'
              }`}
            >
              {alert.status}
            </span>
            {snoozedActive && alert.snoozedUntil && (
              <span
                data-testid="drill-down-snoozed-until-badge"
                title={new Date(alert.snoozedUntil).toLocaleString()}
                className="inline-flex items-center gap-1 px-1.5 py-0.5 text-xs font-medium bg-amber-100 text-amber-800 rounded"
              >
                <Clock className="h-3 w-3" />
                Snoozed until {formatRelativeFuture(alert.snoozedUntil)}
              </span>
            )}
          </div>
          <button
            ref={closeRef}
            data-testid="drill-down-close"
            onClick={onClose}
            className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800"
            aria-label="Close drill-down panel"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="mt-2 text-xs text-slate-500 dark:text-slate-400 space-y-1">
          <div>Book: <span className="font-medium text-slate-700 dark:text-slate-300">{alert.bookId}</span></div>
          <div>Breach: <span className="font-medium text-red-600">{formatCurrency(breachMagnitude)}</span> over threshold of {formatCurrency(alert.threshold)}</div>
          <div>Triggered: {new Date(alert.triggeredAt).toLocaleString()}</div>
        </div>

        {!anyFormOpen && (canAcknowledge || canEscalate || canResolve || canSnooze) && (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            {canAcknowledge && (
              <Button
                data-testid="drill-down-acknowledge-btn"
                variant="primary"
                size="sm"
                icon={<Check className="h-3 w-3" />}
                onClick={() => setAckOpen(true)}
              >
                Acknowledge
              </Button>
            )}
            {canEscalate && (
              <Button
                data-testid="drill-down-escalate-btn"
                variant="secondary"
                size="sm"
                icon={<ArrowUpCircle className="h-3 w-3" />}
                onClick={() => setEscalateOpen(true)}
              >
                Escalate
              </Button>
            )}
            {canResolve && (
              <Button
                data-testid="drill-down-resolve-btn"
                variant="secondary"
                size="sm"
                icon={<CheckSquare className="h-3 w-3" />}
                onClick={() => setResolveOpen(true)}
              >
                Resolve
              </Button>
            )}
            {canSnooze && (
              <div className="relative">
                <Button
                  data-testid="drill-down-snooze-btn"
                  variant="secondary"
                  size="sm"
                  icon={<Clock className="h-3 w-3" />}
                  onClick={() => setSnoozeOpen((v) => !v)}
                  aria-haspopup="menu"
                  aria-expanded={snoozeOpen}
                >
                  Snooze
                </Button>
                {snoozeOpen && (
                  <div
                    data-testid="drill-down-snooze-popover"
                    role="menu"
                    className="absolute left-0 z-10 mt-1 w-48 rounded border border-slate-200 bg-white dark:bg-slate-800 dark:border-slate-700 shadow-lg p-1 flex flex-col gap-0.5"
                  >
                    {SNOOZE_PRESETS.map((preset) => (
                      <button
                        key={preset.id}
                        type="button"
                        role="menuitem"
                        data-testid={preset.id}
                        onClick={() => applySnoozePreset(preset.compute)}
                        className="text-left px-2 py-1 text-xs text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 rounded"
                      >
                        {preset.label}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {ackOpen && canAcknowledge && (
          <form
            data-testid="drill-down-acknowledge-form"
            onSubmit={(e) => {
              e.preventDefault()
              submitAcknowledge()
            }}
            className="mt-3 space-y-2"
          >
            <Input
              data-testid="drill-down-acknowledge-note"
              placeholder="Optional note (what did you find?)"
              value={ackNote}
              onChange={(e) => setAckNote(e.target.value)}
              className="w-full"
            />
            <div className="flex items-center gap-2">
              <Button
                data-testid="drill-down-acknowledge-submit"
                type="submit"
                variant="primary"
                size="sm"
                disabled={ackSubmitting}
              >
                Submit
              </Button>
              <Button
                data-testid="drill-down-acknowledge-cancel"
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => {
                  setAckOpen(false)
                  setAckNote('')
                }}
                disabled={ackSubmitting}
              >
                Cancel
              </Button>
            </div>
          </form>
        )}

        {escalateOpen && canEscalate && (
          <form
            data-testid="drill-down-escalate-form"
            onSubmit={(e) => {
              e.preventDefault()
              submitEscalate()
            }}
            noValidate
            className="mt-3 space-y-2"
          >
            <Input
              data-testid="drill-down-escalate-reason"
              placeholder="Reason (required)"
              value={escalateReason}
              onChange={(e) => {
                setEscalateReason(e.target.value)
                if (
                  escalateReasonError !== null &&
                  e.target.value.trim() !== ''
                ) {
                  setEscalateReasonError(null)
                }
              }}
              aria-invalid={escalateReasonError !== null ? 'true' : undefined}
              aria-describedby={
                escalateReasonError !== null
                  ? 'drill-down-escalate-reason-error'
                  : undefined
              }
              className="w-full"
            />
            {escalateReasonError !== null && (
              <p
                id="drill-down-escalate-reason-error"
                data-testid="drill-down-escalate-reason-error"
                role="alert"
                className="text-xs text-red-600 dark:text-red-400"
              >
                {escalateReasonError}
              </p>
            )}
            <Input
              data-testid="drill-down-escalate-assignee"
              placeholder="Assignee (optional)"
              value={escalateAssignee}
              onChange={(e) => setEscalateAssignee(e.target.value)}
              className="w-full"
            />
            <div className="flex items-center gap-2">
              <Button
                data-testid="drill-down-escalate-submit"
                type="submit"
                variant="primary"
                size="sm"
                disabled={escalateSubmitting}
              >
                Submit
              </Button>
              <Button
                data-testid="drill-down-escalate-cancel"
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => {
                  setEscalateOpen(false)
                  setEscalateReason('')
                  setEscalateAssignee('')
                  setEscalateReasonError(null)
                }}
                disabled={escalateSubmitting}
              >
                Cancel
              </Button>
            </div>
          </form>
        )}

        {resolveOpen && canResolve && (
          <form
            data-testid="drill-down-resolve-form"
            onSubmit={(e) => {
              e.preventDefault()
              submitResolve()
            }}
            noValidate
            className="mt-3 space-y-2"
          >
            <Input
              data-testid="drill-down-resolve-text"
              placeholder="Resolution (required)"
              value={resolveText}
              onChange={(e) => {
                setResolveText(e.target.value)
                if (
                  resolveTextError !== null &&
                  e.target.value.trim() !== ''
                ) {
                  setResolveTextError(null)
                }
              }}
              aria-invalid={resolveTextError !== null ? 'true' : undefined}
              aria-describedby={
                resolveTextError !== null
                  ? 'drill-down-resolve-text-error'
                  : undefined
              }
              className="w-full"
            />
            {resolveTextError !== null && (
              <p
                id="drill-down-resolve-text-error"
                data-testid="drill-down-resolve-text-error"
                role="alert"
                className="text-xs text-red-600 dark:text-red-400"
              >
                {resolveTextError}
              </p>
            )}
            <div className="flex items-center gap-2">
              <Button
                data-testid="drill-down-resolve-submit"
                type="submit"
                variant="primary"
                size="sm"
                disabled={resolveSubmitting}
              >
                Submit
              </Button>
              <Button
                data-testid="drill-down-resolve-cancel"
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => {
                  setResolveOpen(false)
                  setResolveText('')
                  setResolveTextError(null)
                }}
                disabled={resolveSubmitting}
              >
                Cancel
              </Button>
            </div>
          </form>
        )}
      </div>

      {/* Contributors */}
      <div className="flex-1 overflow-y-auto p-4">
        <h3 className="text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase mb-3">Top Contributors to VaR</h3>

        {loading && (
          <div className="flex items-center gap-2 text-sm text-slate-500">
            <Spinner size="sm" />
            Loading contributors...
          </div>
        )}

        {!loading && contributors.length === 0 && (
          <EmptyState
            title="No contributor data"
            description="No contributor data available for this alert."
          />
        )}

        {!loading && visible.map((c) => {
          const pct = Number(c.percentageOfTotal)
          const varAmount = Number(c.varContribution)
          return (
            <div
              key={c.instrumentId}
              data-testid={`contributor-${c.instrumentId}`}
              className="flex items-center gap-3 py-2 border-b border-slate-100 dark:border-slate-800 last:border-0"
            >
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-slate-800 dark:text-slate-200 truncate">
                  {c.instrumentName ?? c.instrumentId}
                </div>
                <div className="text-xs text-slate-500">{c.assetClass}</div>
              </div>
              <div className="w-[60px] h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                <div
                  className="h-full bg-blue-500 rounded-full"
                  style={{ width: `${Math.min(pct, 100)}%` }}
                />
              </div>
              <div className="text-xs text-slate-600 dark:text-slate-400 w-12 text-right">{pct.toFixed(1)}%</div>
              <div className="text-xs font-medium text-slate-700 dark:text-slate-300 w-20 text-right">
                {formatCurrency(varAmount)}
              </div>
            </div>
          )
        })}

        {!loading && !expanded && remaining > 0 && (
          <button
            data-testid="show-more-contributors"
            onClick={() => setExpanded(true)}
            className="mt-2 text-xs text-blue-600 hover:text-blue-800 font-medium"
          >
            {remaining} more position{remaining > 1 ? 's' : ''}
          </button>
        )}

        {/* Suggested Action */}
        {alert.suggestedAction && (
          <div data-testid="suggested-action" className="mt-4 p-3 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-800">
            <h4 className="text-xs font-semibold text-blue-800 dark:text-blue-300 uppercase mb-1">Suggested Action</h4>
            <p className="text-sm text-blue-900 dark:text-blue-200">{alert.suggestedAction}</p>
            <p className="text-xs text-blue-600 dark:text-blue-400 mt-1 italic">
              Actual VaR depends on correlations at time of execution
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
