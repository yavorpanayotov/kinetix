import type { TradeHistoryDto } from '../types'
import { authFetch } from '../auth/authFetch'

export interface TradeHistoryPageDto {
  items: TradeHistoryDto[]
  total: number
  offset: number
  limit: number
  hasMore: boolean
}

export async function fetchTradeHistory(
  bookId: string,
): Promise<TradeHistoryDto[]> {
  const response = await authFetch(
    `/api/v1/books/${encodeURIComponent(bookId)}/trades`,
  )
  if (!response.ok) {
    throw new Error(
      `Failed to fetch trade history: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export interface FetchTradeHistoryPageOptions {
  offset?: number
  limit?: number
  counterpartyId?: string | null
}

export async function fetchTradeHistoryPage(
  bookId: string,
  { offset = 0, limit = 100, counterpartyId = null }: FetchTradeHistoryPageOptions = {},
): Promise<TradeHistoryPageDto> {
  const params = new URLSearchParams()
  params.set('offset', String(offset))
  params.set('limit', String(limit))
  if (counterpartyId) params.set('counterpartyId', counterpartyId)

  const response = await authFetch(
    `/api/v1/books/${encodeURIComponent(bookId)}/trades/page?${params.toString()}`,
  )
  if (!response.ok) {
    throw new Error(
      `Failed to fetch trade history page: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}
