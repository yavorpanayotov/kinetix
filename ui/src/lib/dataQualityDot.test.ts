// Tests for the per-row data-quality dot helper (kx-xogb).
//
// Risk and position tables show data that gets recomputed at different
// cadences — some columns refresh every minute, others lag five or fifteen
// minutes behind. Without an inline freshness cue, traders cannot tell
// whether the "last printed" cell is current or six hours stale. The
// dataQualityDot helper returns a green dot when the data is within
// the freshness threshold and a gray (stale) dot when it isn't, with an
// accessible label that announces the lag.

import { describe, it, expect } from 'vitest'

import { dataQualityDot } from './dataQualityDot'

describe('dataQualityDot', () => {
  const NOW = new Date('2026-05-28T12:00:00Z')
  const FRESH_MS = 5 * 60 * 1000 // 5 minutes

  it('returns the fresh dot when the data is within the threshold', () => {
    const lastUpdate = new Date(NOW.getTime() - 30 * 1000) // 30s ago
    expect(dataQualityDot(lastUpdate, { now: NOW, freshMs: FRESH_MS })).toEqual({
      tone: 'fresh',
      color: 'green',
      label: 'Data updated 30s ago',
      ariaLabel: 'Data is fresh; updated 30 seconds ago',
    })
  })

  it('returns the stale dot once the data exceeds the threshold', () => {
    const lastUpdate = new Date(NOW.getTime() - 10 * 60 * 1000) // 10m ago
    expect(dataQualityDot(lastUpdate, { now: NOW, freshMs: FRESH_MS })).toEqual({
      tone: 'stale',
      color: 'gray',
      label: 'Data updated 10m ago',
      ariaLabel: 'Data is stale; updated 10 minutes ago',
    })
  })

  it('flags data exactly at the threshold as stale (strictly less than counts as fresh)', () => {
    const lastUpdate = new Date(NOW.getTime() - FRESH_MS)
    expect(dataQualityDot(lastUpdate, { now: NOW, freshMs: FRESH_MS }).tone).toBe(
      'stale',
    )
  })

  it('formats lags below 60s in seconds', () => {
    const lastUpdate = new Date(NOW.getTime() - 7 * 1000)
    expect(
      dataQualityDot(lastUpdate, { now: NOW, freshMs: FRESH_MS }).label,
    ).toBe('Data updated 7s ago')
  })

  it('formats lags above 60s in whole minutes', () => {
    const lastUpdate = new Date(NOW.getTime() - 3 * 60 * 1000 - 15 * 1000)
    expect(
      dataQualityDot(lastUpdate, { now: NOW, freshMs: FRESH_MS }).label,
    ).toBe('Data updated 3m ago')
  })

  it('formats lags above 60 minutes in whole hours', () => {
    const lastUpdate = new Date(NOW.getTime() - 2 * 60 * 60 * 1000)
    expect(
      dataQualityDot(lastUpdate, { now: NOW, freshMs: FRESH_MS }).label,
    ).toBe('Data updated 2h ago')
  })

  it('returns "never" when lastUpdate is null', () => {
    expect(dataQualityDot(null, { now: NOW, freshMs: FRESH_MS })).toEqual({
      tone: 'stale',
      color: 'gray',
      label: 'No data',
      ariaLabel: 'Data not yet received',
    })
  })

  it('returns "future" when lastUpdate is later than now (defensive against clock skew)', () => {
    const lastUpdate = new Date(NOW.getTime() + 1000)
    expect(dataQualityDot(lastUpdate, { now: NOW, freshMs: FRESH_MS }).tone).toBe(
      'fresh',
    )
  })
})
