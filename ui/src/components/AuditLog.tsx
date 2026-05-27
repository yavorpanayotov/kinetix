// Compact audit-log table with a widen-range empty state (kx-9gfx).
//
// The richer `AuditLogPanel` handles fetching, cursor pagination, and chain
// verification; this lightweight component renders a pre-fetched slice with
// a deliberate empty state. When the active date filter yields no rows, the
// readout tells the user *why* the table is empty and what to do about it —
// "No events in selected range; widen date" plus the current from/to bounds
// so it is obvious which range to extend. A silent empty body invites the
// user to assume the service is broken; a hint redirects them to the
// filter.

export interface AuditLogEntry {
  id: string
  /** ISO-8601 timestamp; renders verbatim. */
  timestamp: string
  /** Event type token, e.g. TRADE_BOOKED / LIMIT_BREACH. */
  eventType: string
  /** Primary subject of the event — trade id, model name, etc. */
  subject: string
}

interface AuditLogProps {
  entries: AuditLogEntry[]
  /** Lower bound of the active date filter, e.g. "2026-05-27". */
  from: string
  /** Upper bound of the active date filter, e.g. "2026-05-27". */
  to: string
}

export function AuditLog({ entries, from, to }: AuditLogProps) {
  const isEmpty = entries.length === 0

  return (
    <div>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-200 dark:border-slate-700">
            <th
              scope="col"
              className="text-left py-1.5 px-2 text-xs font-medium text-slate-500"
            >
              When
            </th>
            <th
              scope="col"
              className="text-left py-1.5 px-2 text-xs font-medium text-slate-500"
            >
              Event
            </th>
            <th
              scope="col"
              className="text-left py-1.5 px-2 text-xs font-medium text-slate-500"
            >
              Subject
            </th>
          </tr>
        </thead>
        <tbody>
          {entries.map(entry => (
            <tr
              key={entry.id}
              className="border-b border-slate-100 dark:border-slate-800"
            >
              <td className="py-1.5 px-2 font-mono text-xs">{entry.timestamp}</td>
              <td className="py-1.5 px-2">{entry.eventType}</td>
              <td className="py-1.5 px-2">{entry.subject}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {isEmpty && (
        <div
          role="status"
          aria-live="polite"
          className="mt-3 rounded border border-dashed border-slate-300 dark:border-slate-700 p-4 text-sm text-slate-500"
        >
          No events in selected range; widen date ({from} → {to}).
        </div>
      )}
    </div>
  )
}
