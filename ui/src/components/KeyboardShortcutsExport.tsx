import { useCallback, useState, type KeyboardEvent } from 'react'

// Ctrl+E (or Cmd+E) exports the visible table rows to CSV (kx-lbjn).
//
// Traders use a CSV dump for quick handoffs into chat or a spreadsheet.
// Wiring a keyboard shortcut on the table itself means an analyst doesn't
// have to hunt for an Export button when their hands are already on the
// keyboard. The shortcut writes a CSV string to the clipboard rather than
// triggering a file download — copy-paste into an existing sheet is faster
// than wrangling another download.
//
// The shortcut requires exactly Ctrl OR Meta plus E — no extra modifiers —
// to avoid colliding with browser/OS combinations (e.g. Ctrl+Shift+E is a
// devtools shortcut on some browsers).

export interface ExportableRow {
  id: string
  symbol: string
  value: string
}

interface ExportableTableProps {
  rows: ExportableRow[]
}

function isExportShortcut(e: KeyboardEvent): boolean {
  if (e.key !== 'e' && e.key !== 'E') return false
  if (e.altKey || e.shiftKey) return false
  // Exactly one of Ctrl / Meta — not both, not neither.
  return e.ctrlKey !== e.metaKey
}

/**
 * Escape a CSV cell per RFC 4180: wrap in quotes when the value contains
 * a comma, a double-quote, or a newline; double-up any embedded quotes.
 */
function escapeCsvCell(value: string): string {
  if (/[",\n\r]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`
  }
  return value
}

function toCsv(rows: ExportableRow[]): string {
  const header = 'symbol,value'
  const body = rows
    .map(r => `${escapeCsvCell(r.symbol)},${escapeCsvCell(r.value)}`)
    .join('\n')
  return `${header}\n${body}`
}

export function ExportableTable({ rows }: ExportableTableProps) {
  const [announcement, setAnnouncement] = useState('')

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTableElement>) => {
      if (!isExportShortcut(e)) return
      if (rows.length === 0) return
      e.preventDefault()
      const csv = toCsv(rows)
      void navigator.clipboard?.writeText(csv)
      const noun = rows.length === 1 ? 'row' : 'rows'
      setAnnouncement(`Exported ${rows.length} ${noun} to clipboard`)
    },
    [rows],
  )

  return (
    <div>
      <table
        tabIndex={0}
        onKeyDown={handleKeyDown}
        className="w-full text-sm focus:outline focus:outline-2 focus:outline-sky-500"
      >
        <thead>
          <tr>
            <th
              scope="col"
              className="text-left py-1.5 px-2 text-xs font-medium text-slate-500"
            >
              Symbol
            </th>
            <th
              scope="col"
              className="text-right py-1.5 px-2 text-xs font-medium text-slate-500"
            >
              Value
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr key={row.id}>
              <td className="py-1.5 px-2">{row.symbol}</td>
              <td className="py-1.5 px-2 text-right">{row.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div role="status" aria-live="polite" className="sr-only">
        {announcement}
      </div>
    </div>
  )
}
