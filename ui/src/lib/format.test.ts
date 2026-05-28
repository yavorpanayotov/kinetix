import { describe, expect, it } from 'vitest'
import { formatNumeric, formatRhoTooltip, formatVegaTooltip } from './format'

describe('formatNumeric', () => {
  it('renders an em-dash for null', () => {
    expect(formatNumeric(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatNumeric(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatNumeric(NaN)).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatNumeric(Infinity)).toBe('—')
    expect(formatNumeric(-Infinity)).toBe('—')
  })

  it('formats a finite number with default 2 decimal places', () => {
    expect(formatNumeric(1234.5678)).toBe('1234.57')
  })

  it('honours the supplied fractionDigits', () => {
    expect(formatNumeric(1.23456, { fractionDigits: 4 })).toBe('1.2346')
  })

  it('formats zero without falling through to the em-dash branch', () => {
    expect(formatNumeric(0)).toBe('0.00')
  })

  it('formats negative numbers', () => {
    expect(formatNumeric(-42.5)).toBe('-42.50')
  })

  it('uses the supplied placeholder when given', () => {
    expect(formatNumeric(null, { placeholder: 'n/a' })).toBe('n/a')
  })
})

describe('formatRhoTooltip', () => {
  it('renders an em-dash for null', () => {
    expect(formatRhoTooltip(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatRhoTooltip(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatRhoTooltip(NaN)).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatRhoTooltip(Infinity)).toBe('—')
    expect(formatRhoTooltip(-Infinity)).toBe('—')
  })

  it('appends the $/bp IR unit suffix to a finite value', () => {
    expect(formatRhoTooltip(1234.5678)).toBe('1234.57 $/bp IR')
  })

  it('honours custom fractionDigits', () => {
    expect(formatRhoTooltip(1.23456, { fractionDigits: 4 })).toBe('1.2346 $/bp IR')
  })

  it('formats zero with the unit suffix', () => {
    expect(formatRhoTooltip(0)).toBe('0.00 $/bp IR')
  })

  it('formats negative values with the unit suffix', () => {
    expect(formatRhoTooltip(-42.5)).toBe('-42.50 $/bp IR')
  })
})

describe('formatVegaTooltip', () => {
  it('renders an em-dash for null', () => {
    expect(formatVegaTooltip(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatVegaTooltip(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatVegaTooltip(NaN)).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatVegaTooltip(Infinity)).toBe('—')
    expect(formatVegaTooltip(-Infinity)).toBe('—')
  })

  it('appends the %/1pp vol unit suffix to a finite value', () => {
    expect(formatVegaTooltip(1234.5678)).toBe('1234.57 %/1pp vol')
  })

  it('honours custom fractionDigits', () => {
    expect(formatVegaTooltip(1.23456, { fractionDigits: 4 })).toBe(
      '1.2346 %/1pp vol',
    )
  })

  it('formats zero with the unit suffix', () => {
    expect(formatVegaTooltip(0)).toBe('0.00 %/1pp vol')
  })

  it('formats negative values with the unit suffix', () => {
    expect(formatVegaTooltip(-42.5)).toBe('-42.50 %/1pp vol')
  })
})
