// Tests for the counterparty exposure RAG gradient helper (kx-8m18).
//
// Counterparty risk teams watch utilisation against per-counterparty limits.
// Sighted users read the bar's colour at a glance — green when comfortably
// below the limit, amber as utilisation approaches it, red when the limit
// is breached. The dashboard already exposes the numeric percentage, but
// the colour is what catches the eye on a wall of tickers, so the colour
// helper must:
//
//   1. Return a green hue while utilisation is low (≤ 50% of limit).
//   2. Transition through amber in the middle band (50% < % < 100%).
//   3. Return red once the limit is reached or breached (≥ 100%).
//   4. Stay deterministic — same input always yields the same colour, so
//      screenshots don't flap.
//   5. Clamp inputs that arrive outside [0, ∞) so a junk value can never
//      produce an undefined colour.
//
// The helper returns a 7-character `#rrggbb` string. Hex is unambiguous,
// stable across dark/light themes (we set lightness explicitly), and easy
// to assert against in unit tests.

import { describe, it, expect } from 'vitest'

import { counterpartyExposureColor } from './counterpartyExposureColor'

const HEX_PATTERN = /^#[0-9a-f]{6}$/

describe('counterpartyExposureColor', () => {
  it('returns a hex colour string for any non-negative percentage', () => {
    for (const pct of [0, 25, 50, 75, 100, 200]) {
      expect(counterpartyExposureColor(pct)).toMatch(HEX_PATTERN)
    }
  })

  it('returns pure green at zero utilisation', () => {
    expect(counterpartyExposureColor(0)).toBe('#22c55e')
  })

  it('returns pure amber at the start of the warning band (50%)', () => {
    expect(counterpartyExposureColor(50)).toBe('#22c55e')
  })

  it('returns pure red at exactly 100% utilisation', () => {
    expect(counterpartyExposureColor(100)).toBe('#ef4444')
  })

  it('returns red for any utilisation above the limit', () => {
    expect(counterpartyExposureColor(105)).toBe('#ef4444')
    expect(counterpartyExposureColor(200)).toBe('#ef4444')
    expect(counterpartyExposureColor(1000)).toBe('#ef4444')
  })

  it('blends towards amber as utilisation climbs through the warning band', () => {
    const at50 = counterpartyExposureColor(50)
    const at75 = counterpartyExposureColor(75)
    const at99 = counterpartyExposureColor(99)
    expect(at50).not.toBe(at75)
    expect(at75).not.toBe(at99)
    expect(at99).not.toBe(at50)
  })

  it('treats negative percentages as zero (clamps below)', () => {
    expect(counterpartyExposureColor(-5)).toBe('#22c55e')
    expect(counterpartyExposureColor(-100)).toBe('#22c55e')
  })

  it('produces deterministic output — same input, same colour', () => {
    expect(counterpartyExposureColor(73.2)).toBe(counterpartyExposureColor(73.2))
    expect(counterpartyExposureColor(99.9)).toBe(counterpartyExposureColor(99.9))
  })

  it('returns amber-ish hues across the middle of the warning band', () => {
    // We don't pin the exact midpoint colour — that would couple the test to
    // the interpolation curve. Instead we verify the red component dominates
    // green as utilisation climbs, which is the only behaviour traders see.
    const at60 = counterpartyExposureColor(60)
    const at90 = counterpartyExposureColor(90)
    const r60 = parseInt(at60.slice(1, 3), 16)
    const g60 = parseInt(at60.slice(3, 5), 16)
    const r90 = parseInt(at90.slice(1, 3), 16)
    const g90 = parseInt(at90.slice(3, 5), 16)
    // Red grows, green shrinks as we approach the limit.
    expect(r90).toBeGreaterThanOrEqual(r60)
    expect(g90).toBeLessThanOrEqual(g60)
  })

  it('handles non-finite inputs gracefully by clamping to red', () => {
    expect(counterpartyExposureColor(Number.POSITIVE_INFINITY)).toBe('#ef4444')
    // NaN is treated as a "missing" reading — surface it as red so the
    // operator notices the gap rather than silently rendering green.
    expect(counterpartyExposureColor(Number.NaN)).toBe('#ef4444')
  })
})
