import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { formatRelativeTime, useRelativeTime, RELATIVE_TIME_REFRESH_MS } from './relativeTime'

describe('formatRelativeTime', () => {
  const now = new Date('2026-05-27T12:00:00Z')

  it('renders "just now" for timestamps within five seconds', () => {
    expect(formatRelativeTime(new Date('2026-05-27T11:59:58Z'), now)).toBe('just now')
    expect(formatRelativeTime(new Date('2026-05-27T12:00:00Z'), now)).toBe('just now')
  })

  it('renders seconds for sub-minute deltas', () => {
    expect(formatRelativeTime(new Date('2026-05-27T11:59:30Z'), now)).toBe('30 seconds ago')
  })

  it('renders a singular minute when exactly one minute old', () => {
    expect(formatRelativeTime(new Date('2026-05-27T11:59:00Z'), now)).toBe('1 minute ago')
  })

  it('renders plural minutes for multi-minute deltas', () => {
    expect(formatRelativeTime(new Date('2026-05-27T11:57:00Z'), now)).toBe('3 minutes ago')
  })

  it('renders hours past one hour', () => {
    expect(formatRelativeTime(new Date('2026-05-27T11:00:00Z'), now)).toBe('1 hour ago')
    expect(formatRelativeTime(new Date('2026-05-27T09:00:00Z'), now)).toBe('3 hours ago')
  })

  it('renders days past one day', () => {
    expect(formatRelativeTime(new Date('2026-05-26T12:00:00Z'), now)).toBe('1 day ago')
    expect(formatRelativeTime(new Date('2026-05-24T12:00:00Z'), now)).toBe('3 days ago')
  })

  it('renders "in N seconds" for timestamps in the near future', () => {
    expect(formatRelativeTime(new Date('2026-05-27T12:00:30Z'), now)).toBe('in 30 seconds')
  })

  it('renders "in N minutes" for timestamps further in the future', () => {
    expect(formatRelativeTime(new Date('2026-05-27T12:05:00Z'), now)).toBe('in 5 minutes')
  })

  it('accepts ISO strings as input', () => {
    expect(formatRelativeTime('2026-05-27T11:59:00Z', now)).toBe('1 minute ago')
  })
})

describe('useRelativeTime', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-27T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('exposes the initial relative time on mount', () => {
    const { result } = renderHook(() =>
      useRelativeTime(new Date('2026-05-27T11:57:00Z')),
    )
    expect(result.current).toBe('3 minutes ago')
  })

  it('refreshes every ten seconds as the wall clock advances', () => {
    const start = new Date('2026-05-27T11:59:55Z')
    const { result } = renderHook(() => useRelativeTime(start))
    expect(result.current).toBe('5 seconds ago')

    act(() => {
      // `advanceTimersByTime` advances the (faked) wall clock too, so after
      // 10s we are at 12:00:10 — i.e. 15s after the input timestamp.
      vi.advanceTimersByTime(RELATIVE_TIME_REFRESH_MS)
    })
    expect(result.current).toBe('15 seconds ago')

    act(() => {
      // Another 50s of fake time → 12:01:00, one minute after the input.
      vi.advanceTimersByTime(50_000)
    })
    expect(result.current).toBe('1 minute ago')
  })

  it('uses a ten-second refresh interval', () => {
    expect(RELATIVE_TIME_REFRESH_MS).toBe(10_000)
  })

  it('clears the interval on unmount', () => {
    const clearSpy = vi.spyOn(globalThis, 'clearInterval')
    const { unmount } = renderHook(() =>
      useRelativeTime(new Date('2026-05-27T11:59:00Z')),
    )
    unmount()
    expect(clearSpy).toHaveBeenCalled()
  })
})
