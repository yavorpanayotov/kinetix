// Tests for the market-hours indicator badge helper (kx-epmb).

import { describe, it, expect } from 'vitest'

import {
  describeMarketState,
  formatMarketHoursBadge,
  isMarketOpen,
  marketSessionFor,
  MARKET_SESSIONS,
} from './marketHours'

describe('marketSessionFor', () => {
  it('returns the configured NYSE session for the ET zone', () => {
    const session = marketSessionFor('ET')
    expect(session).toBe(MARKET_SESSIONS.ET)
    expect(session.openMinutes).toBe(9 * 60 + 30)
    expect(session.closeMinutes).toBe(16 * 60)
  })

  it('returns the configured LSE session for the GB zone', () => {
    const session = marketSessionFor('GB')
    expect(session.label).toBe('GB')
    expect(session.openMinutes).toBe(8 * 60)
    expect(session.closeMinutes).toBe(16 * 60 + 30)
  })
})

describe('isMarketOpen', () => {
  it('reports closed on a Saturday regardless of the wall clock', () => {
    // 2026-05-30 is a Saturday.
    const saturdayNoonUtc = new Date(Date.UTC(2026, 4, 30, 16, 0))
    expect(isMarketOpen('ET', saturdayNoonUtc)).toBe(false)
  })

  it('reports open inside the NYSE 09:30-16:00 ET window on a weekday', () => {
    // 2026-05-27 is a Wednesday. 14:00 UTC == 10:00 ET in EDT (UTC-4).
    const wedTenET = new Date(Date.UTC(2026, 4, 27, 14, 0))
    expect(isMarketOpen('ET', wedTenET)).toBe(true)
  })

  it('reports closed before the NYSE open on a weekday', () => {
    // 2026-05-27 12:00 UTC == 08:00 ET — pre-market.
    const wedPreOpen = new Date(Date.UTC(2026, 4, 27, 12, 0))
    expect(isMarketOpen('ET', wedPreOpen)).toBe(false)
  })

  it('reports closed after the NYSE close on a weekday', () => {
    // 2026-05-27 21:00 UTC == 17:00 ET — post-close.
    const wedPostClose = new Date(Date.UTC(2026, 4, 27, 21, 0))
    expect(isMarketOpen('ET', wedPostClose)).toBe(false)
  })
})

describe('describeMarketState', () => {
  it('returns "market open" while inside the session window', () => {
    const wedTenET = new Date(Date.UTC(2026, 4, 27, 14, 0))
    expect(describeMarketState('ET', wedTenET)).toBe('market open')
  })

  it('returns "market closed" outside the session window', () => {
    const wedPostClose = new Date(Date.UTC(2026, 4, 27, 21, 0))
    expect(describeMarketState('ET', wedPostClose)).toBe('market closed')
  })
})

describe('formatMarketHoursBadge', () => {
  it('renders the local time in the requested zone with an open marker', () => {
    // 2026-05-27 14:15 UTC == 10:15 ET (EDT, UTC-4).
    const wedMorningUtc = new Date(Date.UTC(2026, 4, 27, 14, 15))
    expect(formatMarketHoursBadge('ET', wedMorningUtc)).toBe('10:15 ET (market open)')
  })

  it('renders the local time with a closed marker on the weekend', () => {
    // 2026-05-30 16:00 UTC (Saturday) == 12:00 ET.
    const satNoonUtc = new Date(Date.UTC(2026, 4, 30, 16, 0))
    expect(formatMarketHoursBadge('ET', satNoonUtc)).toBe('12:00 ET (market closed)')
  })

  it('renders LSE timings with the GB label', () => {
    // 2026-05-27 09:30 UTC == 10:30 BST (UK summer time, UTC+1) — LSE open.
    const wedMorningUtc = new Date(Date.UTC(2026, 4, 27, 9, 30))
    expect(formatMarketHoursBadge('GB', wedMorningUtc)).toBe('10:30 GB (market open)')
  })
})
