import { describe, expect, it, vi, afterEach } from 'vitest'
import { formatMoney, formatSignedMoney, formatCurrency, formatQuantity, formatRelativeTime, formatTimestamp, formatTimeOnly, formatChartTime, formatDuration, formatNum, formatSignedNum, formatCompactNum, formatPctOfTotal, isOlderThanMinutes, pnlColorClass } from './format'

describe('formatMoney', () => {
  it('formats USD with dollar sign and commas', () => {
    expect(formatMoney('1500.00', 'USD')).toBe('$1,500.00')
  })

  it('formats EUR with euro sign', () => {
    expect(formatMoney('2500.50', 'EUR')).toBe('\u20ac2,500.50')
  })

  it('formats negative amounts', () => {
    expect(formatMoney('-1234.56', 'USD')).toBe('-$1,234.56')
  })

  it('formats large numbers with thousands separators', () => {
    expect(formatMoney('1234567.89', 'USD')).toBe('$1,234,567.89')
  })

  it('falls back to amount + currency code for unknown currencies', () => {
    expect(formatMoney('100.00', 'XYZ')).toBe('100.00 XYZ')
  })

  it('rounds amounts with excessive decimal places', () => {
    expect(formatMoney('28387.500000000000000000000000', 'USD')).toBe('$28,387.50')
  })

  it('rounds to 2 decimal places', () => {
    expect(formatMoney('150.999', 'USD')).toBe('$151.00')
  })

  // Phase 2.5.1 (kx-cm3) — distinguish "no data yet" from a genuine zero so
  // the firm KPI bar never displays a misleading `$0.00` while the firm
  // aggregate endpoint is still bootstrapping.
  it('renders an em-dash for a null amount', () => {
    expect(formatMoney(null as unknown as string, 'USD')).toBe('—')
  })

  it('renders an em-dash for an undefined amount', () => {
    expect(formatMoney(undefined as unknown as string, 'USD')).toBe('—')
  })

  it('renders $0.00 for a genuine zero (not null)', () => {
    expect(formatMoney('0', 'USD')).toBe('$0.00')
    expect(formatMoney('0.00', 'USD')).toBe('$0.00')
  })
})

describe('formatSignedMoney', () => {
  it('prefixes + on positive USD amounts', () => {
    expect(formatSignedMoney('1500.00', 'USD')).toBe('+$1,500.00')
  })

  it('prefixes + on positive EUR amounts', () => {
    expect(formatSignedMoney('2500.50', 'EUR')).toBe('+€2,500.50')
  })

  it('leaves the native minus sign on negative amounts', () => {
    expect(formatSignedMoney('-1234.56', 'USD')).toBe('-$1,234.56')
  })

  it('does not prefix + on zero', () => {
    expect(formatSignedMoney('0.00', 'USD')).toBe('$0.00')
  })

  it('does not prefix + on negative zero', () => {
    expect(formatSignedMoney('-0', 'USD')).toBe(formatMoney('-0', 'USD'))
    expect(formatSignedMoney('-0', 'USD')).not.toMatch(/^\+/)
  })

  it('does not prefix + when the value rounds to zero', () => {
    expect(formatSignedMoney('0.001', 'USD')).toBe('$0.00')
  })

  it('prefixes + when the value rounds up to a positive cent', () => {
    expect(formatSignedMoney('0.005', 'USD')).toBe('+$0.01')
  })

  it('falls back to the formatMoney unknown-currency form without a + prefix', () => {
    expect(formatSignedMoney('100.00', 'XYZ')).toBe('100.00 XYZ')
  })

  it('does not prefix + on NaN inputs', () => {
    expect(formatSignedMoney('not-a-number', 'USD')).toBe(formatMoney('not-a-number', 'USD'))
    expect(formatSignedMoney('not-a-number', 'USD')).not.toMatch(/^\+/)
  })

  it('does not prefix + on +Infinity inputs', () => {
    expect(formatSignedMoney('Infinity', 'USD')).toBe(formatMoney('Infinity', 'USD'))
    expect(formatSignedMoney('Infinity', 'USD')).not.toMatch(/^\+/)
  })

  it('does not prefix + on -Infinity inputs', () => {
    expect(formatSignedMoney('-Infinity', 'USD')).toBe(formatMoney('-Infinity', 'USD'))
  })

  // Phase 2.5.1 (kx-cm3) — preserve the "—" affordance through the signed
  // wrapper so callers that pass nullable P&L straight in still get the
  // missing-data glyph rather than `+$0.00` / `$0.00`.
  it('renders an em-dash for a null amount', () => {
    expect(formatSignedMoney(null as unknown as string, 'USD')).toBe('—')
  })

  it('renders an em-dash for an undefined amount', () => {
    expect(formatSignedMoney(undefined as unknown as string, 'USD')).toBe('—')
  })

  it('renders $0.00 for a genuine zero (not null)', () => {
    expect(formatSignedMoney('0', 'USD')).toBe('$0.00')
  })
})

