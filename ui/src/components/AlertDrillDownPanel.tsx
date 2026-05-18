import { useCallback, useEffect, useRef, useState } from 'react'
import { Check, X } from 'lucide-react'
import type { AlertEventDto } from '../types'
import { fetchAlertContributors, type PositionContributor } from '../api/alertContributors'
import { formatCurrency } from '../utils/format'
import { Button, Input } from './ui'

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
   * drill-down panel renders an inline Acknowledge form. Escalate/Resolve are
   * intentionally not yet wired — see docs/plans/ui-overhaul.md §3.1 follow-ups.
   */
  onAcknowledge?: (alertId: string, notes?: string) => Promise<void> | void
}

const statusBadgeClass: Record<string, string> = {
  TRIGGERED: 'bg-red-100 text-red-800',
  ACKNOWLEDGED: 'bg-blue-100 text-blue-800',
  ESCALATED: 'bg-orange-100 text-orange-800',
  RESOLVED: 'bg-green-100 text-green-800',
}

export function AlertDrillDownPanel({ alert, onClose, onAcknowledge }: AlertDrillDownPanelProps) {
  const { contributors, loading } = useAlertContributors(alert.id)
  const [expanded, setExpanded] = useState(false)
  const [ackOpen, setAckOpen] = useState(false)
  const [ackNote, setAckNote] = useState('')
  const [ackSubmitting, setAckSubmitting] = useState(false)
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

        {canAcknowledge && !ackOpen && (
          <div className="mt-3">
            <Button
              data-testid="drill-down-acknowledge-btn"
              variant="primary"
              size="sm"
              icon={<Check className="h-3 w-3" />}
              onClick={() => setAckOpen(true)}
            >
              Acknowledge
            </Button>
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
      </div>

      {/* Contributors */}
      <div className="flex-1 overflow-y-auto p-4">
        <h3 className="text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase mb-3">Top Contributors to VaR</h3>

        {loading && (
          <div className="text-sm text-slate-500">Loading contributors...</div>
        )}

        {!loading && contributors.length === 0 && (
          <div className="text-sm text-slate-500">No contributor data available for this alert.</div>
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
