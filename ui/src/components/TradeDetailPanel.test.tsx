import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TradeHistoryDto } from '../types'

vi.mock('../api/ghostFills', () => ({
  fetchOrderGhostFills: vi.fn().mockResolvedValue([]),
}))

import { fetchOrderGhostFills } from '../api/ghostFills'
import { TradeDetailPanel } from './TradeDetailPanel'

const mockFetchGhostFills = vi.mocked(fetchOrderGhostFills)

const filledTrade: TradeHistoryDto = {
  tradeId: 'trd-42',
  bookId: 'book-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  instrumentType: 'CASH_EQUITY',
  side: 'BUY',
  quantity: '100',
  price: { amount: '150.25', currency: 'USD' },
  tradedAt: '2025-01-15T10:00:00Z',
  status: 'LIVE',
  venue: 'NYSE',
  venueOrderId: 'VO-9001',
  counterpartyId: 'cp-goldman',
}

const cancelledTrade: TradeHistoryDto = {
  ...filledTrade,
  tradeId: 'trd-43',
  status: 'CANCELLED',
}

describe('TradeDetailPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetchGhostFills.mockResolvedValue([])
  })

  describe('identifiers block', () => {
    it('shows the trade ID with a copy button', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(screen.getByTestId('detail-trade-id')).toHaveTextContent('trd-42')
      expect(screen.getByTestId('copy-trade-id')).toBeInTheDocument()
    })

    it('copies the trade ID to the clipboard on click', () => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      Object.assign(navigator, { clipboard: { writeText } })
      render(<TradeDetailPanel trade={filledTrade} />)
      fireEvent.click(screen.getByTestId('copy-trade-id'))
      expect(writeText).toHaveBeenCalledWith('trd-42')
    })

    it('shows venue, venue order ID, book, and counterparty', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(screen.getByTestId('detail-venue')).toHaveTextContent('NYSE')
      expect(screen.getByTestId('detail-venue-order-id')).toHaveTextContent('VO-9001')
      expect(screen.getByTestId('detail-book')).toHaveTextContent('book-1')
      expect(screen.getByTestId('detail-counterparty')).toHaveTextContent('cp-goldman')
    })

    it('copies the venue order ID to the clipboard on click', () => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      Object.assign(navigator, { clipboard: { writeText } })
      render(<TradeDetailPanel trade={filledTrade} />)
      fireEvent.click(screen.getByTestId('detail-copy-venue-order-id'))
      expect(writeText).toHaveBeenCalledWith('VO-9001')
    })

    it('falls back to em dashes when venue, venue order ID, and counterparty are absent', () => {
      const bare: TradeHistoryDto = {
        ...filledTrade,
        venue: undefined,
        venueOrderId: undefined,
        counterpartyId: undefined,
      }
      render(<TradeDetailPanel trade={bare} />)
      expect(screen.getByTestId('detail-venue')).toHaveTextContent('—')
      expect(screen.getByTestId('detail-venue-order-id')).toHaveTextContent('—')
      expect(screen.getByTestId('detail-counterparty')).toHaveTextContent('—')
      expect(screen.queryByTestId('detail-copy-venue-order-id')).not.toBeInTheDocument()
    })

    it('shows asset class and instrument type', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(screen.getByTestId('detail-asset-class')).toHaveTextContent('EQUITY')
      expect(screen.getByTestId('detail-instrument-type')).toHaveTextContent('Cash Equity')
    })
  })

  describe('economics block', () => {
    it('shows quantity, filled, and open projections', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(screen.getByTestId('detail-quantity')).toHaveTextContent('100')
      expect(screen.getByTestId('detail-qty-filled')).toHaveTextContent('100')
      expect(screen.getByTestId('detail-qty-open')).toHaveTextContent('0')
    })

    it('shows the full-precision price with currency', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(screen.getByTestId('detail-price')).toHaveTextContent('$150.25')
    })

    it('shows the full (non-compact) notional', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(screen.getByTestId('detail-notional')).toHaveTextContent('$15,025.00')
    })

    it('shows the full traded-at timestamp', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(screen.getByTestId('detail-traded-at')).toHaveTextContent('2025-01-15')
    })
  })

  describe('ghost fills section', () => {
    it('renders ghost fills for terminal-status trades', () => {
      render(<TradeDetailPanel trade={cancelledTrade} />)
      expect(mockFetchGhostFills).toHaveBeenCalledWith('trd-43')
    })

    it('does not fetch ghost fills for non-terminal trades', () => {
      render(<TradeDetailPanel trade={filledTrade} />)
      expect(mockFetchGhostFills).not.toHaveBeenCalled()
    })
  })
})
