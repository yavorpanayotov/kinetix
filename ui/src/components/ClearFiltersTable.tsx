import { useMemo, useState, type ReactNode } from 'react'

// Clear all filters button for the table toolbar (kx-x2jh).
//
// Tables in the platform tend to accumulate filters: a user narrows by symbol,
// then by desk, then by tag, gets confused about why the row count is so low,
// and has to chase every filter individually to undo. A single "Clear all
// filters" button in the toolbar wipes them in one click. The button is only
// rendered once at least one filter has departed from its default — when
// nothing is filtered there is nothing to clear, and a permanently-visible
// button just becomes visual noise.

export type FilterValue = string | number | boolean | null

export interface FilterDef<R> {
  /** Stable identifier; used as the form-control id and React key. */
  key: string
  /** Visible label for the input. Also used as the input's accessible name. */
  label: string
  /** Value treated as "no filter applied" — typically '' for text inputs. */
  defaultValue: FilterValue
  /** Predicate deciding whether a row passes this filter at the given value. */
  match: (row: R, value: FilterValue) => boolean
}

export interface ColumnDef<R> {
  key: string
  label: string
  render: (row: R) => ReactNode
}

interface ClearFiltersTableProps<R> {
  rows: R[]
  rowKey: (row: R) => string
  filters: Array<FilterDef<R>>
  columns: Array<ColumnDef<R>>
}

function buildInitialState<R>(filters: Array<FilterDef<R>>): Record<string, FilterValue> {
  const out: Record<string, FilterValue> = {}
  for (const f of filters) out[f.key] = f.defaultValue
  return out
}

export function ClearFiltersTable<R>({
  rows,
  rowKey,
  filters,
  columns,
}: ClearFiltersTableProps<R>) {
  const initial = useMemo(() => buildInitialState(filters), [filters])
  const [values, setValues] = useState<Record<string, FilterValue>>(initial)

  const activeCount = useMemo(
    () => filters.filter(f => values[f.key] !== f.defaultValue).length,
    [filters, values],
  )

  const visibleRows = useMemo(
    () =>
      rows.filter(row =>
        filters.every(f => f.match(row, values[f.key] ?? f.defaultValue)),
      ),
    [rows, filters, values],
  )

  function update(key: string, next: FilterValue) {
    setValues(prev => ({ ...prev, [key]: next }))
  }

  function clearAll() {
    setValues(buildInitialState(filters))
  }

  return (
    <div>
      <div
        role="toolbar"
        aria-label="Table filters"
        className="flex flex-wrap items-end gap-3 mb-2"
      >
        {filters.map(f => (
          <div key={f.key} className="flex flex-col">
            <label
              htmlFor={`filter-${f.key}`}
              className="text-xs font-medium text-slate-500"
            >
              {f.label}
            </label>
            <input
              id={`filter-${f.key}`}
              type="text"
              value={values[f.key] == null ? '' : String(values[f.key])}
              onChange={e => update(f.key, e.target.value)}
              className="border border-slate-200 dark:border-slate-700 rounded px-2 py-1 text-sm"
            />
          </div>
        ))}
        {activeCount > 0 && (
          <button
            type="button"
            onClick={clearAll}
            className="text-xs px-2 py-1 rounded border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800"
          >
            Clear all filters ({activeCount})
          </button>
        )}
      </div>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-slate-200 dark:border-slate-700">
            {columns.map(col => (
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
          {visibleRows.map(row => (
            <tr
              key={rowKey(row)}
              className="border-b border-slate-100 dark:border-slate-800"
            >
              {columns.map(col => (
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
