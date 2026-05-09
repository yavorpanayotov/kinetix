import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchTradeHistoryPage } from '../api/tradeHistory'
import type { TradeHistoryDto } from '../types'

export interface UseTradeHistoryOptions {
  pageSize?: number
  counterpartyId?: string | null
}

export interface UseTradeHistoryResult {
  trades: TradeHistoryDto[]
  loading: boolean
  error: string | null
  refetch: () => void
  // Server-side pagination state and controls.
  total: number
  offset: number
  pageSize: number
  hasMore: boolean
  page: number
  totalPages: number
  setOffset: (offset: number) => void
  goToPage: (page: number) => void
}

const DEFAULT_PAGE_SIZE = 100

export function useTradeHistory(
  bookId: string | null,
  options: UseTradeHistoryOptions = {},
): UseTradeHistoryResult {
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE
  const counterpartyId = options.counterpartyId ?? null

  const [trades, setTrades] = useState<TradeHistoryDto[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffsetState] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const initialLoadDone = useRef(false)

  const load = useCallback(async () => {
    if (!bookId) return
    if (!initialLoadDone.current) {
      setLoading(true)
    }
    setError(null)
    try {
      const page = await fetchTradeHistoryPage(bookId, {
        offset,
        limit: pageSize,
        counterpartyId,
      })
      setTrades(page.items)
      setTotal(page.total)
      setHasMore(page.hasMore)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
      initialLoadDone.current = true
    }
  }, [bookId, offset, pageSize, counterpartyId])

  const loadRef = useRef(load)
  loadRef.current = load

  useEffect(() => {
    initialLoadDone.current = false
    loadRef.current()
  }, [bookId, offset, pageSize, counterpartyId])

  // Reset to page 0 whenever the book or counterparty filter changes; pagination
  // state is meaningless across different result sets.
  useEffect(() => {
    setOffsetState(0)
  }, [bookId, counterpartyId])

  const refetch = useCallback(() => {
    loadRef.current()
  }, [])

  const setOffset = useCallback((next: number) => {
    setOffsetState(Math.max(0, next))
  }, [])

  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  const page = Math.floor(offset / pageSize)
  const goToPage = useCallback(
    (next: number) => {
      const clamped = Math.max(0, Math.min(totalPages - 1, next))
      setOffsetState(clamped * pageSize)
    },
    [pageSize, totalPages],
  )

  return {
    trades,
    loading,
    error,
    refetch,
    total,
    offset,
    pageSize,
    hasMore,
    page,
    totalPages,
    setOffset,
    goToPage,
  }
}
