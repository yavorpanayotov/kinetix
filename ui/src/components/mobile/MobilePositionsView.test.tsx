import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PositionDto } from '../../types'
import type { UsePositionsResult } from '../../hooks/usePositions'

vi.mock('../../hooks/usePositions')

import { MobilePositionsView } from './MobilePositionsView'
import { usePositions } from '../../hooks/usePositions'

const mockUsePositions = vi.mocked(usePositions)

function position(overrides: Partial<PositionDto> = {}): PositionDto {
  return {
    bookId: 'book-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    quantity: '100',
    averageCost: { amount: '150', currency: 'USD' },
    marketPrice: { amount: '170', currency: 'USD' },
    marketValue: { amount: '17000', currency: 'USD' },
    unrealizedPnl: { amount: '2000', currency: 'USD' },
    ...overrides,
  }
}

function setPositions(overrides: Partial<UsePositionsResult> = {}) {
  mockUsePositions.mockReturnValue({
    positions: [position()],
    bookId: 'book-1',
    books: ['book-1'],
    selectBook: vi.fn(),
    refreshPositions: vi.fn(),
    retryInitialLoad: vi.fn(),
    loading: false,
    error: null,
    ...overrides,
  })
}

describe('MobilePositionsView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setPositions()
  })

  it('always shows a freshness indication even though positions carry no timestamp', () => {
    render(<MobilePositionsView bookId="book-1" />)

    // The positions feed exposes no per-row or envelope as-of timestamp, so the
    // view must still communicate that — a stale list must never look identical
    // to a live one. We render a static fallback banner in the freshness slot.
    const banner = screen.getByTestId('mobile-positions-freshness')
    expect(banner).toHaveTextContent(/no timestamp available/i)
  })

  it('renders one row per position with instrument id and market value', () => {
    setPositions({
      positions: [
        position({ instrumentId: 'AAPL', marketValue: { amount: '17000', currency: 'USD' } }),
        position({ instrumentId: 'MSFT', marketValue: { amount: '25000', currency: 'USD' } }),
      ],
    })

    render(<MobilePositionsView bookId="book-1" />)

    expect(screen.getByTestId('mobile-positions-view')).toBeInTheDocument()

    const aapl = screen.getByTestId('mobile-position-row-AAPL')
    expect(within(aapl).getByText('AAPL')).toBeInTheDocument()
    expect(aapl).toHaveTextContent('$17,000')

    const msft = screen.getByTestId('mobile-position-row-MSFT')
    expect(within(msft).getByText('MSFT')).toBeInTheDocument()
    expect(msft).toHaveTextContent('$25,000')
  })

  it('sorts rows by absolute market value descending', () => {
    setPositions({
      positions: [
        position({ instrumentId: 'SMALL', marketValue: { amount: '1000', currency: 'USD' } }),
        position({ instrumentId: 'BIG', marketValue: { amount: '90000', currency: 'USD' } }),
        // Large magnitude short — absolute exposure ranks it second.
        position({ instrumentId: 'SHORT', marketValue: { amount: '-50000', currency: 'USD' } }),
      ],
    })

    render(<MobilePositionsView bookId="book-1" />)

    const rows = screen.getAllByTestId(/^mobile-position-row-/)
    const ids = rows.map((r) => r.getAttribute('data-testid'))
    expect(ids).toEqual([
      'mobile-position-row-BIG',
      'mobile-position-row-SHORT',
      'mobile-position-row-SMALL',
    ])
  })

  it('caps the list at the top 15 positions by absolute exposure', () => {
    const positions = Array.from({ length: 20 }, (_, i) =>
      position({
        instrumentId: `INST-${i}`,
        // Decreasing exposure: INST-0 largest, INST-19 smallest.
        marketValue: { amount: String((20 - i) * 1000), currency: 'USD' },
      }),
    )
    setPositions({ positions })

    render(<MobilePositionsView bookId="book-1" />)

    const rows = screen.getAllByTestId(/^mobile-position-row-/)
    expect(rows).toHaveLength(15)
    // The 15 largest survive; the 5 smallest are dropped.
    expect(screen.getByTestId('mobile-position-row-INST-0')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-position-row-INST-19')).not.toBeInTheDocument()
  })

  it('shows a truncation footer naming the cap when more positions than the cap are provided', () => {
    const positions = Array.from({ length: 20 }, (_, i) =>
      position({
        instrumentId: `INST-${i}`,
        marketValue: { amount: String((20 - i) * 1000), currency: 'USD' },
      }),
    )
    setPositions({ positions })

    render(<MobilePositionsView bookId="book-1" />)

    // A trader must never believe a capped list is the whole book. When the
    // underlying count exceeds the cap, a muted footer names the cap and points
    // to the desktop blotter for the full set.
    const footer = screen.getByTestId('mobile-positions-truncation')
    expect(footer).toHaveTextContent(/15/)
    expect(footer).toHaveTextContent(/desktop/i)
  })

  it('renders no truncation footer when the position count is at or below the cap', () => {
    const positions = Array.from({ length: 15 }, (_, i) =>
      position({
        instrumentId: `INST-${i}`,
        marketValue: { amount: String((15 - i) * 1000), currency: 'USD' },
      }),
    )
    setPositions({ positions })

    render(<MobilePositionsView bookId="book-1" />)

    expect(screen.queryByTestId('mobile-positions-truncation')).not.toBeInTheDocument()
  })

  it('colours unrealised P&L green when positive and red when negative', () => {
    setPositions({
      positions: [
        position({ instrumentId: 'WIN', unrealizedPnl: { amount: '2000', currency: 'USD' } }),
        position({ instrumentId: 'LOSE', unrealizedPnl: { amount: '-3000', currency: 'USD' } }),
      ],
    })

    render(<MobilePositionsView bookId="book-1" />)

    expect(screen.getByTestId('mobile-position-pnl-WIN').className).toContain('green')
    expect(screen.getByTestId('mobile-position-pnl-LOSE').className).toContain('red')
  })

  it('gives rows raised-contrast edges in dark mode so the list does not read as one block', () => {
    render(<MobilePositionsView bookId="book-1" />)

    // In dark mode the row cards sit on the surface-900 page background. A
    // dim border/fill makes adjacent rows bleed together. Raise the edge
    // contrast: a brighter border (slate-600) and a lighter fill (surface-700,
    // a DEFINED shade) keep each row visually distinct.
    const row = screen.getByTestId('mobile-position-row-AAPL')
    expect(row.className).toContain('dark:border-slate-600')
    expect(row.className).toContain('dark:bg-surface-700')
  })

  it('shows a loading state while positions are loading', () => {
    setPositions({ positions: [], loading: true })

    render(<MobilePositionsView bookId="book-1" />)

    expect(screen.getByTestId('mobile-positions-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-positions-view')).not.toBeInTheDocument()
  })

  it('shows an empty state when there are no positions', () => {
    setPositions({ positions: [], loading: false })

    render(<MobilePositionsView bookId="book-1" />)

    expect(screen.getByTestId('mobile-positions-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-positions-view')).not.toBeInTheDocument()
  })
})
