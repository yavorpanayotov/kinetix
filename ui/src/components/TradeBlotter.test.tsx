import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TradeHistoryDto } from '../types'

vi.mock('../hooks/useTradeHistory')

import { useTradeHistory } from '../hooks/useTradeHistory'
import { TradeBlotter } from './TradeBlotter'

const mockUseTradeHistory = vi.mocked(useTradeHistory)

const trades: TradeHistoryDto[] = [
  {
    tradeId: 't-1',
    bookId: 'book-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    instrumentType: 'CASH_EQUITY',
    side: 'BUY',
    quantity: '100',
    price: { amount: '150.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:00:00Z',
    status: 'LIVE',
  },
  {
    tradeId: 't-2',
    bookId: 'book-1',
    instrumentId: 'MSFT',
    assetClass: 'EQUITY',
    instrumentType: 'CASH_EQUITY',
    side: 'SELL',
    quantity: '50',
    price: { amount: '300.00', currency: 'USD' },
    tradedAt: '2025-01-15T11:00:00Z',
    status: 'CANCELLED',
  },
  {
    tradeId: 't-3',
    bookId: 'book-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    instrumentType: 'CASH_EQUITY',
    side: 'BUY',
    quantity: '200',
    price: { amount: '148.00', currency: 'USD' },
    tradedAt: '2025-01-14T09:00:00Z',
    status: 'AMENDED',
  },
]

function defaultMockReturn(
  overrides?: Partial<ReturnType<typeof useTradeHistory>>,
): ReturnType<typeof useTradeHistory> {
  const tradeList = overrides?.trades ?? trades
  return {
    trades: tradeList,
    loading: false,
    error: null,
    refetch: vi.fn(),
    total: tradeList.length,
    offset: 0,
    pageSize: 50,
    hasMore: false,
    page: 0,
    totalPages: Math.max(1, Math.ceil(tradeList.length / 50)),
    setOffset: vi.fn(),
    goToPage: vi.fn(),
    ...overrides,
  }
}

function setupDefaults(overrides?: Partial<ReturnType<typeof useTradeHistory>>) {
  mockUseTradeHistory.mockReturnValue(defaultMockReturn(overrides))
}

describe('TradeBlotter', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('renders trade history table with correct columns', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    expect(screen.getByText('Time')).toBeInTheDocument()
    expect(screen.getByText('Instrument')).toBeInTheDocument()
    expect(screen.getByText('Side')).toBeInTheDocument()
    expect(screen.getByText('Qty')).toBeInTheDocument()
    expect(screen.getByText('Price')).toBeInTheDocument()
    expect(screen.getByText('Notional')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
  })

  it('renders trade rows with correct data', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    expect(screen.getByTestId('trade-row-t-1')).toBeInTheDocument()
    expect(screen.getByTestId('trade-row-t-2')).toBeInTheDocument()
    expect(screen.getByTestId('trade-row-t-3')).toBeInTheDocument()
  })

  it('shows empty state when no trades exist', () => {
    setupDefaults({ trades: [] })
    render(<TradeBlotter bookId="book-1" />)

    expect(screen.getByText('No trades to display.')).toBeInTheDocument()
  })

  it('shows loading state with a spinner', () => {
    setupDefaults({ loading: true, trades: [] })
    const { container } = render(<TradeBlotter bookId="book-1" />)

    expect(screen.getByText('Loading trades...')).toBeInTheDocument()
    // Spinner uses lucide-react's Loader2 with animate-spin
    expect(container.querySelector('.animate-spin')).toBeInTheDocument()
  })

  it('shows error state', () => {
    setupDefaults({ error: 'Failed to load', trades: [] })
    render(<TradeBlotter bookId="book-1" />)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Failed to load')
  })

  it('color-codes BUY trades in green and SELL trades in red', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    const buyRow = screen.getByTestId('trade-row-t-1')
    const sideCell = within(buyRow).getByTestId('trade-side-t-1')
    expect(sideCell.className).toContain('text-green')

    const sellRow = screen.getByTestId('trade-row-t-2')
    const sellSideCell = within(sellRow).getByTestId('trade-side-t-2')
    expect(sellSideCell.className).toContain('text-red')
  })

  it('sorts trades by time descending by default', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    const rows = screen.getAllByTestId(/^trade-row-/)
    expect(rows[0]).toHaveAttribute('data-testid', 'trade-row-t-2')
    expect(rows[1]).toHaveAttribute('data-testid', 'trade-row-t-1')
    expect(rows[2]).toHaveAttribute('data-testid', 'trade-row-t-3')
  })

  it('filters by instrument text input', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    const input = screen.getByTestId('filter-instrument')
    fireEvent.change(input, { target: { value: 'MSFT' } })

    expect(screen.getByTestId('trade-row-t-2')).toBeInTheDocument()
    expect(screen.queryByTestId('trade-row-t-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('trade-row-t-3')).not.toBeInTheDocument()
  })

  it('filters by side dropdown', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    const select = screen.getByTestId('filter-side')
    fireEvent.change(select, { target: { value: 'SELL' } })

    expect(screen.getByTestId('trade-row-t-2')).toBeInTheDocument()
    expect(screen.queryByTestId('trade-row-t-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('trade-row-t-3')).not.toBeInTheDocument()
  })

  it('renders CSV export button', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    expect(screen.getByTestId('csv-export-button')).toBeInTheDocument()
  })

  describe('initialCounterpartyFilter prop (cross-tab jump from Counterparty Risk)', () => {
    it('pre-populates the counterparty filter input from the prop', () => {
      setupDefaults()
      render(
        <TradeBlotter bookId="book-1" initialCounterpartyFilter="CP-GS" />,
      )

      const input = screen.getByTestId('filter-counterparty') as HTMLInputElement
      expect(input.value).toBe('CP-GS')
    })

    it('forwards the initial counterparty filter to useTradeHistory', () => {
      setupDefaults()
      render(
        <TradeBlotter bookId="book-1" initialCounterpartyFilter="CP-JPM" />,
      )

      // The hook is called with the counterparty id so the server filters
      // the result set — verifying call arguments asserts the wiring.
      expect(mockUseTradeHistory).toHaveBeenCalledWith(
        'book-1',
        expect.objectContaining({ counterpartyId: 'CP-JPM' }),
      )
    })

    it('leaves the counterparty filter empty when the prop is omitted', () => {
      setupDefaults()
      render(<TradeBlotter bookId="book-1" />)

      const input = screen.getByTestId('filter-counterparty') as HTMLInputElement
      expect(input.value).toBe('')
    })
  })

  it('shows the actual trade status in the status cell, not a hardcoded FILLED', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    expect(screen.getByTestId('trade-status-t-1')).toHaveTextContent('LIVE')
    expect(screen.getByTestId('trade-status-t-2')).toHaveTextContent('CANCELLED')
    expect(screen.getByTestId('trade-status-t-3')).toHaveTextContent('AMENDED')
  })

  it('falls back to LIVE status when trade has no status field', () => {
    const tradesWithoutStatus: TradeHistoryDto[] = [
      {
        tradeId: 'no-status',
        bookId: 'book-1',
        instrumentId: 'AAPL',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '100',
        price: { amount: '150.00', currency: 'USD' },
        tradedAt: '2025-01-15T10:00:00Z',
      },
    ]
    mockUseTradeHistory.mockReturnValue(defaultMockReturn({ trades: tradesWithoutStatus }))
    render(<TradeBlotter bookId="book-1" />)

    expect(screen.getByTestId('trade-status-no-status')).toHaveTextContent('LIVE')
  })

  it('displays notional value as quantity times price', () => {
    setupDefaults()
    render(<TradeBlotter bookId="book-1" />)

    const row = screen.getByTestId('trade-row-t-1')
    const notional = within(row).getByTestId('trade-notional-t-1')
    // 100 * 150.00 = 15000 -> compact: $15K
    expect(notional.textContent).toContain('$15K')
  })

  describe('instrument type filter', () => {
    const tradesWithTypes: TradeHistoryDto[] = [
      {
        tradeId: 'tx-1',
        bookId: 'book-1',
        instrumentId: 'AAPL',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '100',
        price: { amount: '150.00', currency: 'USD' },
        tradedAt: '2025-01-15T10:00:00Z',
        instrumentType: 'CASH_EQUITY',
      },
      {
        tradeId: 'tx-2',
        bookId: 'book-1',
        instrumentId: 'AAPL-OPT',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '10',
        price: { amount: '5.00', currency: 'USD' },
        tradedAt: '2025-01-15T11:00:00Z',
        instrumentType: 'EQUITY_OPTION',
      },
      {
        tradeId: 'tx-3',
        bookId: 'book-1',
        instrumentId: 'US10Y',
        assetClass: 'FIXED_INCOME',
        side: 'SELL',
        quantity: '500',
        price: { amount: '980.00', currency: 'USD' },
        tradedAt: '2025-01-15T12:00:00Z',
        instrumentType: 'GOVERNMENT_BOND',
      },
    ]

    function setupWithTypes() {
      mockUseTradeHistory.mockReturnValue(defaultMockReturn({ trades: tradesWithTypes }))
    }

    it('renders an instrument type filter dropdown', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      expect(screen.getByTestId('filter-instrument-type')).toBeInTheDocument()
    })

    it('defaults to showing all trades', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      const rows = screen.getAllByTestId(/^trade-row-/)
      expect(rows).toHaveLength(3)
    })

    it('filters trades to the selected instrument type', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      fireEvent.change(screen.getByTestId('filter-instrument-type'), { target: { value: 'EQUITY_OPTION' } })

      expect(screen.getByTestId('trade-row-tx-2')).toBeInTheDocument()
      expect(screen.queryByTestId('trade-row-tx-1')).not.toBeInTheDocument()
      expect(screen.queryByTestId('trade-row-tx-3')).not.toBeInTheDocument()
    })

    it('restores all trades when filter is reset to All', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      fireEvent.change(screen.getByTestId('filter-instrument-type'), { target: { value: 'EQUITY_OPTION' } })
      fireEvent.change(screen.getByTestId('filter-instrument-type'), { target: { value: '' } })

      const rows = screen.getAllByTestId(/^trade-row-/)
      expect(rows).toHaveLength(3)
    })

    it('works in combination with instrument text and side filters', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      fireEvent.change(screen.getByTestId('filter-instrument-type'), { target: { value: 'CASH_EQUITY' } })
      fireEvent.change(screen.getByTestId('filter-side'), { target: { value: 'BUY' } })

      expect(screen.getByTestId('trade-row-tx-1')).toBeInTheDocument()
      expect(screen.queryByTestId('trade-row-tx-2')).not.toBeInTheDocument()
      expect(screen.queryByTestId('trade-row-tx-3')).not.toBeInTheDocument()
    })

    it('renders instrument type badges in the type column', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      expect(screen.getByText('Cash Equity')).toBeInTheDocument()
      expect(screen.getByText('Equity Option')).toBeInTheDocument()
      expect(screen.getByText('Government Bond')).toBeInTheDocument()
    })

    it('shows only instrument types present in the trade data', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      const select = screen.getByTestId('filter-instrument-type')
      const optionValues = within(select).getAllByRole('option').map((o) => o.getAttribute('value'))
      expect(optionValues).toEqual(['', 'CASH_EQUITY', 'EQUITY_OPTION', 'GOVERNMENT_BOND'])
    })

    it('displays counts next to each filter option', () => {
      setupWithTypes()
      render(<TradeBlotter bookId="book-1" />)

      const select = screen.getByTestId('filter-instrument-type')
      const options = within(select).getAllByRole('option')
      expect(options[1].textContent).toBe('Cash Equity (1)')
      expect(options[2].textContent).toBe('Equity Option (1)')
      expect(options[3].textContent).toBe('Government Bond (1)')
    })

    it('hides the filter dropdown when only one instrument type exists', () => {
      const singleTypeTrades: TradeHistoryDto[] = [
        { ...tradesWithTypes[0], tradeId: 'st-1' },
        { ...tradesWithTypes[0], tradeId: 'st-2', instrumentId: 'GOOGL' },
      ]
      mockUseTradeHistory.mockReturnValue(defaultMockReturn({ trades: singleTypeTrades }))
      render(<TradeBlotter bookId="book-1" />)

      expect(screen.queryByTestId('filter-instrument-type')).not.toBeInTheDocument()
    })

    it('resets stale filter when trades change to a dataset without the selected type', () => {
      setupWithTypes()
      const { rerender } = render(<TradeBlotter bookId="book-1" />)

      fireEvent.change(screen.getByTestId('filter-instrument-type'), { target: { value: 'EQUITY_OPTION' } })
      expect(screen.getByTestId('trade-row-tx-2')).toBeInTheDocument()
      expect(screen.queryByTestId('trade-row-tx-1')).not.toBeInTheDocument()

      // Simulate book switch — new dataset has no EQUITY_OPTION
      const newTrades: TradeHistoryDto[] = [
        { ...tradesWithTypes[0], tradeId: 'nt-1' },
        { ...tradesWithTypes[2], tradeId: 'nt-2' },
      ]
      mockUseTradeHistory.mockReturnValue(defaultMockReturn({ trades: newTrades }))
      rerender(<TradeBlotter bookId="book-2" />)

      const rows = screen.getAllByTestId(/^trade-row-/)
      expect(rows).toHaveLength(2)
      expect(screen.getByTestId('filter-reset-notice')).toBeInTheDocument()
    })
  })

  describe('order status badges and Venue Order ID column (ADR-0035 phase 4)', () => {
    const phase4Trades: TradeHistoryDto[] = [
      {
        tradeId: 'order-pending',
        bookId: 'book-1',
        instrumentId: 'AAPL',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '100',
        price: { amount: '150.00', currency: 'USD' },
        tradedAt: '2026-05-08T10:00:00Z',
        status: 'PENDING',
      },
      {
        tradeId: 'order-pending-failed',
        bookId: 'book-1',
        instrumentId: 'MSFT',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '50',
        price: { amount: '300.00', currency: 'USD' },
        tradedAt: '2026-05-08T10:01:00Z',
        status: 'PENDING_FAILED',
      },
      {
        tradeId: 'order-sent',
        bookId: 'book-1',
        instrumentId: 'GOOGL',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '10',
        price: { amount: '2800.00', currency: 'USD' },
        tradedAt: '2026-05-08T10:02:00Z',
        status: 'SENT',
        venueOrderId: 'NYSE-99887766',
      },
    ]

    function setupPhase4(overrides?: Partial<ReturnType<typeof useTradeHistory>>) {
      mockUseTradeHistory.mockReturnValue(defaultMockReturn({ trades: phase4Trades, ...overrides }))
    }

    it('renders an amber badge for PENDING_FAILED orders', () => {
      setupPhase4()
      render(<TradeBlotter bookId="book-1" />)

      const badge = screen.getByTestId('trade-status-order-pending-failed')
      expect(badge).toHaveTextContent('PENDING_FAILED')
      expect(badge.className).toMatch(/amber/)
    })

    it('renders a neutral grey badge for PENDING orders', () => {
      setupPhase4()
      render(<TradeBlotter bookId="book-1" />)

      const badge = screen.getByTestId('trade-status-order-pending')
      expect(badge).toHaveTextContent('PENDING')
      expect(badge.className).toMatch(/slate|gray|grey/i)
    })

    it('shows the Venue Order ID column toggle and reveals the column when enabled', () => {
      setupPhase4()
      render(<TradeBlotter bookId="book-1" />)

      // Column hidden by default
      expect(screen.queryByText('Venue Order ID')).toBeNull()

      const toggle = screen.getByTestId('toggle-venue-order-id-column')
      fireEvent.click(toggle)

      expect(screen.getByText('Venue Order ID')).toBeInTheDocument()
      const cell = screen.getByTestId('trade-venue-order-id-order-sent')
      expect(cell).toHaveTextContent('NYSE-99887766')
      expect(cell).toHaveAttribute('aria-label', 'Venue order ID')
      expect(cell.className).toMatch(/font-mono|right/)
    })

    it('renders a clipboard-copy button per row in the Venue Order ID column', () => {
      setupPhase4()
      render(<TradeBlotter bookId="book-1" />)

      fireEvent.click(screen.getByTestId('toggle-venue-order-id-column'))

      const copyBtn = screen.getByTestId('copy-venue-order-id-order-sent')
      expect(copyBtn).toHaveAttribute('aria-label', 'Copy venue order ID')
    })
  })
})
