// Stable-sort tiebreaker for tabular row lists (kx-ixpt).
//
// Risk dashboards re-render the same rows many times per minute — websocket
// price ticks, polling refreshes, and React's effect batching all conspire to
// reshuffle ties between paints. Because `Array.prototype.sort` is only
// guaranteed stable since ES2019 *for equal comparator results*, and because
// our inputs themselves arrive in non-deterministic order, two snapshots with
// the same numeric P&L can swap positions on screen for no apparent reason.
//
// The fix is to always supply a deterministic tiebreaker. We pick
// `instrumentId` because it is present on every row that surfaces in trader
// tables, is unique per row, and is the column traders mentally anchor on
// when scanning. The tiebreaker is *always* ascending — making it follow the
// primary direction defeats the purpose, because then "tied" rows still flip
// each time the user toggles asc/desc.

import type { ReactNode } from 'react'

export interface StableSortRow {
  instrumentId: string
  [key: string]: unknown
}

export type SortDirection = 'asc' | 'desc'

export interface StableSortTableColumn<Row extends StableSortRow> {
  key: keyof Row & string
  label: string
}

export interface StableSortTableProps<Row extends StableSortRow> {
  rows: Row[]
  sortKey: keyof Row & string
  direction: SortDirection
  columns: StableSortTableColumn<Row>[]
}

function compareInstrumentIds(a: string, b: string): number {
  if (a === b) return 0
  return a < b ? -1 : 1
}

function comparePrimary(
  a: unknown,
  b: unknown,
  direction: SortDirection,
): number {
  // Rows missing the key sort after rows that have it, regardless of
  // direction. Surfacing `undefined` at the top would push useful data below
  // the fold; pushing it to the bottom keeps the populated rows visible.
  const aMissing = a === undefined || a === null
  const bMissing = b === undefined || b === null
  if (aMissing && bMissing) return 0
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
 * Returns a new array, sorted first by `sortKey` in the given direction and
 * then by `instrumentId` ascending. The input is not mutated.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function sortRowsByKeyThenInstrumentId<Row extends StableSortRow>(
  rows: Row[],
  sortKey: keyof Row & string,
  direction: SortDirection,
): Row[] {
  const copy = rows.slice()
  copy.sort((a, b) => {
    const primary = comparePrimary(a[sortKey], b[sortKey], direction)
    if (primary !== 0) return primary
    return compareInstrumentIds(a.instrumentId, b.instrumentId)
  })
  return copy
}

export function StableSortTable<Row extends StableSortRow>({
  rows,
  sortKey,
  direction,
  columns,
}: StableSortTableProps<Row>): ReactNode {
  const sorted = sortRowsByKeyThenInstrumentId(rows, sortKey, direction)
  return (
    <table data-testid="stable-sort-table">
      <thead>
        <tr>
          {columns.map((col) => (
            <th key={col.key} scope="col">
              {col.label}
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
