import { describe, it, expect } from 'vitest'
import { businessDayShade, isBusinessDay } from './businessDayShading'

describe('isBusinessDay', () => {
  it('returns true for a Wednesday with no holiday configured', () => {
    // 2026-05-27 is a Wednesday.
    expect(isBusinessDay(new Date('2026-05-27T00:00:00Z'))).toBe(true)
  })

  it('returns false for a Saturday', () => {
    // 2026-05-30 is a Saturday.
    expect(isBusinessDay(new Date('2026-05-30T00:00:00Z'))).toBe(false)
  })

  it('returns false for a Sunday', () => {
    // 2026-05-31 is a Sunday.
    expect(isBusinessDay(new Date('2026-05-31T00:00:00Z'))).toBe(false)
  })

  it('returns false for a date listed in the holiday set', () => {
    const holidays = new Set(['2026-12-25'])
    // 2026-12-25 is a Friday, normally a business day.
    expect(isBusinessDay(new Date('2026-12-25T00:00:00Z'), holidays)).toBe(false)
  })

  it('treats holidays as opaque ISO date strings, not full timestamps', () => {
    const holidays = new Set(['2026-07-04'])
    // Saturday 4 July 2026 — both weekend and holiday; still non-business.
    expect(isBusinessDay(new Date('2026-07-04T12:34:56Z'), holidays)).toBe(false)
  })
})

describe('businessDayShade', () => {
  it('returns the default shade for a regular business day', () => {
    expect(businessDayShade(new Date('2026-05-27T00:00:00Z'))).toBe('bg-transparent')
  })

  it('returns the weekend shade for a Saturday', () => {
    expect(businessDayShade(new Date('2026-05-30T00:00:00Z'))).toBe('bg-slate-100')
  })

  it('returns the weekend shade for a Sunday', () => {
    expect(businessDayShade(new Date('2026-05-31T00:00:00Z'))).toBe('bg-slate-100')
  })

  it('returns the holiday shade for a configured holiday on a weekday', () => {
    const holidays = new Set(['2026-12-25'])
    expect(businessDayShade(new Date('2026-12-25T00:00:00Z'), holidays)).toBe(
      'bg-amber-50',
    )
  })

  it('prefers the holiday shade over the weekend shade when a holiday falls on a weekend', () => {
    const holidays = new Set(['2026-07-04'])
    expect(businessDayShade(new Date('2026-07-04T00:00:00Z'), holidays)).toBe(
      'bg-amber-50',
    )
  })

  it('accepts an ISO date string as input', () => {
    expect(businessDayShade('2026-05-30')).toBe('bg-slate-100')
    expect(businessDayShade('2026-05-27')).toBe('bg-transparent')
  })

  it('uses UTC day-of-week so the result is stable across local timezones', () => {
    // 2026-05-30T23:30:00Z is still Saturday UTC, even though it may render
    // as Sunday or Friday in some local zones. The helper must shade on the
    // UTC calendar day to stay consistent with date-only column data.
    expect(businessDayShade(new Date('2026-05-30T23:30:00Z'))).toBe('bg-slate-100')
  })
})
