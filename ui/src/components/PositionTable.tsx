// Position table with shimmer skeleton loading variant (kx-loox).
//
// Trading dashboards refetch positions on every market tick, websocket
// reconnect, or tab switch. Replacing the table with a centred spinner makes
// the entire dashboard jump and pulls the eye away from neighbouring panels.
// A shimmer skeleton — placeholder rectangles with a subtle pulse animation
// in the spot where data would normally sit — keeps the layout stable, hints
// at activity in the peripheral vision, and signals "more rows are coming"
// without the heavy-handed spinner.
//
// Behaviour notes:
//   - When `loading` is true and the table has no prior rows, render N
//     skeleton rows (default 5) so the table reserves vertical space.
//   - When `loading` is true *and* prior rows exist, keep showing them and
//     just flip `aria-busy=true`. This avoids flashing to empty during
//     refreshes — traders rely on row position to track positions across
//     ticks (see kx-ixpt / StableSortTable).
//   - The shimmer effect is delivered via the `data-shimmer="true"` attribute
//     so CSS can target it without leaking styling into this file.

import type { ReactNode } from 'react'

export interface PositionTableRow {
  instrumentId: string
  quantity: number
  marketValue: number
}

export interface PositionTableProps {
  rows: PositionTableRow[]
  loading: boolean
  /** Number of placeholder rows to render while loading with no data. */
  skeletonRowCount?: number
}

const DEFAULT_SKELETON_ROW_COUNT = 5

function SkeletonRow(): ReactNode {
  return (
    <tr data-testid="position-table-skeleton-row" aria-hidden="true">
      <td>
        <span
          data-shimmer="true"
          className="position-table-shimmer position-table-shimmer--text"
        />
      </td>
      <td>
        <span
          data-shimmer="true"
          className="position-table-shimmer position-table-shimmer--num"
        />
      </td>
      <td>
        <span
          data-shimmer="true"
          className="position-table-shimmer position-table-shimmer--num"
        />
      </td>
    </tr>
  )
}

export function PositionTable({
  rows,
  loading,
  skeletonRowCount = DEFAULT_SKELETON_ROW_COUNT,
}: PositionTableProps): ReactNode {
  const showSkeletonRows = loading && rows.length === 0
  const showEmptyState = !loading && rows.length === 0

  return (
    <table aria-busy={loading} className="position-table">
      <thead>
        <tr>
          <th scope="col">Instrument</th>
          <th scope="col">Quantity</th>
          <th scope="col">Market value</th>
        </tr>
      </thead>
      <tbody>
        {showSkeletonRows
          ? Array.from({ length: skeletonRowCount }, (_, idx) => (
              <SkeletonRow key={`skeleton-${idx}`} />
            ))
          : rows.map((row) => (
              <tr key={row.instrumentId}>
                <td>{row.instrumentId}</td>
                <td>{row.quantity}</td>
                <td>{row.marketValue}</td>
              </tr>
            ))}
        {showEmptyState ? (
          <tr>
            <td colSpan={3}>No positions to display</td>
          </tr>
        ) : null}
      </tbody>
    </table>
  )
}
