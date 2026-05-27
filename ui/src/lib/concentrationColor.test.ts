// Tests for the per-asset-class position-concentration colour helper (kx-7dbn).
//
// A 30% position weight in a single equity name is a noteworthy concentration
// but not yet alarming. The same 30% weight in a single FX pair or a single
// crypto name is *very* alarming — the risk profile of each asset class makes
// the same numeric weight mean different things. The portfolio dashboard
// renders a small swatch next to each position's weight; this helper decides
// what colour that swatch should be.
//
// Each asset class has its own thresholds: anything below the "warn" boundary
// is green, anything between warn and "breach" is amber, anything at or above
// breach is red. The boundaries are tuned to each asset class's typical
// concentration risk profile:
//
//   equity:   warn 25%, breach 40%   (large-cap single-name limits)
//   fx:       warn 15%, breach 30%   (single-pair gap-risk)
//   crypto:   warn 10%, breach 20%   (24/7 venue, vol, liquidity holes)
//   rates:    warn 35%, breach 60%   (low idiosyncratic risk)
//   commodity: warn 20%, breach 35%  (squeeze / storage risk)
//
// Unknown asset classes fall back to a conservative generic profile so a new
// instrument type never silently renders green.

import { describe, it, expect } from 'vitest'

import { concentrationColor } from './concentrationColor'

const HEX_PATTERN = /^#[0-9a-f]{6}$/

describe('concentrationColor', () => {
  it('returns a hex colour string for every asset class and weight', () => {
    for (const cls of ['equity', 'fx', 'crypto', 'rates', 'commodity'] as const) {
      for (const w of [0, 10, 25, 40, 60, 100]) {
        expect(concentrationColor(w, cls)).toMatch(HEX_PATTERN)
      }
    }
  })

  it('renders green for low equity concentration', () => {
    // 10% in a single equity name is well within typical desk limits.
    expect(concentrationColor(10, 'equity')).toBe('#22c55e')
  })

  it('renders amber as equity concentration approaches the limit', () => {
    // 30% in one equity name sits in the warn band (25–40%).
    const c = concentrationColor(30, 'equity')
    expect(c).not.toBe('#22c55e')
    expect(c).not.toBe('#ef4444')
  })

  it('renders red once equity concentration breaches its limit', () => {
    expect(concentrationColor(40, 'equity')).toBe('#ef4444')
    expect(concentrationColor(75, 'equity')).toBe('#ef4444')
  })

  it('treats the same weight as more dangerous in crypto than in equity', () => {
    // 30% in equity is amber; 30% in crypto is already red — crypto has a
    // much lower breach threshold (20%) by design.
    expect(concentrationColor(30, 'crypto')).toBe('#ef4444')
    expect(concentrationColor(30, 'equity')).not.toBe('#ef4444')
  })

  it('treats the same weight as less dangerous in rates than in equity', () => {
    // 30% in rates is still comfortably green; rates carry low idiosyncratic
    // single-name risk so the warn threshold sits higher (35%).
    expect(concentrationColor(30, 'rates')).toBe('#22c55e')
    expect(concentrationColor(30, 'equity')).not.toBe('#22c55e')
  })

  it('renders red for fx pair concentrations above the breach threshold', () => {
    // 30% in a single FX pair sits exactly on the breach threshold.
    expect(concentrationColor(30, 'fx')).toBe('#ef4444')
    expect(concentrationColor(50, 'fx')).toBe('#ef4444')
  })

  it('renders green for low commodity concentration', () => {
    expect(concentrationColor(5, 'commodity')).toBe('#22c55e')
  })

  it('clamps negative weights to green (zero-weight position)', () => {
    expect(concentrationColor(-5, 'equity')).toBe('#22c55e')
    expect(concentrationColor(-100, 'rates')).toBe('#22c55e')
  })

  it('surfaces non-finite weights as red so missing readings stay visible', () => {
    expect(concentrationColor(Number.POSITIVE_INFINITY, 'equity')).toBe('#ef4444')
    expect(concentrationColor(Number.NaN, 'crypto')).toBe('#ef4444')
  })

  it('falls back to a conservative profile for unknown asset classes', () => {
    // A brand-new instrument type should never silently render green for
    // moderately high weights — the unknown lane is tuned to the strictest
    // (crypto-style) thresholds so an operator notices the gap.
    expect(concentrationColor(25, 'wildcat-derivative')).toBe('#ef4444')
    expect(concentrationColor(5, 'wildcat-derivative')).toBe('#22c55e')
  })

  it('produces deterministic output — same input, same colour', () => {
    expect(concentrationColor(27.5, 'equity')).toBe(concentrationColor(27.5, 'equity'))
    expect(concentrationColor(19.9, 'crypto')).toBe(concentrationColor(19.9, 'crypto'))
  })

  it('is case-insensitive on the asset class', () => {
    expect(concentrationColor(10, 'EQUITY')).toBe(concentrationColor(10, 'equity'))
    expect(concentrationColor(45, 'Crypto')).toBe(concentrationColor(45, 'crypto'))
  })
})
