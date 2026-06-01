import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as intradayVaRApi from '../api/intradayVaRTimeline'
import { useIntradayVaRTimelineWithFallback } from './useIntradayVaRTimelineWithFallback'

vi.mock('../api/intradayVaRTimeline')

const mockFetch = vi.mocked(intradayVaRApi.fetchIntradayVaRTimeline)

const makePoint = (timestamp: string, varValue = 12000) => ({
  timestamp,
  varValue,
  expectedShortfall: varValue * 1.3,
  delta: 0.65,
  gamma: null,
  vega: null,
})

const TODAY_POINTS = [
  makePoint('2026-06-01T09:30:00Z', 12000),
  makePoint('2026-06-01T10:30:00Z', 12500),
]

const PAST_POINTS = [
  makePoint('2026-05-30T09:30:00Z', 11000),
  makePoint('2026-05-30T11:00:00Z', 11500),
]

const ANNOTATION = {
  timestamp: '2026-05-30T09:15:00Z',
  instrumentId: 'AAPL',
  side: 'BUY' as const,
  quantity: '100',
  tradeId: 'T001',
}

describe('useIntradayVaRTimelineWithFallback', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('returns empty state and no fallback when bookId is null', () => {
    const { result } = renderHook(() =>
      useIntradayVaRTimelineWithFallback(null, '2026-06-01'),
    )

    expect(result.current.varPoints).toEqual([])
    expect(result.current.tradeAnnotations).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.sessionDate).toBeNull()
  })

  it('returns today data without sessionDate when today has VaR points', async () => {
    mockFetch.mockResolvedValueOnce({
      bookId: 'book-1',
      varPoints: TODAY_POINTS,
      tradeAnnotations: [],
    })

    const { result } = renderHook(() =>
      useIntradayVaRTimelineWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.varPoints).toEqual(TODAY_POINTS)
    expect(result.current.sessionDate).toBeNull()
    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('falls back to the most recent day with data when today has no VaR points', async () => {
    // First: today's window → empty
    mockFetch.mockResolvedValueOnce({ bookId: 'book-1', varPoints: [], tradeAnnotations: [] })
    // Second: 7-day lookback → past session data with annotation
    mockFetch.mockResolvedValueOnce({
      bookId: 'book-1',
      varPoints: PAST_POINTS,
      tradeAnnotations: [ANNOTATION],
    })

    const { result } = renderHook(() =>
      useIntradayVaRTimelineWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.varPoints).toEqual(PAST_POINTS)
    expect(result.current.tradeAnnotations).toEqual([ANNOTATION])
    expect(result.current.sessionDate).toBe('2026-05-30')
  })

  it('picks the most recent day when lookback contains multiple days', async () => {
    mockFetch.mockResolvedValueOnce({ bookId: 'book-1', varPoints: [], tradeAnnotations: [] })
    const mixedPoints = [
      makePoint('2026-05-29T09:00:00Z', 10000),
      makePoint('2026-05-30T09:30:00Z', 11000),
      makePoint('2026-05-30T11:00:00Z', 11500),
    ]
    mockFetch.mockResolvedValueOnce({
      bookId: 'book-1',
      varPoints: mixedPoints,
      tradeAnnotations: [],
    })

    const { result } = renderHook(() =>
      useIntradayVaRTimelineWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.varPoints).toHaveLength(2)
    expect(result.current.varPoints[0].timestamp).toBe('2026-05-30T09:30:00Z')
    expect(result.current.sessionDate).toBe('2026-05-30')
  })

  it('returns empty state with no sessionDate when lookback also has no data', async () => {
    mockFetch.mockResolvedValue({
      bookId: 'book-1',
      varPoints: [],
      tradeAnnotations: [],
    })

    const { result } = renderHook(() =>
      useIntradayVaRTimelineWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.varPoints).toEqual([])
    expect(result.current.sessionDate).toBeNull()
  })

  it('sets error state when fetch fails', async () => {
    mockFetch.mockRejectedValueOnce(new Error('service unavailable'))

    const { result } = renderHook(() =>
      useIntradayVaRTimelineWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.error).toBe('service unavailable')
    expect(result.current.varPoints).toEqual([])
  })
})