describe('formatCurrency', () => {
  it('formats positive values with $ sign and commas', () => {
    expect(formatCurrency(1500)).toBe('$1,500.00')
  })

  it('formats negative values with minus sign', () => {
    expect(formatCurrency(-1234.56)).toBe('-$1,234.56')
  })

  it('handles zero', () => {
    expect(formatCurrency(0)).toBe('$0.00')
  })

  it('uses 2 decimal places by default', () => {
    expect(formatCurrency(42)).toBe('$42.00')
    expect(formatCurrency(1234567.89)).toBe('$1,234,567.89')
  })

  it('supports custom currency codes', () => {
    expect(formatCurrency(2500.50, 'EUR')).toContain('2,500.50')
  })

  it('coerces string values to numbers', () => {
    expect(formatCurrency('9999.99' as unknown as number)).toBe('$9,999.99')
  })
})

describe('formatPctOfTotal', () => {
  it('formats an ordinary share of total', () => {
    expect(formatPctOfTotal(250, 1000)).toBe('25.0%')
  })

  it('keeps the sign on negative shares', () => {
    expect(formatPctOfTotal(-250, 1000)).toBe('-25.0%')
  })

  it('suppresses ratios beyond ±999.9% as not meaningful', () => {
    expect(formatPctOfTotal(-12382262479.12, -77046.35)).toBe('—')
  })

  it('suppresses division by a zero total', () => {
    expect(formatPctOfTotal(100, 0)).toBe('—')
  })
})

describe('formatCompactNum', () => {
  it('abbreviates millions with one decimal', () => {
    expect(formatCompactNum(-19750000)).toBe('-19.8M')
  })

  it('abbreviates thousands', () => {
    expect(formatCompactNum(255854.85)).toBe('255.9K')
  })

  it('abbreviates billions', () => {
    expect(formatCompactNum(12382262479)).toBe('12.4B')
  })

  it('keeps small values at two decimals', () => {
    expect(formatCompactNum(0.2851)).toBe('0.29')
  })

  it('drops trailing .0 from abbreviations', () => {
    expect(formatCompactNum(2000)).toBe('2K')
  })
})

describe('formatNum', () => {
  it('formats a number string with default 2 decimal places', () => {
    expect(formatNum('1234.560000')).toBe('1,234.56')
  })

  it('formats a numeric value with default 2 decimal places', () => {
    expect(formatNum(5678.12)).toBe('5,678.12')
  })

  it('uses custom decimal places', () => {
    expect(formatNum('-123.450000', 4)).toBe('-123.4500')
  })

  it('pads short decimals to the requested precision', () => {
    expect(formatNum(42, 4)).toBe('42.0000')
  })

  it('formats zero', () => {
    expect(formatNum(0)).toBe('0.00')
  })

  // Phase 2.5.1 (kx-cm3) — null/undefined Greeks should surface as a clear
  // "—" so a trader doesn't mistake a missing aggregate for net-flat risk.
  it('renders an em-dash for a null value', () => {
    expect(formatNum(null as unknown as number)).toBe('—')
  })

  it('renders an em-dash for an undefined value', () => {
    expect(formatNum(undefined as unknown as number)).toBe('—')
  })

  it('renders 0.00 for a genuine zero (not null)', () => {
    expect(formatNum(0)).toBe('0.00')
  })
})

