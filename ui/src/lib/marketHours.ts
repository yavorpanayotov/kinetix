// Market-hours indicator badge helper (kx-epmb).
//
// A trader's workspace lives across multiple exchange schedules. Surfacing
// "is my market open right now?" at a glance saves the operator from
// reaching for a separate clock — and saves a costly mistake when staring
// at frozen prices on a closed venue. This module renders a compact badge
// of the form `HH:MM <ZONE> (market open|closed)` for a small set of
// supported sessions.
//
// The intent is *informational*, not authoritative: we deliberately do not
// model holidays, half-days, or exchange-specific microstructure here.
// Those concerns live with reference-data; this helper exists to give the
// operator a quick contextual cue, with the actual scheduling source of
// truth held server-side.

/** Identifier for one of the supported exchange sessions. */
export type MarketZone = 'ET' | 'GB'

/**
 * Static description of an exchange session: the IANA timezone the wall
 * clock should be rendered in, plus the open/close window expressed as
 * minutes since local midnight.
 */
export interface MarketSession {
  /** Short label shown in the badge (e.g. "ET", "GB"). */
  label: MarketZone
  /** IANA timezone that the badge renders the wall clock in. */
  ianaZone: string
  /** Minute-of-day at which the session opens (local to `ianaZone`). */
  openMinutes: number
  /** Minute-of-day at which the session closes (local to `ianaZone`). */
  closeMinutes: number
}

/**
 * Supported sessions. Times are the public regular-session hours of the
 * primary US and UK equity venues — NYSE 09:30-16:00 ET and LSE
 * 08:00-16:30 GB. Other venues can be added as the platform expands.
 */
export const MARKET_SESSIONS: Record<MarketZone, MarketSession> = {
  ET: {
    label: 'ET',
    ianaZone: 'America/New_York',
    openMinutes: 9 * 60 + 30,
    closeMinutes: 16 * 60,
  },
  GB: {
    label: 'GB',
    ianaZone: 'Europe/London',
    openMinutes: 8 * 60,
    closeMinutes: 16 * 60 + 30,
  },
}

/** Look up the configured session for a given zone. */
export function marketSessionFor(zone: MarketZone): MarketSession {
  return MARKET_SESSIONS[zone]
}

/**
 * Extract the wall-clock fields for `instant` rendered in `ianaZone`.
 *
 * We lean on `Intl.DateTimeFormat` to do the zone-shift arithmetic so we
 * inherit DST handling for free — no need to ship our own table of
 * historical offset transitions.
 */
function wallClockParts(
  instant: Date,
  ianaZone: string,
): { weekday: number; hour: number; minute: number } {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: ianaZone,
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
  const parts = formatter.formatToParts(instant)
  let weekdayStr = ''
  let hour = 0
  let minute = 0
  for (const part of parts) {
    if (part.type === 'weekday') weekdayStr = part.value
    else if (part.type === 'hour') hour = Number.parseInt(part.value, 10)
    else if (part.type === 'minute') minute = Number.parseInt(part.value, 10)
  }
  // `en-US` returns 24 for midnight under hour12:false in some engines —
  // normalise to the expected [0, 23] domain.
  if (hour === 24) hour = 0
  const weekday = weekdayToIndex(weekdayStr)
  return { weekday, hour, minute }
}

function weekdayToIndex(weekday: string): number {
  switch (weekday) {
    case 'Sun':
      return 0
    case 'Mon':
      return 1
    case 'Tue':
      return 2
    case 'Wed':
      return 3
    case 'Thu':
      return 4
    case 'Fri':
      return 5
    case 'Sat':
      return 6
    default:
      return -1
  }
}

/**
 * Return `true` when `instant` falls inside the regular-session window for
 * `zone`. Weekend days are always closed. Holidays and half-days are not
 * modelled — see the module comment for the rationale.
 */
export function isMarketOpen(zone: MarketZone, instant: Date = new Date()): boolean {
  const session = marketSessionFor(zone)
  const { weekday, hour, minute } = wallClockParts(instant, session.ianaZone)
  if (weekday === 0 || weekday === 6) return false
  const minuteOfDay = hour * 60 + minute
  return minuteOfDay >= session.openMinutes && minuteOfDay < session.closeMinutes
}

/** Human-readable open/closed label used inside the badge text. */
export function describeMarketState(
  zone: MarketZone,
  instant: Date = new Date(),
): 'market open' | 'market closed' {
  return isMarketOpen(zone, instant) ? 'market open' : 'market closed'
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/**
 * Render the badge string, e.g. `"10:15 ET (market open)"`. Hours and
 * minutes are rendered in the zone's local wall clock so the operator sees
 * the time as it appears on the venue's own ticker.
 */
export function formatMarketHoursBadge(
  zone: MarketZone,
  instant: Date = new Date(),
): string {
  const session = marketSessionFor(zone)
  const { hour, minute } = wallClockParts(instant, session.ianaZone)
  const state = describeMarketState(zone, instant)
  return `${pad2(hour)}:${pad2(minute)} ${session.label} (${state})`
}
