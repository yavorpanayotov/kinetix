import { useCallback, useState, type KeyboardEvent } from 'react'

// Shift+C copies the focused cell value (kx-1njf).
//
// Traders frequently copy a single number out of a Greeks / risk table into
// a chat or another spreadsheet. Plain Ctrl/Cmd+C is reserved for native
// text-selection copy; Shift+C (no modifier other than Shift) gives a
// table-cell shortcut that bypasses selection.
//
// CopyableCellTable wires the shortcut to cells rendered as role=gridcell
// with tabIndex=0. Successful copies announce via an aria-live region so
// screen-reader users get the same feedback as sighted users.

export interface CopyableRow {
  id: string
  symbol: string
  value: string
}

interface CopyableCellTableProps {
  rows: CopyableRow[]
}

function isCopyShortcut(e: KeyboardEvent): boolean {
  if (!e.shiftKey) return false
  if (e.ctrlKey || e.metaKey || e.altKey) return false
  return e.key === 'C' || e.key === 'c'
}

export function CopyableCellTable({ rows }: CopyableCellTableProps) {
  const [announcement, setAnnouncement] = useState('')

  const handleKeyDown = useCallback((e: KeyboardEvent<HTMLTableCellElement>) => {
    if (!isCopyShortcut(e)) return
    const value = e.currentTarget.dataset.cellValue ?? e.currentTarget.textContent ?? ''
    if (!value) return
    e.preventDefault()
    // navigator.clipboard.writeText returns a promise; we ignore failures
    // here because the announcement reflects what was attempted. A real
    // production helper could surface the failure separately.
    void navigator.clipboard?.writeText(value)
    setAnnouncement(`Copied ${value} to clipboard`)
  }, [])

  return (
    <div>
      <table role="grid" className="w-full text-sm">
        <thead>
          <tr>
            <th scope="col" className="text-left py-1.5 px-2 text-xs font-medium text-slate-500">
              Symbol
            </th>
            <th scope="col" className="text-right py-1.5 px-2 text-xs font-medium text-slate-500">
              Value
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr key={row.id}>
              <td
                role="gridcell"
                tabIndex={0}
                aria-label={row.symbol}
                data-cell-value={row.symbol}
                onKeyDown={handleKeyDown}
                className="py-1.5 px-2 focus:outline focus:outline-2 focus:outline-sky-500"
              >
                {row.symbol}
              </td>
              <td
                role="gridcell"
                tabIndex={0}
                aria-label={row.value}
                data-cell-value={row.value}
                onKeyDown={handleKeyDown}
                className="py-1.5 px-2 text-right focus:outline focus:outline-2 focus:outline-sky-500"
              >
                {row.value}
              </td>
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
