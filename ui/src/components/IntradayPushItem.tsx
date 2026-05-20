import { Zap } from 'lucide-react'
import { formatRelativeTime } from '../utils/format'
import type { CopilotPushEvent } from '../api/copilot'
import { DOT_CLASS, pushSeverity } from '../utils/notificationSeverity'

/**
 * Plan §7.9 — a single intraday Copilot push event rendered as an inbox
 * row inside <NotificationStrip>.
 *
 * Intraday pushes arrive over the ``/ws/copilot`` WebSocket channel
 * (PR 7 / ADR-0036) and are surfaced by ``useCopilotWebSocket()``. They
 * differ from the caller-owned ``NotificationItem``s the strip already
 * renders: a push has no dismiss affordance (it is a transient stream
 * entry, not an inbox item) and is marked with a `Zap` icon so a trader
 * can tell a live intraday trigger apart from a standing notification at
 * a glance.
 *
 * The row reuses the shared severity → colour mapping
 * (``DOT_CLASS``/``pushSeverity`` in ``../utils/notificationSeverity``);
 * the push wire field is a free-form ``string`` (the gateway forwards
 * ``"warning"`` / ``"critical"`` from the Python ``IntradayPush`` model
 * verbatim), so ``pushSeverity`` narrows it and falls back to ``info``
 * for anything unrecognised.
 */

export interface IntradayPushItemProps {
  push: CopilotPushEvent
}

export function IntradayPushItem({ push }: IntradayPushItemProps) {
  const severity = pushSeverity(push.severity)
  return (
    <li
      data-testid={`intraday-push-item-${push.session_id}`}
      className="flex items-start gap-2.5 px-3 py-2 border-b border-slate-100 dark:border-surface-700 last:border-b-0"
    >
      <span
        data-testid="intraday-push-severity-dot"
        className={`mt-1 h-2 w-2 flex-shrink-0 rounded-full ${DOT_CLASS[severity]}`}
        aria-hidden="true"
      />
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <span className="flex min-w-0 items-baseline gap-1.5">
            <Zap
              data-testid="intraday-push-zap-icon"
              className="h-3 w-3 flex-shrink-0 self-center text-amber-500"
              aria-hidden="true"
            />
            <span className="truncate text-sm font-medium text-slate-800 dark:text-slate-100">
              {push.headline}
            </span>
          </span>
          <span
            data-testid="intraday-push-time"
            className="flex-shrink-0 text-[10px] font-mono text-slate-400 dark:text-slate-500"
          >
            {formatRelativeTime(push.generated_at)}
          </span>
        </div>
        {push.context_bullets.length > 0 && (
          <p className="mt-0.5 text-xs text-slate-600 dark:text-slate-300">
            {push.context_bullets[0]}
          </p>
        )}
        <span className="mt-0.5 inline-block text-[10px] uppercase tracking-wide text-slate-400 dark:text-slate-500">
          Intraday push
        </span>
      </div>
    </li>
  )
}
