import { renderHook, waitFor, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as intradayVaRApi from '../api/intradayVaRTimeline'
import { useIntradayVaRTimeline } from './useIntradayVaRTimeline'

vi.mock('../api/intradayVaRTimeline')

const mockFetch = vi.mocked(intradayVaRApi.fetchIntradayVaRTimeline)

const samplePoint = {
  timestamp: '2026-03-25T09:30:00Z',
  varValue: 12500.0,
  expectedShortfall: 15000.0,
  delta: 0.65,
  gamma: null,
  vega: null,
}

const sampleAnnotation = {
  timestamp: '2026-03-25T09:15:00Z',
  instrumentId: 'AAPL',
  side: 'BUY',
  quantity: '100',
  tradeId: 'T001',
}

const sampleTimeline = {
  bookId: 'book-1',
  varPoints: [samplePoint],
  tradeAnnotations: [sampleAnnotation],
}

describe('useIntradayVaRTimeline', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns empty state and not loading when bookId is null', () => {
    const { result } = renderHook(() =>
      useIntradayVaRTimeline(null, '2026-03-25T09:00:00Z', '2026-03-25T17:00:00Z'),
    )

    expect(result.current.varPoints).toEqual([])
    expect(result.current.tradeAnnotations).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('does not call fetchIntradayVaRTimeline when bookId is null', () => {
    renderHook(() =>
      useIntradayVaRTimeline(null, '2026-03-25T09:00:00Z', '2026-03-25T17:00:00Z'),
    )

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('fetches data and returns varPoints and tradeAnnotations on success', async () => {
    mockFetch.mockResolvedValueOnce(sampleTimeline)

    const { result } = renderHook(() =>
      useIntradayVaRTimeline('book-1', '2026-03-25T09:00:00Z', '2026-03-25T17:00:00Z'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.varPoints).toHaveLength(1)
    expect(result.current.varPoints[0].varValue).toBe(12500.0)
    expect(result.current.tradeAnnotations).toHaveLength(1)
    expect(result.current.tradeAnnotations[0].tradeId).toBe('T001')
    expect(result.current.error).toBeNull()
  })

  // Plan §10.1 — the gateway's intraday VaR endpoint returns 400 "Both
  // 'from' and 'to' query parameters are required" if either is omitted.
  // Lock the hook's contract: it always passes both to the API. Catches
  // a future regression where a consumer might pass undefined under
  // strictNullChecks loopholes.
  it('always passes from AND to to the API when bookId is supplied', async () => {
    mockFetch.mockResolvedValueOnce(sampleTimeline)

    renderHook(() =>
      useIntradayVaRTimeline('book-1', '2026-03-25T09:00:00Z', '2026-03-25T17:00:00Z'),
    )

    await waitFor(() => expect(mockFetch).toHaveBeenCalled())
    const [bookIdArg, fromArg, toArg] = mockFetch.mock.calls[0]
    expect(bookIdArg).toBe('book-1')
    expect(fromArg).toBe('2026-03-25T09:00:00Z')
    expect(toArg).toBe('2026-03-25T17:00:00Z')
    expect(fromArg).toBeTruthy()
    expect(toArg).toBeTruthy()
  })

  it('sets error state when fetch fails', async () => {
    mockFetch.mockRejectedValueOnce(new Error('upstream failure'))

    const { result } = renderHook(() =>
      useIntradayVaRTimeline('book-1', '2026-03-25T09:00:00Z', '2026-03-25T17:00:00Z'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('upstream failure')
    expect(result.current.varPoints).toEqual([])
    expect(result.current.tradeAnnotations).toEqual([])
  })

  it('auto-refreshes every 60 seconds', async () => {
    vi.useFakeTimers()
    try {
      mockFetch.mockResolvedValue(sampleTimeline)

      renderHook(() =>
        useIntradayVaRTimeline('book-1', '2026-03-25T09:00:00Z', '2026-03-25T17:00:00Z'),
      )

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1)
      })
      expect(mockFetch).toHaveBeenCalledTimes(1)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(60_000)
      })
      expect(mockFetch).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})
