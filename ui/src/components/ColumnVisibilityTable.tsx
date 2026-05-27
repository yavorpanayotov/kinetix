import { useMemo, useState, type ReactNode } from 'react'

// Column visibility toggle for optional Greeks (kx-h28m).
//
// Greek columns (Gamma / Vega / Theta / etc.) clutter the table when traders
// only want the top-line P&L drivers. This component renders a generic table
// plus a toolbar button that opens a checkbox menu of optional columns;
// required columns are never offered for hiding so the user can't be tricked
// into removing the row key.

export type GreeksColumnKey = string

export interface ColumnDef<R> {
  key: GreeksColumnKey
  label: string
  optional: boolean
  render: (row: R) => ReactNode
}

interface ColumnVisibilityTableProps<R> {
  rows: R[]
  columns: Array<ColumnDef<R>>
  rowKey: (row: R) => string
}

export function ColumnVisibilityTable<R>({
  rows,
  columns,
  rowKey,
}: ColumnVisibilityTableProps<R>) {
  const optionalColumns = useMemo(
    () => columns.filter(c => c.optional),
    [columns],
  )

  const [hidden, setHidden] = useState<ReadonlySet<GreeksColumnKey>>(() => new Set())
  const [menuOpen, setMenuOpen] = useState(false)

  const visibleColumns = columns.filter(c => !hidden.has(c.key))

  function toggleColumn(key: GreeksColumnKey) {
    setHidden(prev => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  return (
    <div>
      {optionalColumns.length > 0 && (
        <div className="flex justify-end mb-2">
          <button
            type="button"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen(open => !open)}
            className="text-xs px-2 py-1 border border-slate-300 dark:border-slate-600 rounded"
          >
            Columns
          </button>
          {menuOpen && (
            <div
              role="menu"
              aria-label="Columns"
              className="absolute mt-7 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded shadow"
            >
              {optionalColumns.map(col => {
                const visible = !hidden.has(col.key)
                return (
                  <button
                    key={col.key}
                    type="button"
                    role="menuitemcheckbox"
                    aria-checked={visible}
                    onClick={() => toggleColumn(col.key)}
                    className="block w-full text-left text-xs px-3 py-1 hover:bg-slate-100 dark:hover:bg-slate-700"
                  >
                    {col.label}
                  </button>
                )
              })}
            </div>
          )}
        </div>
      )}

      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-200 dark:border-slate-700">
            {visibleColumns.map(col => (
              <th
                key={col.key}
                scope="col"
                className="text-left py-1.5 px-2 text-xs font-medium text-slate-500"
              >
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr
              key={rowKey(row)}
              className="border-b border-slate-100 dark:border-slate-800"
            >
              {visibleColumns.map(col => (
                <td key={col.key} className="py-1.5 px-2">
                  {col.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
