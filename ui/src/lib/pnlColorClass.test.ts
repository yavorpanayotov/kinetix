// Tests for the P&L saturation-scale helper (kx-rfrf).
//
// P&L cells render with green for gains, red for losses, and the
// saturation increases with magnitude — a $5 daily P&L is barely tinted,
// a $1M daily P&L pops at full saturation. Without a saturation scale,
// every P&L cell on a desk dashboard has the same colour intensity,
// making it impossible to spot the outlier rows at a glance.
//
// The thresholds in the helper are calibrated for a $-denominated book:
// |P&L| < $10k -> 50 (lightest); $10k <= |P&L| < $100k -> 200; $100k+ ->
// 600 (darkest). Callers in non-USD books can pass a [scale] divisor to
// re-anchor the thresholds.

import { describe, it, expect } from 'vitest'

import { pnlColorClass } from './pnlColorClass'

describe('pnlColorClass', () => {
  it('returns the lightest green for tiny positive P&L (<$10k)', () => {
    expect(pnlColorClass(5_000)).toEqual({
      sign: 'gain',
      magnitude: 'light',
      className: 'bg-emerald-50 dark:bg-emerald-950/20',
    })
  })

  it('returns the medium green for mid positive P&L ($10k-$100k)', () => {
    expect(pnlColorClass(50_000).magnitude).toBe('medium')
  })

  it('returns the deep green for large positive P&L (>=$100k)', () => {
    expect(pnlColorClass(500_000).magnitude).toBe('deep')
  })

  it('returns the lightest red for tiny losses (>-$10k)', () => {
    expect(pnlColorClass(-5_000).magnitude).toBe('light')
    expect(pnlColorClass(-5_000).sign).toBe('loss')
  })

  it('returns the medium red for mid losses (-$10k to -$100k)', () => {
    expect(pnlColorClass(-50_000)).toEqual({
      sign: 'loss',
      magnitude: 'medium',
      className: 'bg-red-100 dark:bg-red-900/30',
    })
  })

  it('returns the deep red for large losses (<=-$100k)', () => {
    expect(pnlColorClass(-500_000).magnitude).toBe('deep')
  })

  it('returns the neutral cue for zero', () => {
    expect(pnlColorClass(0)).toEqual({
      sign: 'flat',
      magnitude: 'light',
      className: '',
    })
  })

  it('returns neutral for null / undefined / non-finite', () => {
    expect(pnlColorClass(null).sign).toBe('flat')
    expect(pnlColorClass(undefined).sign).toBe('flat')
    expect(pnlColorClass(NaN).sign).toBe('flat')
    expect(pnlColorClass(Infinity).sign).toBe('flat')
  })

  it('honours custom scale: $1k threshold rescales (1000-> medium, 10000->deep)', () => {
    expect(pnlColorClass(1_500, { scale: 0.1 }).magnitude).toBe('medium')
    expect(pnlColorClass(15_000, { scale: 0.1 }).magnitude).toBe('deep')
  })

  it('boundary 10k is medium not light (>= threshold)', () => {
    expect(pnlColorClass(10_000).magnitude).toBe('medium')
  })

  it('boundary 100k is deep not medium', () => {
    expect(pnlColorClass(100_000).magnitude).toBe('deep')
  })
})
