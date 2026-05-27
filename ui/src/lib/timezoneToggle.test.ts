import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  formatTimestamp,
  useTimezoneMode,
  TIMEZONE_STORAGE_KEY,
  type TimezoneMode,
} from './timezoneToggle'

describe('formatTimestamp', () => {
  // 12:00:00 UTC on 27 May 2026 — picking a date deliberately offset from
  // most populated locales so the local-vs-UTC distinction is obvious.
  const stamp = new Date('2026-05-27T12:00:00Z')

  it('renders the UTC wall clock with a UTC suffix when mode is utc', () => {
    expect(formatTimestamp(stamp, 'utc')).toBe('2026-05-27 12:00:00 UTC')
  })

  it('renders the local wall clock with a local suffix when mode is local', () => {
    // The exact local string depends on the runner timezone; vitest config pins
    // TZ to UTC for repeatability so local == utc *value* but the suffix
    // differs. The contract we care about is: the suffix says "local" and the
    // ISO-shaped fields are present.
    const out = formatTimestamp(stamp, 'local')
    expect(out).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} local$/)
  })

  it('accepts ISO strings as input', () => {
    expect(formatTimestamp('2026-05-27T12:00:00Z', 'utc')).toBe('2026-05-27 12:00:00 UTC')
  })

  it('accepts millisecond epoch numbers as input', () => {
    expect(formatTimestamp(stamp.getTime(), 'utc')).toBe('2026-05-27 12:00:00 UTC')
  })

  it('pads single-digit fields to two characters', () => {
    expect(formatTimestamp(new Date('2026-01-03T04:05:06Z'), 'utc')).toBe(
      '2026-01-03 04:05:06 UTC',
    )
  })
})

describe('useTimezoneMode', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    window.localStorage.clear()
    vi.restoreAllMocks()
  })

  it('defaults to local when no preference is stored', () => {
    const { result } = renderHook(() => useTimezoneMode())
    expect(result.current.mode).toBe('local')
  })

  it('reads a previously persisted preference from localStorage', () => {
    window.localStorage.setItem(TIMEZONE_STORAGE_KEY, 'utc')
    const { result } = renderHook(() => useTimezoneMode())
    expect(result.current.mode).toBe('utc')
  })

  it('ignores an invalid stored value and falls back to local', () => {
    window.localStorage.setItem(TIMEZONE_STORAGE_KEY, 'mars')
    const { result } = renderHook(() => useTimezoneMode())
    expect(result.current.mode).toBe('local')
  })

  it('toggle flips local to utc and persists the new choice', () => {
    const { result } = renderHook(() => useTimezoneMode())
    act(() => result.current.toggle())
    expect(result.current.mode).toBe('utc')
    expect(window.localStorage.getItem(TIMEZONE_STORAGE_KEY)).toBe('utc')
  })

  it('toggle flips utc back to local and persists the new choice', () => {
    window.localStorage.setItem(TIMEZONE_STORAGE_KEY, 'utc')
    const { result } = renderHook(() => useTimezoneMode())
    act(() => result.current.toggle())
    expect(result.current.mode).toBe('local')
    expect(window.localStorage.getItem(TIMEZONE_STORAGE_KEY)).toBe('local')
  })

  it('setMode can jump straight to a target mode', () => {
    const { result } = renderHook(() => useTimezoneMode())
    act(() => result.current.setMode('utc' as TimezoneMode))
    expect(result.current.mode).toBe('utc')
    expect(window.localStorage.getItem(TIMEZONE_STORAGE_KEY)).toBe('utc')
  })

  it('survives a storage write failure without throwing', () => {
    const setItemSpy = vi
      .spyOn(Storage.prototype, 'setItem')
      .mockImplementation(() => {
        throw new Error('quota exceeded')
      })
    const { result } = renderHook(() => useTimezoneMode())
    expect(() => act(() => result.current.toggle())).not.toThrow()
    expect(result.current.mode).toBe('utc')
    setItemSpy.mockRestore()
  })
})