describe('formatSignedNum', () => {
  it('prefixes + on positive numeric values', () => {
    expect(formatSignedNum(1500)).toBe('+1,500.00')
  })

  it('prefixes + on positive string values', () => {
    expect(formatSignedNum('1234.56')).toBe('+1,234.56')
  })

  it('leaves the native minus sign on negative values', () => {
    expect(formatSignedNum(-1234.56)).toBe('-1,234.56')
  })

  it('leaves the native minus sign on negative string values', () => {
    expect(formatSignedNum('-50.00')).toBe('-50.00')
  })

  it('does not prefix + on zero', () => {
    expect(formatSignedNum(0)).toBe('0.00')
  })

  it('does not prefix + on negative zero', () => {
    expect(formatSignedNum(-0)).toBe(formatNum(-0))
    expect(formatSignedNum(-0)).not.toMatch(/^\+/)
  })

  it('does not prefix + when a tiny positive rounds to zero', () => {
    expect(formatSignedNum(0.001)).toBe('0.00')
  })

  it('prefixes + when a tiny positive rounds up to the last decimal', () => {
    expect(formatSignedNum(0.005)).toBe('+0.01')
  })

  it('honours custom decimals when deciding whether to prefix', () => {
    expect(formatSignedNum(0.0001, 4)).toBe('+0.0001')
    expect(formatSignedNum(0.00001, 4)).toBe('0.0000')
  })

  it('does not prefix + on NaN inputs', () => {
    expect(formatSignedNum(NaN)).toBe(formatNum(NaN))
    expect(formatSignedNum(NaN)).not.toMatch(/^\+/)
  })

  it('does not prefix + on +Infinity inputs', () => {
    expect(formatSignedNum(Infinity)).toBe(formatNum(Infinity))
    expect(formatSignedNum(Infinity)).not.toMatch(/^\+/)
  })

  it('does not prefix + on -Infinity inputs', () => {
    expect(formatSignedNum(-Infinity)).toBe(formatNum(-Infinity))
  })

  it('does not prefix + on non-numeric strings', () => {
    expect(formatSignedNum('not-a-number')).toBe(formatNum('not-a-number'))
    expect(formatSignedNum('not-a-number')).not.toMatch(/^\+/)
  })
})

describe('pnlColorClass', () => {
  it('returns green for positive amounts', () => {
    expect(pnlColorClass('150.00')).toBe('text-green-600 dark:text-green-400')
  })

  it('returns red for negative amounts', () => {
    expect(pnlColorClass('-50.00')).toBe('text-red-600 dark:text-red-400')
  })

  it('returns gray for zero', () => {
    expect(pnlColorClass('0.00')).toBe('text-gray-500 dark:text-gray-400')
  })
})

describe('formatQuantity', () => {
  it('strips trailing zeros from integer', () => {
    expect(formatQuantity('150.000000000000')).toBe('150')
  })

  it('preserves meaningful decimals', () => {
    expect(formatQuantity('0.500000')).toBe('0.5')
  })

  it('handles two decimal places', () => {
    expect(formatQuantity('10.25')).toBe('10.25')
  })

  it('rounds to 2 decimal places', () => {
    expect(formatQuantity('1.999')).toBe('2')
  })

  it('handles plain integers', () => {
    expect(formatQuantity('100')).toBe('100')
  })

  it('handles negative values', () => {
    expect(formatQuantity('-5.500000')).toBe('-5.5')
  })

  it('adds thousands separators for large integers', () => {
    expect(formatQuantity('25000')).toBe('25,000')
  })

  it('adds thousands separators for large decimals', () => {
    expect(formatQuantity('100000.50')).toBe('100,000.5')
  })

  it('adds thousands separators for millions', () => {
    expect(formatQuantity('10000000')).toBe('10,000,000')
  })
})

