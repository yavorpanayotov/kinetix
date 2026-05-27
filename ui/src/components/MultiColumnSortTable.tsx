// Multi-column sort with Shift+click for risk dashboard tables (kx-tznd).
//
// Traders routinely need to slice rows by two dimensions at once — book first,
// then P&L within each book; counterparty first, then exposure within each
// counterparty. Single-column sort forces them to re-shuffle every refresh,
// which breaks the mental scan and makes it easy to miss outliers.
//
// The UX convention (Excel, AG Grid, MUI) is:
//   - Plain click on a header → reset to a single-column sort, ascending.
//     If the same header is plain-clicked twice, direction toggles.
//   - Shift+click on a header → add this column as a secondary (or tertiary)
//     sort. If the header is already in the sort, its direction toggles
//     in-place; its position in the priority list does not change.
//
// Each column that participates in the sort shows a `[1]`, `[2]`, ... badge
// in its header so the priority is visible. A final deterministic tiebreaker
// on `instrumentId` (always ascending) keeps row order stable across
// re-renders even when every active sort key ties.

import { useMemo, useState, type MouseEvent, type ReactNode } from 'react'

export interface MultiColumnSortRow {
  instrumentId: string
  [key: string]: unknown
}

export type SortDirection = 'asc' | 'desc'

export interface MultiColumnSortEntry<Row extends MultiColumnSortRow> {
  key: keyof Row & string
  direction: SortDirection
}

export type MultiColumnSortState<Row extends MultiColumnSortRow> =
  ReadonlyArray<MultiColumnSortEntry<Row>>

export interface MultiColumnSortColumn<Row extends MultiColumnSortRow> {
  key: keyof Row & string
  label: string
}

export interface MultiColumnSortTableProps<Row extends MultiColumnSortRow> {
  rows: Row[]
  columns: MultiColumnSortColumn<Row>[]
}

function compareValues(a: unknown, b: unknown, direction: SortDirection): number {
  const aMissing = a === undefined || a === null
  const bMissing = b === undefined || b === null
  if (aMissing && bMissing) return 0
  // Missing values sort to the bottom regardless of direction — surfacing
  // gaps at the top of risk tables would push useful data below the fold.
  if (aMissing) return 1
  if (bMissing) return -1

  let cmp: number
  if (typeof a === 'number' && typeof b === 'number') {
    cmp = a - b
  } else {
    const sa = String(a)
    const sb = String(b)
    if (sa === sb) cmp = 0
    else cmp = sa < sb ? -1 : 1
  }
  return direction === 'asc' ? cmp : -cmp
}

/**
 * Returns a new array, sorted by each sort entry in priority order, with a
 * final `instrumentId` ascending tiebreaker so renders are deterministic.
 * The input is not mutated. An empty sort returns the original order.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function applyMultiColumnSort<Row extends MultiColumnSortRow>(
  rows: Row[],
  sort: MultiColumnSortState<Row>,
): Row[] {
  const copy = rows.slice()
  if (sort.length === 0) return copy
  copy.sort((a, b) => {
    for (const entry of sort) {
      const cmp = compareValues(a[entry.key], b[entry.key], entry.direction)
      if (cmp !== 0) return cmp
    }
    // Final deterministic tiebreaker — always ascending so that flipping a
    // primary direction never reshuffles tied rows among themselves.
    if (a.instrumentId === b.instrumentId) return 0
    return a.instrumentId < b.instrumentId ? -1 : 1
  })
  return copy
}

function nextSortState<Row extends MultiColumnSortRow>(
  current: MultiColumnSortState<Row>,
  key: keyof Row & string,
  shift: boolean,
): MultiColumnSortState<Row> {
  if (!shift) {
    // Plain click — single-column sort. If we're already on this column,
    // toggle direction; otherwise reset to ascending.
    const existing = current.find((e) => e.key === key)
    const direction: SortDirection =
      existing && existing.direction === 'asc' ? 'desc' : 'asc'
    return [{ key, direction }]
  }

  // Shift+click — add a secondary sort or toggle direction in place.
  const idx = current.findIndex((e) => e.key === key)
  if (idx === -1) {
    return [...current, { key, direction: 'asc' }]
  }
  const next = current.slice()
  next[idx] = {
    key,
    direction: current[idx].direction === 'asc' ? 'desc' : 'asc',
  }
  return next
}

export function MultiColumnSortTable<Row extends MultiColumnSortRow>({
  rows,
  columns,
}: MultiColumnSortTableProps<Row>): ReactNode {
  const [sort, setSort] = useState<MultiColumnSortState<Row>>([])

  const sorted = useMemo(() => applyMultiColumnSort(rows, sort), [rows, sort])

  const onHeaderClick = (key: keyof Row & string) => (event: MouseEvent) => {
    setSort((current) => nextSortState(current, key, event.shiftKey))
  }

  const badgeFor = (key: keyof Row & string): string => {
    const idx = sort.findIndex((e) => e.key === key)
    if (idx === -1) return ''
    return ` [${idx + 1}]`
  }

  return (
    <table data-testid="multi-column-sort-table">
      <thead>
        <tr>
          {columns.map((col) => (
            <th
              key={col.key}
              scope="col"
              onClick={onHeaderClick(col.key)}
              data-sort-key={col.key}
            >
              {col.label}
              {badgeFor(col.key)}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {sorted.map((row, idx) => (
          <tr key={`${row.instrumentId}-${idx}`}>
            {columns.map((col) => (
              <td key={col.key}>{String(row[col.key] ?? '')}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
