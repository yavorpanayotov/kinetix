import { describe, it, expect } from 'vitest'
import { formatDiversificationBenefit } from './diversificationFormatter'

describe('diversificationFormatter', () => {
  it('collapses tiny rounding-noise values to ~$0', () => {
    expect(formatDiversificationBenefit(0.01)).toBe('~$0')
    expect(formatDiversificationBenefit(-0.01)).toBe('~$0')
    expect(formatDiversificationBenefit(0)).toBe('~$0')
  })

  it('formats a genuine diversification benefit as a negative currency amount', () => {
    // Diversification reduces VaR, so it is conventionally shown as a credit (-$X).
    expect(formatDiversificationBenefit(125000)).toBe('-$125,000.00')
    expect(formatDiversificationBenefit(1234.56)).toBe('-$1,234.56')
  })

  it('treats the magnitude of the value, so negative inputs above epsilon still render', () => {
    expect(formatDiversificationBenefit(-1234.56)).toBe('-$1,234.56')
  })

  it('respects a configurable epsilon', () => {
    // With a wider epsilon, values under it collapse to ~$0.
    expect(formatDiversificationBenefit(5, 10)).toBe('~$0')
    // At/above epsilon they render as currency.
    expect(formatDiversificationBenefit(15, 10)).toBe('-$15.00')
  })

  it('uses a default epsilon that keeps sub-cent rounding noise collapsed', () => {
    // -$0.004 rounds to $0.00 at cent precision — pure noise.
    expect(formatDiversificationBenefit(0.004)).toBe('~$0')
    // $0.50 is a real, displayable amount and must not collapse.
    expect(formatDiversificationBenefit(0.5)).toBe('-$0.50')
  })

  it('collapses NaN and non-finite values to ~$0 rather than leaking $NaN', () => {
    expect(formatDiversificationBenefit(Number.NaN)).toBe('~$0')
    expect(formatDiversificationBenefit(Number.POSITIVE_INFINITY)).toBe('~$0')
  })
})
