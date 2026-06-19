import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BookAggregationDto, IntradayPnlSnapshotDto } from '../../types'
import type { UseHierarchySummaryResult } from '../../hooks/useHierarchySummary'

vi.mock('../../hooks/useHierarchySummary')
vi.mock('../../hooks/useIntradayPnlStream')

import { MobilePnlView } from './MobilePnlView'
import { useHierarchySummary } from '../../hooks/useHierarchySummary'
import { useIntradayPnlStream } from '../../hooks/useIntradayPnlStream'

const mockUseHierarchySummary = vi.mocked(useHierarchySummary)
const mockUseIntradayPnlStream = vi.mocked(useIntradayPnlStream)

function bookSummary(overrides: Partial<BookAggregationDto> = {}): BookAggregationDto {
  return {
    bookId: 'book-1',
    baseCurrency: 'USD',
    totalNav: { amount: '12000000', currency: 'USD' },
    totalUnrealizedPnl: { amount: '250000', currency: 'USD' },
    currencyBreakdown: [],
    ...overrides,
  }
}

function snapshot(overrides: Partial<IntradayPnlSnapshotDto> = {}): IntradayPnlSnapshotDto {
  return {
    // Recent so the freshness banner renders fresh.
    snapshotAt: new Date().toISOString(),
    baseCurrency: 'USD',
    trigger: 'PRICE',
    totalPnl: '180000',
    realisedPnl: '0',
    unrealisedPnl: '0',
    deltaPnl: '0',
    gammaPnl: '0',
    vegaPnl: '0',
    thetaPnl: '0',
    rhoPnl: '0',
    unexplainedPnl: '0',
    highWaterMark: '0',
    ...overrides,
  }
}

function setSummary(overrides: Partial<UseHierarchySummaryResult> = {}) {
  mockUseHierarchySummary.mockReturnValue({
    summary: bookSummary(),
    baseCurrency: 'USD',
    setBaseCurrency: vi.fn(),
    loading: false,
    error: null,
    summaryLabel: 'Book Summary',
    ...overrides,
  })
}

function setStream(latest: IntradayPnlSnapshotDto | null = snapshot()) {
  mockUseIntradayPnlStream.mockReturnValue({
    snapshots: latest ? [latest] : [],
    latest,
    connected: true,
  })
}

describe('MobilePnlView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setSummary()
    setStream()
  })

  it('renders NAV, unrealised P&L, and intraday P&L when data is present', () => {
    render(<MobilePnlView bookId="book-1" />)

    expect(screen.getByTestId('mobile-pnl-view')).toBeInTheDocument()
    expect(screen.getByTestId('mobile-pnl-nav')).toHaveTextContent('$12,000,000')
    expect(screen.getByTestId('mobile-pnl-unrealised')).toHaveTextContent('$250,000')
    expect(screen.getByTestId('mobile-pnl-intraday')).toHaveTextContent('$180,000')
  })

  it('colours a positive unrealised and intraday P&L green', () => {
    setSummary({ summary: bookSummary({ totalUnrealizedPnl: { amount: '250000', currency: 'USD' } }) })
    setStream(snapshot({ totalPnl: '180000' }))

    render(<MobilePnlView bookId="book-1" />)

    expect(screen.getByTestId('mobile-pnl-unrealised').className).toContain('green')
    expect(screen.getByTestId('mobile-pnl-intraday').className).toContain('green')
  })

  it('colours a negative unrealised and intraday P&L red', () => {
    setSummary({ summary: bookSummary({ totalUnrealizedPnl: { amount: '-250000', currency: 'USD' } }) })
    setStream(snapshot({ totalPnl: '-180000' }))

    render(<MobilePnlView bookId="book-1" />)

    expect(screen.getByTestId('mobile-pnl-unrealised').className).toContain('red')
    expect(screen.getByTestId('mobile-pnl-intraday').className).toContain('red')
  })

  it('shows a loading state while the summary is loading', () => {
    setSummary({ summary: null, loading: true })

    render(<MobilePnlView bookId="book-1" />)

    expect(screen.getByTestId('mobile-pnl-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-pnl-nav')).not.toBeInTheDocument()
  })

  it('shows an empty state when there is no summary data', () => {
    setSummary({ summary: null, loading: false })

    render(<MobilePnlView bookId="book-1" />)

    expect(screen.getByTestId('mobile-pnl-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-pnl-nav')).not.toBeInTheDocument()
  })

  it('renders the freshness banner fed by the intraday snapshot timestamp', () => {
    render(<MobilePnlView bookId="book-1" />)

    expect(screen.getByTestId('mobile-freshness-banner')).toBeInTheDocument()
  })

  it('renders a static no-timestamp fallback banner when the stream has not delivered a snapshot', () => {
    // The summary DTO carries NAV and unrealised P&L but no as-of timestamp,
    // and the intraday stream has produced nothing yet. Rather than render no
    // banner at all (which would let a stale phone screen pass for live), the
    // view shows a static "no timestamp available" strip — mirroring
    // MobilePositionsView — so the headline figures never appear undated.
    setStream(null)

    render(<MobilePnlView bookId="book-1" />)

    // The live banner is absent (no timestamp to drive it)…
    expect(screen.queryByTestId('mobile-freshness-banner')).not.toBeInTheDocument()
    // …but a static fallback banner is present, not nothing.
    const fallback = screen.getByTestId('mobile-pnl-freshness')
    expect(fallback).toBeInTheDocument()
    expect(fallback).toHaveTextContent(/no timestamp available/i)
  })

  it('renders an em dash for intraday P&L when no intraday snapshot has arrived', () => {
    setStream(null)

    render(<MobilePnlView bookId="book-1" />)

    expect(screen.getByTestId('mobile-pnl-nav')).toHaveTextContent('$12,000,000')
    expect(screen.getByTestId('mobile-pnl-intraday')).toHaveTextContent('—')
  })
})
