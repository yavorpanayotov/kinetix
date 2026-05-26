import { describe, it, expect } from 'vitest'
import { formatPrice, priceDecimals } from './formatPrice'

describe('priceDecimals', () => {
  it('uses 4 decimals for FX', () => {
    expect(priceDecimals('FX')).toBe(4)
    expect(priceDecimals('fx_spot')).toBe(4)
    expect(priceDecimals('FOREX')).toBe(4)
  })

  it('uses 3 decimals for bonds / fixed income', () => {
    expect(priceDecimals('BOND')).toBe(3)
    expect(priceDecimals('FIXED_INCOME')).toBe(3)
  })

  it('uses 2 decimals for equities and other asset classes', () => {
    expect(priceDecimals('EQUITY')).toBe(2)
    expect(priceDecimals('COMMODITY')).toBe(2)
    expect(priceDecimals('OPTION')).toBe(2)
    expect(priceDecimals(undefined)).toBe(2)
  })
})

describe('formatPrice', () => {
  it('formats EQUITY prices with 2 decimal places in USD', () => {
    expect(formatPrice('150', 'USD', 'EQUITY')).toBe('$150.00')
    expect(formatPrice('123.456', 'USD', 'EQUITY')).toBe('$123.46')
  })

  it('formats FX prices with 4 decimal places', () => {
    expect(formatPrice('1.085', 'USD', 'FX')).toBe('$1.0850')
    expect(formatPrice('1.08543', 'USD', 'FX')).toBe('$1.0854')
  })

  it('formats bond prices with 3 decimal places', () => {
    expect(formatPrice('98.765', 'USD', 'BOND')).toBe('$98.765')
    expect(formatPrice('100', 'USD', 'FIXED_INCOME')).toBe('$100.000')
  })

  it('returns em-dash for null / undefined / empty', () => {
    expect(formatPrice(null, 'USD', 'EQUITY')).toBe('—')
    expect(formatPrice(undefined, 'USD', 'EQUITY')).toBe('—')
    expect(formatPrice('', 'USD', 'EQUITY')).toBe('—')
  })

  it('preserves zero as a real numeric value (not em-dash)', () => {
    expect(formatPrice('0', 'USD', 'EQUITY')).toBe('$0.00')
  })

  it('falls back to a numeric+currency string for unknown currency codes', () => {
    const result = formatPrice('1.0850', 'XYZ', 'FX')
    // Unknown currency: Intl may throw — accept either fallback or native render
    expect(result).toMatch(/1\.0850/)
  })
})
