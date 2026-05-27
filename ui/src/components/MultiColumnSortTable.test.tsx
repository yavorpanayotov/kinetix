// Tests for multi-column sort with Shift+click (kx-tznd).
//
// Risk dashboards routinely need traders to sort by two dimensions at once —
// for example, group rows by book first, then within each book order by P&L.
// A single-column sort makes them re-shuffle every time, defeating the mental
// scan. Shift+click is the established convention (Excel, AG Grid, Material
// UI) for "add this column as a secondary sort"; clicking without Shift
// resets the sort to a single column.
//
// The visible affordance is a small `[1]`, `[2]`, ... badge on each header
// that participates in the active sort, in the order they were applied.
// Clicking a header that is already in the sort toggles its direction; the
// badge stays in place so traders can see priority without losing context.

import { describe, it, expect } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'

import {
  MultiColumnSortTable,
  applyMultiColumnSort,
  type MultiColumnSortRow,
  type MultiColumnSortState,
} from './MultiColumnSortTable'

interface PositionRow extends MultiColumnSortRow {
  bookId: string
  instrumentId: string
  pnl: number
}

const rows: PositionRow[] = [
  { bookId: 'B', instrumentId: 'GOOG', pnl: 50 },
  { bookId: 'A', instrumentId: 'AAPL', pnl: 100 },
  { bookId: 'A', instrumentId: 'MSFT', pnl: 250 },
  { bookId: 'B', instrumentId: 'AMZN', pnl: 75 },
  { bookId: 'A', instrumentId: 'ZM', pnl: 50 },
]

describe('applyMultiColumnSort', () => {
  it('orders rows by the single primary key when only one sort is active', () => {
    const sort: MultiColumnSortState<PositionRow> = [
      { key: 'pnl', direction: 'asc' },
    ]
    const sorted = applyMultiColumnSort(rows, sort)
    expect(sorted.map((r) => r.pnl)).toEqual([50, 50, 75, 100, 250])
  })

  it('uses the secondary key to break ties from the primary key', () => {
    const sort: MultiColumnSortState<PositionRow> = [
      { key: 'bookId', direction: 'asc' },
      { key: 'pnl', direction: 'desc' },
    ]
    const sorted = applyMultiColumnSort(rows, sort)
    expect(sorted.map((r) => r.instrumentId)).toEqual([
      'MSFT', // A, 250
      'AAPL', // A, 100
      'ZM', //   A, 50
      'AMZN', // B, 75
      'GOOG', // B, 50
    ])
  })

  it('falls back to instrumentId ascending as a final deterministic tiebreaker', () => {
    const sort: MultiColumnSortState<PositionRow> = [
      { key: 'pnl', direction: 'asc' },
    ]
    const sorted = applyMultiColumnSort(rows, sort)
    // The two rows with pnl=50 are GOOG and ZM; instrumentId ascending puts
    // GOOG before ZM regardless of insertion order.
    const tied = sorted.filter((r) => r.pnl === 50)
    expect(tied.map((r) => r.instrumentId)).toEqual(['GOOG', 'ZM'])
  })

  it('does not mutate the input array', () => {
    const before = rows.map((r) => r.instrumentId)
    applyMultiColumnSort(rows, [{ key: 'pnl', direction: 'desc' }])
    expect(rows.map((r) => r.instrumentId)).toEqual(before)
  })

  it('returns the original row order when no sort is active', () => {
    const sorted = applyMultiColumnSort(rows, [])
    expect(sorted.map((r) => r.instrumentId)).toEqual(
      rows.map((r) => r.instrumentId),
    )
  })
})

describe('<MultiColumnSortTable />', () => {
  const columns = [
    { key: 'bookId' as const, label: 'Book' },
    { key: 'instrumentId' as const, label: 'Instrument' },
    { key: 'pnl' as const, label: 'P&L' },
  ]

  it('renders headers without sort badges before any header is clicked', () => {
    render(<MultiColumnSortTable rows={rows} columns={columns} />)
    expect(screen.queryByText('[1]')).toBeNull()
    expect(screen.queryByText('[2]')).toBeNull()
  })

  it('shows a [1] badge on the primary sort header after a plain click', () => {
    render(<MultiColumnSortTable rows={rows} columns={columns} />)
    fireEvent.click(screen.getByRole('columnheader', { name: /Book/ }))
    expect(
      screen.getByRole('columnheader', { name: /Book/ }).textContent,
    ).toContain('[1]')
    expect(screen.queryByText('[2]')).toBeNull()
  })

  it('adds a secondary sort with a [2] badge on Shift+click', () => {
    render(<MultiColumnSortTable rows={rows} columns={columns} />)
    fireEvent.click(screen.getByRole('columnheader', { name: /Book/ }))
    fireEvent.click(screen.getByRole('columnheader', { name: /P&L/ }), {
      shiftKey: true,
    })
    expect(
      screen.getByRole('columnheader', { name: /Book/ }).textContent,
    ).toContain('[1]')
    expect(
      screen.getByRole('columnheader', { name: /P&L/ }).textContent,
    ).toContain('[2]')
  })

  it('resets to a single-column sort when a header is plain-clicked', () => {
    render(<MultiColumnSortTable rows={rows} columns={columns} />)
    fireEvent.click(screen.getByRole('columnheader', { name: /Book/ }))
    fireEvent.click(screen.getByRole('columnheader', { name: /P&L/ }), {
      shiftKey: true,
    })
    // Plain-click on Instrument should drop both prior sorts.
    fireEvent.click(screen.getByRole('columnheader', { name: /Instrument/ }))
    expect(
      screen.getByRole('columnheader', { name: /Instrument/ }).textContent,
    ).toContain('[1]')
    expect(
      screen.getByRole('columnheader', { name: /Book/ }).textContent,
    ).not.toContain('[1]')
    expect(screen.queryByText('[2]')).toBeNull()
  })

  it('toggles direction when Shift+clicking a header already in the sort', () => {
    render(<MultiColumnSortTable rows={rows} columns={columns} />)
    fireEvent.click(screen.getByRole('columnheader', { name: /Book/ }))
    fireEvent.click(screen.getByRole('columnheader', { name: /P&L/ }), {
      shiftKey: true,
    })
    // Initial direction is asc; rows sorted by Book asc, P&L asc.
    const cellsBefore = screen.getAllByRole('cell')
    const instrumentsBefore = cellsBefore
      .filter((_, idx) => idx % 3 === 1)
      .map((c) => c.textContent)
    expect(instrumentsBefore).toEqual(['ZM', 'AAPL', 'MSFT', 'GOOG', 'AMZN'])

    // Shift+click P&L again — toggles its direction to desc.
    fireEvent.click(screen.getByRole('columnheader', { name: /P&L/ }), {
      shiftKey: true,
    })
    const cellsAfter = screen.getAllByRole('cell')
    const instrumentsAfter = cellsAfter
      .filter((_, idx) => idx % 3 === 1)
      .map((c) => c.textContent)
    expect(instrumentsAfter).toEqual(['MSFT', 'AAPL', 'ZM', 'AMZN', 'GOOG'])
    expect(
      screen.getByRole('columnheader', { name: /P&L/ }).textContent,
    ).toContain('[2]')
  })
})
