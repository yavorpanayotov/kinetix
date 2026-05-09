import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TradeHistoryDto } from '../types'

vi.mock('../api/tradeHistory')

import { fetchTradeHistoryPage } from '../api/tradeHistory'
import { useTradeHistory } from './useTradeHistory'

const mockFetchTradeHistoryPage = vi.mocked(fetchTradeHistoryPage)

const trade: TradeHistoryDto = {
  tradeId: 't-1',
  bookId: 'book-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '100',
  price: { amount: '150.00', currency: 'USD' },
  tradedAt: '2025-01-15T10:00:00Z',
}

function pageOf(items: TradeHistoryDto[], total = items.length, offset = 0, limit = 100) {
  return { items, total, offset, limit, hasMore: offset + items.length < total }
}

describe('useTradeHistory', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('does not fetch when bookId is null', () => {
    renderHook(() => useTradeHistory(null))

    expect(mockFetchTradeHistoryPage).not.toHaveBeenCalled()
  })

  it('fetches the first page on mount', async () => {
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([trade], 1))

    const { result } = renderHook(() => useTradeHistory('book-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.trades).toEqual([trade])
    expect(result.current.total).toBe(1)
    expect(result.current.error).toBeNull()
    expect(mockFetchTradeHistoryPage).toHaveBeenCalledWith('book-1', {
      offset: 0,
      limit: 100,
      counterpartyId: null,
    })
  })

  it('sets error on fetch failure', async () => {
    mockFetchTradeHistoryPage.mockRejectedValue(new Error('Network error'))

    const { result } = renderHook(() => useTradeHistory('book-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('Network error')
    expect(result.current.trades).toEqual([])
  })

  it('refetches when refetch is called', async () => {
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([trade], 1))

    const { result } = renderHook(() => useTradeHistory('book-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    const trade2: TradeHistoryDto = { ...trade, tradeId: 't-2' }
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([trade, trade2], 2))

    await act(async () => {
      result.current.refetch()
    })

    await waitFor(() => {
      expect(result.current.trades).toHaveLength(2)
    })
  })

  it('refetches when bookId changes', async () => {
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([trade], 1))

    const { result, rerender } = renderHook(
      ({ id }: { id: string | null }) => useTradeHistory(id),
      { initialProps: { id: 'book-1' } },
    )

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    const trade2: TradeHistoryDto = { ...trade, bookId: 'book-2', tradeId: 't-2' }
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([trade2], 1))

    rerender({ id: 'book-2' })

    await waitFor(() => {
      expect(result.current.trades).toEqual([trade2])
    })

    expect(mockFetchTradeHistoryPage).toHaveBeenCalledWith('book-2', {
      offset: 0,
      limit: 100,
      counterpartyId: null,
    })
  })

  it('forwards counterpartyId filter to the API', async () => {
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([], 0))

    renderHook(() => useTradeHistory('book-1', { counterpartyId: 'CP-GS' }))

    await waitFor(() => {
      expect(mockFetchTradeHistoryPage).toHaveBeenCalledWith('book-1', {
        offset: 0,
        limit: 100,
        counterpartyId: 'CP-GS',
      })
    })
  })

  it('paginates via goToPage and exposes page metadata', async () => {
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([trade], 250))

    const { result } = renderHook(() => useTradeHistory('book-1', { pageSize: 50 }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.totalPages).toBe(5)
    expect(result.current.page).toBe(0)

    await act(async () => {
      result.current.goToPage(2)
    })

    await waitFor(() => {
      expect(mockFetchTradeHistoryPage).toHaveBeenLastCalledWith('book-1', {
        offset: 100,
        limit: 50,
        counterpartyId: null,
      })
    })
    expect(result.current.page).toBe(2)
  })

  it('clamps goToPage within [0, totalPages-1]', async () => {
    mockFetchTradeHistoryPage.mockResolvedValue(pageOf([trade], 50))

    const { result } = renderHook(() => useTradeHistory('book-1', { pageSize: 50 }))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    await act(async () => {
      result.current.goToPage(99)
    })

    expect(result.current.page).toBe(0)
  })
})
