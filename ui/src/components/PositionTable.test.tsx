// Tests for the shimmer skeleton loading variant of the position table
// (kx-loox).
//
// While a fresh batch of positions is in flight (network polling, websocket
// reconnect, or page navigation that triggers a re-fetch), the position
// table should render placeholder rows that pulse to indicate work in
// progress. The accepted pattern in financial UIs is a "shimmer" — a row of
// neutral-coloured rectangles where data would normally be, with a subtle
// gradient animation. The goal is to:
//
//   1. Preserve table layout (height, column widths, scroll position) so the
//      surrounding dashboard does not jump when data arrives.
//   2. Communicate "loading" without spinning the user's eye toward a single
//      spinner, which becomes a distraction on a multi-panel screen.
//   3. Stay accessible — the loading state must be announced via
//      `aria-busy` so screen readers don't read stale or empty rows.

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import { PositionTable, type PositionTableRow } from './PositionTable'

const rows: PositionTableRow[] = [
  { instrumentId: 'AAPL', quantity: 100, marketValue: 17500 },
  { instrumentId: 'MSFT', quantity: 50, marketValue: 21500 },
]

describe('<PositionTable />', () => {
  it('renders data rows when not loading', () => {
    render(<PositionTable rows={rows} loading={false} />)
    expect(screen.getByText('AAPL')).toBeTruthy()
    expect(screen.getByText('MSFT')).toBeTruthy()
    expect(screen.queryByTestId('position-table-skeleton-row')).toBeNull()
  })

  it('renders skeleton rows when loading with no data', () => {
    render(<PositionTable rows={[]} loading={true} />)
    const skeletons = screen.getAllByTestId('position-table-skeleton-row')
    // Default skeleton count is configurable but should be at least 3 so the
    // table preserves a believable shape during the first paint.
    expect(skeletons.length).toBeGreaterThanOrEqual(3)
  })

  it('marks the table aria-busy while loading', () => {
    render(<PositionTable rows={[]} loading={true} />)
    expect(screen.getByRole('table').getAttribute('aria-busy')).toBe('true')
  })

  it('does not mark the table aria-busy once loaded', () => {
    render(<PositionTable rows={rows} loading={false} />)
    expect(screen.getByRole('table').getAttribute('aria-busy')).toBe('false')
  })

  it('keeps showing existing rows while reloading (no flash to empty)', () => {
    // When a refresh kicks off while data is already on screen, the table
    // continues to render the prior rows so the trader does not lose context.
    // A subtle aria-busy=true announces the refresh without a layout shift.
    render(<PositionTable rows={rows} loading={true} />)
    expect(screen.getByText('AAPL')).toBeTruthy()
    expect(screen.getByText('MSFT')).toBeTruthy()
    expect(screen.getByRole('table').getAttribute('aria-busy')).toBe('true')
  })

  it('renders the requested number of skeleton rows when configured', () => {
    render(
      <PositionTable rows={[]} loading={true} skeletonRowCount={7} />,
    )
    expect(screen.getAllByTestId('position-table-skeleton-row')).toHaveLength(7)
  })

  it('applies a shimmer animation class to skeleton cells', () => {
    render(<PositionTable rows={[]} loading={true} />)
    const cells = screen
      .getAllByTestId('position-table-skeleton-row')[0]
      .querySelectorAll('[data-shimmer="true"]')
    expect(cells.length).toBeGreaterThan(0)
  })

  it('shows an empty state when not loading and no rows present', () => {
    render(<PositionTable rows={[]} loading={false} />)
    expect(screen.getByText(/no positions/i)).toBeTruthy()
  })
})
