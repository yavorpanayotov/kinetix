import { describe, expect, it } from 'vitest'
import { formatNumeric } from './format'

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
