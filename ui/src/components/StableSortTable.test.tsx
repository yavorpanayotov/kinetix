// Tests for the stable-sort tiebreaker that orders rows with equal primary
// keys by instrumentId so display order is deterministic (kx-ixpt).
//
// Without an explicit tiebreaker, `Array.prototype.sort` is only specified to
// be stable since ES2019, but the *input* order is implementation-dependent
// when rows arrive from network paging, websocket updates, or React batches.
// Two snapshots of the same data with equal primary sort keys can flip back
// and forth between renders, which makes screenshots brittle and confuses
// traders who rely on row position to track positions across refreshes.
//
// `sortRowsByKeyThenInstrumentId` accepts a list of rows, a primary key, and
// a direction, and produces a new list ordered first by the primary key
// (asc/desc) and then by `instrumentId` ascending lexicographically. The
// tiebreaker is *always* ascending, regardless of primary direction — the
// goal is determinism, not configurability.

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import {
  StableSortTable,
  sortRowsByKeyThenInstrumentId,
  type StableSortRow,
} from './StableSortTable'

interface PositionRow extends StableSortRow {
  bookId: string
  instrumentId: string
  pnl: number
}

const rows: PositionRow[] = [
  { bookId: 'A', instrumentId: 'MSFT', pnl: 100 },
  { bookId: 'A', instrumentId: 'AAPL', pnl: 100 },
  { bookId: 'B', instrumentId: 'GOOG', pnl: 50 },
  { bookId: 'A', instrumentId: 'ZM', pnl: 100 },
]

describe('sortRowsByKeyThenInstrumentId', () => {
  it('orders by the primary key first, ascending by default', () => {
    const sorted = sortRowsByKeyThenInstrumentId(rows, 'pnl', 'asc')
    expect(sorted.map((r) => r.pnl)).toEqual([50, 100, 100, 100])
  })

  it('breaks ties by instrumentId ascending so order is deterministic', () => {
    const sorted = sortRowsByKeyThenInstrumentId(rows, 'pnl', 'asc')
    const tied = sorted.filter((r) => r.pnl === 100)
    expect(tied.map((r) => r.instrumentId)).toEqual(['AAPL', 'MSFT', 'ZM'])
  })

  it('keeps the tiebreaker ascending even when the primary is descending', () => {
    const sorted = sortRowsByKeyThenInstrumentId(rows, 'pnl', 'desc')
    expect(sorted.map((r) => r.pnl)).toEqual([100, 100, 100, 50])
    const tied = sorted.filter((r) => r.pnl === 100)
    expect(tied.map((r) => r.instrumentId)).toEqual(['AAPL', 'MSFT', 'ZM'])
  })

  it('produces the same order across repeated calls with shuffled input', () => {
    const shuffled = [rows[3], rows[1], rows[2], rows[0]]
    const a = sortRowsByKeyThenInstrumentId(rows, 'pnl', 'asc')
    const b = sortRowsByKeyThenInstrumentId(shuffled, 'pnl', 'asc')
    expect(a.map((r) => r.instrumentId)).toEqual(b.map((r) => r.instrumentId))
  })

  it('does not mutate the input array', () => {
    const before = rows.map((r) => r.instrumentId)
    sortRowsByKeyThenInstrumentId(rows, 'pnl', 'desc')
    const after = rows.map((r) => r.instrumentId)
    expect(after).toEqual(before)
  })

  it('falls back to instrumentId alone when the primary key is missing on a row', () => {
    interface MaybeRow extends StableSortRow {
      instrumentId: string
      pnl?: number
    }
    const mixed: MaybeRow[] = [
      { instrumentId: 'MSFT' },
      { instrumentId: 'AAPL', pnl: 5 },
    ]
    const sorted = sortRowsByKeyThenInstrumentId(mixed, 'pnl', 'asc')
    // Rows without the key sort after rows that have it, then by instrumentId.
    expect(sorted.map((r) => r.instrumentId)).toEqual(['AAPL', 'MSFT'])
  })

  it('compares instrumentIds lexicographically regardless of length', () => {
    const longShort: PositionRow[] = [
      { bookId: 'X', instrumentId: 'AB', pnl: 1 },
      { bookId: 'X', instrumentId: 'A', pnl: 1 },
    ]
    const sorted = sortRowsByKeyThenInstrumentId(longShort, 'pnl', 'asc')
    expect(sorted.map((r) => r.instrumentId)).toEqual(['A', 'AB'])
  })
})

describe('<StableSortTable />', () => {
  it('renders rows in stable sort order using the tiebreaker', () => {
    render(
      <StableSortTable
        rows={rows}
        sortKey="pnl"
        direction="desc"
        columns={[
          { key: 'instrumentId', label: 'Instrument' },
          { key: 'pnl', label: 'P&L' },
        ]}
      />,
    )

    const cells = screen.getAllByRole('cell')
    // Cells alternate instrumentId, pnl, instrumentId, pnl ... — extract every
    // other one starting at 0 to read down the instrument column in order.
    const instruments = cells
      .filter((_, idx) => idx % 2 === 0)
      .map((c) => c.textContent)
    expect(instruments).toEqual(['AAPL', 'MSFT', 'ZM', 'GOOG'])
  })
})
