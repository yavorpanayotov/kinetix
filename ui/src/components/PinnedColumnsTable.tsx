import { type ReactNode } from 'react'

// Pinnable columns that freeze the first N columns on the left (kx-ardp).
//
// Risk and P&L tables typically pin the row-identifying columns (book,
// instrument, desk) so they stay visible while the user scrolls horizontally
// through Greeks, scenario columns, or backtest buckets. This component
// renders a generic table where the first `pinnedCount` columns use sticky
// positioning so they remain in view while the rest of the row scrolls.
//
// Defaults to pinning the first three columns — the request and the most
// common usage. Consumers can override via the `pinnedCount` prop.

export type PinnedColumnKey = string

export interface PinnedColumnDef<R> {
  key: PinnedColumnKey
  label: string
  render: (row: R) => ReactNode
}

interface PinnedColumnsTableProps<R> {
  rows: R[]
  columns: Array<PinnedColumnDef<R>>
  rowKey: (row: R) => string
  /** How many columns to freeze on the left; defaults to 3. */
  pinnedCount?: number
}

/**
 * Per-column left offset for sticky positioning. Tailwind needs literal class
 * names at build time, so we map the first few indices to a fixed set rather
 * than computing pixel offsets at runtime.
 */
const PINNED_LEFT_CLASS = ['left-0', 'left-32', 'left-64', 'left-96']

export function PinnedColumnsTable<R>({
  rows,
  columns,
  rowKey,
  pinnedCount = 3,
}: PinnedColumnsTableProps<R>) {
  const effectivePinned = Math.max(0, Math.min(pinnedCount, columns.length))

  function pinnedClasses(index: number): string {
    if (index >= effectivePinned) return ''
    const leftClass = PINNED_LEFT_CLASS[index] ?? ''
    return `sticky ${leftClass} bg-white dark:bg-slate-900 z-10`
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-200 dark:border-slate-700">
            {columns.map((col, idx) => {
              const isPinned = idx < effectivePinned
              return (
                <th
                  key={col.key}
                  scope="col"
                  data-pinned={isPinned}
                  className={`text-left py-1.5 px-2 text-xs font-medium text-slate-500 ${pinnedClasses(idx)}`}
                >
                  {col.label}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr
              key={rowKey(row)}
              className="border-b border-slate-100 dark:border-slate-800"
            >
              {columns.map((col, idx) => {
                const isPinned = idx < effectivePinned
                return (
                  <td
                    key={col.key}
                    data-pinned={isPinned}
                    className={`py-1.5 px-2 ${pinnedClasses(idx)}`}
                  >
                    {col.render(row)}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
