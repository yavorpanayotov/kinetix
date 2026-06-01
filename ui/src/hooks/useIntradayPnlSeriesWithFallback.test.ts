import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as intradayPnlApi from '../api/intradayPnl'
import { useIntradayPnlSeriesWithFallback } from './useIntradayPnlSeriesWithFallback'

vi.mock('../api/intradayPnl')

const mockFetchIntradayPnl = vi.mocked(intradayPnlApi.fetchIntradayPnl)

// Snapshots for "today" (2026-06-01)
const TODAY_SNAPSHOTS = [
  {
    snapshotAt: '2026-06-01T09:30:00Z',
    baseCurrency: 'USD',
    trigger: 'price_update',
    totalPnl: '2000.00',
    realisedPnl: '500.00',
    unrealisedPnl: '1500.00',
    deltaPnl: '1800.00',
    gammaPnl: '80.00',
    vegaPnl: '40.00',
    thetaPnl: '-20.00',
    rhoPnl: '10.00',
    unexplainedPnl: '90.00',
    highWaterMark: '2000.00',
  },
  {
    snapshotAt: '2026-06-01T10:30:00Z',
    baseCurrency: 'USD',
    trigger: 'price_update',
    totalPnl: '2500.00',
    realisedPnl: '700.00',
    unrealisedPnl: '1800.00',
    deltaPnl: '2200.00',
    gammaPnl: '90.00',
    vegaPnl: '50.00',
    thetaPnl: '-25.00',
    rhoPnl: '12.00',
    unexplainedPnl: '173.00',
    highWaterMark: '2500.00',
  },
]

// Snapshots for a past session (2026-05-30)
const PAST_SNAPSHOTS = [
  {
    snapshotAt: '2026-05-30T09:30:00Z',
    baseCurrency: 'USD',
    trigger: 'price_update',
    totalPnl: '1500.00',
    realisedPnl: '300.00',
    unrealisedPnl: '1200.00',
    deltaPnl: '1300.00',
    gammaPnl: '60.00',
    vegaPnl: '30.00',
    thetaPnl: '-15.00',
    rhoPnl: '8.00',
    unexplainedPnl: '117.00',
    highWaterMark: '1500.00',
  },
  {
    snapshotAt: '2026-05-30T11:00:00Z',
    baseCurrency: 'USD',
    trigger: 'price_update',
    totalPnl: '1800.00',
    realisedPnl: '400.00',
    unrealisedPnl: '1400.00',
    deltaPnl: '1600.00',
    gammaPnl: '70.00',
    vegaPnl: '35.00',
    thetaPnl: '-18.00',
    rhoPnl: '9.00',
    unexplainedPnl: '104.00',
    highWaterMark: '1800.00',
  },
]

describe('useIntradayPnlSeriesWithFallback', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('returns empty result and no fallback date when bookId is null', () => {
    const { result } = renderHook(() =>
      useIntradayPnlSeriesWithFallback(null, '2026-06-01'),
    )

    expect(result.current.snapshots).toEqual([])
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
    expect(result.current.sessionDate).toBeNull()
  })

  it('returns today snapshots and no sessionDate indicator when today has data', async () => {
    // First call: today's window → data
    mockFetchIntradayPnl.mockResolvedValueOnce({ bookId: 'book-1', snapshots: TODAY_SNAPSHOTS })

    const { result } = renderHook(() =>
      useIntradayPnlSeriesWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.snapshots).toEqual(TODAY_SNAPSHOTS)
    expect(result.current.sessionDate).toBeNull()
    expect(result.current.error).toBeNull()
    // Should only have fetched once (today's window)
    expect(mockFetchIntradayPnl).toHaveBeenCalledTimes(1)
  })

  it('falls back to the most recent day with data when today has no snapshots', async () => {
    // First call: today's window → empty
    mockFetchIntradayPnl.mockResolvedValueOnce({ bookId: 'book-1', snapshots: [] })
    // Second call: 7-day lookback → contains past session data
    mockFetchIntradayPnl.mockResolvedValueOnce({
      bookId: 'book-1',
      snapshots: PAST_SNAPSHOTS,
    })

    const { result } = renderHook(() =>
      useIntradayPnlSeriesWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    // Should show past session's snapshots
    expect(result.current.snapshots).toEqual(PAST_SNAPSHOTS)
    // sessionDate should be set to the date of the most recent snapshot
    expect(result.current.sessionDate).toBe('2026-05-30')
    expect(result.current.error).toBeNull()
  })

  it('picks the most recent day when lookback contains multiple days', async () => {
    // Today: empty
    mockFetchIntradayPnl.mockResolvedValueOnce({ bookId: 'book-1', snapshots: [] })
    // Lookback: contains May 29 and May 30 — should pick May 30
    const mixedSnapshots = [
      { ...PAST_SNAPSHOTS[0], snapshotAt: '2026-05-29T09:00:00Z' },
      { ...PAST_SNAPSHOTS[0], snapshotAt: '2026-05-30T09:30:00Z' },
      { ...PAST_SNAPSHOTS[1], snapshotAt: '2026-05-30T11:00:00Z' },
    ]
    mockFetchIntradayPnl.mockResolvedValueOnce({ bookId: 'book-1', snapshots: mixedSnapshots })

    const { result } = renderHook(() =>
      useIntradayPnlSeriesWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    // Only May 30 snapshots (2 items)
    expect(result.current.snapshots).toHaveLength(2)
    expect(result.current.snapshots[0].snapshotAt).toBe('2026-05-30T09:30:00Z')
    expect(result.current.sessionDate).toBe('2026-05-30')
  })

  it('returns empty state when lookback also has no data', async () => {
    // Both calls empty
    mockFetchIntradayPnl.mockResolvedValue({ bookId: 'book-1', snapshots: [] })

    const { result } = renderHook(() =>
      useIntradayPnlSeriesWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.snapshots).toEqual([])
    expect(result.current.sessionDate).toBeNull()
  })

  it('sets error state when fetch fails', async () => {
    mockFetchIntradayPnl.mockRejectedValueOnce(new Error('upstream failure'))

    const { result } = renderHook(() =>
      useIntradayPnlSeriesWithFallback('book-1', '2026-06-01'),
    )

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.error).toBe('upstream failure')
    expect(result.current.snapshots).toEqual([])
  })
})
