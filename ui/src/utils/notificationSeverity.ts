/**
 * Severity vocabulary shared by the copilot notification surface.
 *
 * <NotificationStrip> (plan §6.9) and the intraday-push row
 * <IntradayPushItem> (plan §7.9) both colour rows by severity. The
 * type, the colour maps, and the wire-string narrowing live here — in a
 * plain (non-component) module — so both component files import the
 * *same* mapping rather than each defining a parallel one. Keeping them
 * out of a component file also satisfies the `react-refresh` fast-refresh
 * rule, which forbids component files from exporting runtime values.
 */

/** The three severities a copilot notification or intraday push can carry. */
export type NotificationSeverity = 'info' | 'warning' | 'critical'

/** Severities rendered in priority order for chips and the dot legend. */
export const SEVERITY_ORDER: NotificationSeverity[] = [
  'critical',
  'warning',
  'info',
]

/** Chip colour classes keyed by severity — class-substring assertions rely on these. */
export const CHIP_CLASS: Record<NotificationSeverity, string> = {
  critical: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300',
  warning: 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  info: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
}

/** Severity dot colour classes for individual inbox rows. */
export const DOT_CLASS: Record<NotificationSeverity, string> = {
  critical: 'bg-red-500',
  warning: 'bg-amber-500',
  info: 'bg-slate-400',
}

/**
 * Narrow a free-form ``severity`` string (the wire shape of an intraday
 * push, which the gateway forwards verbatim from the Python
 * ``IntradayPush`` model) to a {@link NotificationSeverity}. Intraday
 * pushes only ever carry ``"warning"`` or ``"critical"`` today, but the
 * field is wire-typed as ``string`` — anything else degrades gracefully
 * to ``info`` so an unexpected value still renders rather than crashing.
 */
export function pushSeverity(severity: string): NotificationSeverity {
  if (severity === 'critical') return 'critical'
  if (severity === 'warning') return 'warning'
  return 'info'
}
