import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'

// URL-persistent table — filters and sort survive reloads and links (kx-449t).
//
// Tables in this platform routinely accumulate filter state that a user wants
// to share with a colleague ("look at AAPL on the EQ desk sorted by notional").
// Holding that state in component memory only is friendly to React but hostile
// to operators: a browser refresh wipes the configuration, and a copied link
// drops the recipient into the default view. Persisting filter and sort state
// in the URL query string lets reloads and shared links restore the same view
// without coupling the table to a global store.
//
// The shape of the URL is intentionally compact and human-readable:
//   ?f.<key>=<value>&sort=<key>&dir=asc|desc
// Filters that sit at their declared default value are *not* serialised, so
// the link only ever carries the meaningful delta from the empty state.

export type FilterValue = string | number | boolean | null

export interface FilterDef<R> {
  /** Stable identifier; appears in the URL as `f.<key>`. */
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
  /** Comparable value used when this column is the active sort key. */
  sortValue?: (row: R) => string | number
}

export type SortDirection = 'asc' | 'desc'

export interface SortState {
  key: string
  direction: SortDirection
}

interface ParsedState {
  filters: Record<string, string>
  sort: SortState | null
}

interface SerialiseInput {
  filters: Record<string, FilterValue>
  sort: SortState | null
  defaults: Record<string, FilterValue>
}

/**
 * Parse the filter and sort state out of a `location.search` string. Only
 * filter keys explicitly listed in `knownFilterKeys` are returned, so a stray
 * `?f.bogus=…` in the URL is silently dropped rather than poisoning state.
 *
 * Exported so tests can pin down the URL contract independently of React.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function parseTableStateFromSearch(
  search: string,
  knownFilterKeys: string[],
): ParsedState {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)
  const filters: Record<string, string> = {}
  for (const key of knownFilterKeys) filters[key] = ''
  for (const [name, value] of params) {
    if (!name.startsWith('f.')) continue
    const key = name.slice(2)
    if (!knownFilterKeys.includes(key)) continue
    filters[key] = value
  }
  const sortKey = params.get('sort')
  const sortDir = params.get('dir')
  let sort: SortState | null = null
  if (sortKey && (sortDir === 'asc' || sortDir === 'desc')) {
    sort = { key: sortKey, direction: sortDir }
  }
  return { filters, sort }
}

/**
 * Build the `location.search` payload for a given table state. Filters at
 * their default value are omitted so the URL only carries the meaningful
 * delta. Returns the value WITHOUT a leading `?` so callers can compose it
 * trivially.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function serialiseTableStateToSearch(input: SerialiseInput): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(input.filters)) {
    const def = input.defaults[key]
    if (value === def) continue
    if (value === '' || value == null) continue
    params.set(`f.${key}`, String(value))
  }
  if (input.sort) {
    params.set('sort', input.sort.key)
    params.set('dir', input.sort.direction)
  }
  return params.toString()
}

interface UrlPersistentTableProps<R> {
  rows: R[]
  rowKey: (row: R) => string
  filters: Array<FilterDef<R>>
  columns: Array<ColumnDef<R>>
}

function buildDefaults<R>(
  filters: Array<FilterDef<R>>,
): Record<string, FilterValue> {
  const out: Record<string, FilterValue> = {}
  for (const f of filters) out[f.key] = f.defaultValue
  return out
}

export function UrlPersistentTable<R>({
  rows,
  rowKey,
  filters,
  columns,
}: UrlPersistentTableProps<R>) {
  const defaults = useMemo(() => buildDefaults(filters), [filters])
  const filterKeys = useMemo(() => filters.map(f => f.key), [filters])

  // Seed component state from the URL so a shared link restores the view.
  const initial = useMemo(
    () => parseTableStateFromSearch(window.location.search, filterKeys),
    [filterKeys],
  )
  const [values, setValues] = useState<Record<string, FilterValue>>(() => {
    const out: Record<string, FilterValue> = { ...defaults }
    for (const [k, v] of Object.entries(initial.filters)) {
      if (v !== '') out[k] = v
    }
    return out
  })
  const [sort, setSort] = useState<SortState | null>(initial.sort)

  // Mirror every state change back into the URL so reloads and copy-link
  // round-trip cleanly. We use `replaceState` rather than `pushState` to
  // avoid polluting the browser history with every keystroke in a filter.
  useEffect(() => {
    const search = serialiseTableStateToSearch({ filters: values, sort, defaults })
    const next = search ? `?${search}` : window.location.pathname
    window.history.replaceState({}, '', next)
  }, [values, sort, defaults])

  const visibleRows = useMemo(() => {
    const filtered = rows.filter(row =>
      filters.every(f => f.match(row, values[f.key] ?? f.defaultValue)),
    )
    if (!sort) return filtered
    const col = columns.find(c => c.key === sort.key)
    if (!col || !col.sortValue) return filtered
    const sortValue = col.sortValue
    const sorted = [...filtered].sort((a, b) => {
      const av = sortValue(a)
      const bv = sortValue(b)
      if (av < bv) return -1
      if (av > bv) return 1
      return 0
    })
    return sort.direction === 'desc' ? sorted.reverse() : sorted
  }, [rows, filters, values, sort, columns])

  const update = useCallback((key: string, next: FilterValue) => {
    setValues(prev => ({ ...prev, [key]: next }))
  }, [])

  const toggleSort = useCallback((key: string) => {
    setSort(prev => {
      if (!prev || prev.key !== key) return { key, direction: 'asc' }
      if (prev.direction === 'asc') return { key, direction: 'desc' }
      return null
    })
  }, [])

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
              htmlFor={`url-filter-${f.key}`}
              className="text-xs font-medium text-slate-500"
            >
              {f.label}
            </label>
            <input
              id={`url-filter-${f.key}`}
              type="text"
              value={values[f.key] == null ? '' : String(values[f.key])}
              onChange={e => update(f.key, e.target.value)}
              className="border border-slate-200 dark:border-slate-700 rounded px-2 py-1 text-sm"
            />
          </div>
        ))}
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
                {col.sortValue ? (
                  <button
                    type="button"
                    aria-label={`Sort by ${col.label}`}
                    onClick={() => toggleSort(col.key)}
                    className="inline-flex items-center gap-1 text-xs font-medium text-slate-500"
                  >
                    {col.label}
                    {sort?.key === col.key
                      ? sort.direction === 'asc'
                        ? ' ↑'
                        : ' ↓'
                      : null}
                  </button>
                ) : (
                  col.label
                )}
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