describe('formatRelativeTime', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns "just now" for recent times', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T10:00:30Z'))
    expect(formatRelativeTime('2025-01-15T10:00:00Z')).toBe('just now')
  })

  it('returns minutes ago', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T10:05:00Z'))
    expect(formatRelativeTime('2025-01-15T10:00:00Z')).toBe('5m ago')
  })

  it('returns hours ago', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))
    expect(formatRelativeTime('2025-01-15T10:00:00Z')).toBe('2h ago')
  })

  it('returns days ago', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-17T10:00:00Z'))
    expect(formatRelativeTime('2025-01-15T10:00:00Z')).toBe('2d ago')
  })

  it('returns "just now" for future times', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T09:00:00Z'))
    expect(formatRelativeTime('2025-01-15T10:00:00Z')).toBe('just now')
  })
})

describe('isOlderThanMinutes', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('is true when the timestamp is older than the threshold', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T10:30:00Z'))
    expect(isOlderThanMinutes('2025-01-15T10:00:00Z', 15)).toBe(true)
  })

  it('is false when the timestamp is within the threshold', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T10:05:00Z'))
    expect(isOlderThanMinutes('2025-01-15T10:00:00Z', 15)).toBe(false)
  })
})

describe('formatTimestamp', () => {
  it('formats an ISO string as YYYY-MM-DD HH:mm:ss in local time', () => {
    const date = new Date(2025, 0, 15, 10, 5, 30)
    expect(formatTimestamp(date.toISOString())).toBe('2025-01-15 10:05:30')
  })

  it('pads single-digit months, days, hours, minutes, and seconds', () => {
    const date = new Date(2025, 2, 3, 4, 5, 6)
    expect(formatTimestamp(date.toISOString())).toBe('2025-03-03 04:05:06')
  })
})

describe('formatChartTime', () => {
  it('returns HH:mm for ranges up to 1 day', () => {
    expect(formatChartTime(new Date(2025, 0, 15, 14, 30), 0.5)).toBe('14:30')
  })

  it('pads hours and minutes', () => {
    expect(formatChartTime(new Date(2025, 0, 15, 4, 5), 1)).toBe('04:05')
  })

  it('returns MMM dd for ranges longer than 1 day', () => {
    expect(formatChartTime(new Date(2025, 0, 15, 14, 30), 7)).toBe('Jan 15')
  })

  it('pads single-digit days', () => {
    expect(formatChartTime(new Date(2025, 2, 3, 0, 0), 14)).toBe('Mar 03')
  })
})

describe('formatDuration', () => {
  it('formats sub-second durations with one decimal place', () => {
    expect(formatDuration(100)).toBe('0.1s')
    expect(formatDuration(250)).toBe('0.3s')
    expect(formatDuration(999)).toBe('1.0s')
    expect(formatDuration(50)).toBe('0.1s')
  })

  it('formats durations of exactly one second', () => {
    expect(formatDuration(1000)).toBe('1s')
  })

  it('formats durations in whole seconds', () => {
    expect(formatDuration(8000)).toBe('8s')
    expect(formatDuration(8500)).toBe('9s')
    expect(formatDuration(59000)).toBe('59s')
  })

  it('formats durations of one minute or more', () => {
    expect(formatDuration(60000)).toBe('1m 0s')
    expect(formatDuration(154000)).toBe('2m 34s')
    expect(formatDuration(90000)).toBe('1m 30s')
  })

  it('formats zero milliseconds', () => {
    expect(formatDuration(0)).toBe('0.0s')
  })
})

describe('formatTimeOnly', () => {
  it('formats an ISO string as HH:mm:ss in local time', () => {
    const date = new Date(2025, 0, 15, 10, 5, 30)
    expect(formatTimeOnly(date.toISOString())).toBe('10:05:30')
  })

  it('pads single-digit hours, minutes, and seconds', () => {
    const date = new Date(2025, 2, 3, 4, 5, 6)
    expect(formatTimeOnly(date.toISOString())).toBe('04:05:06')
  })
})
