// Business-day shading helper (kx-4qym).
//
// Date columns across the UI render daily series — risk timelines, P&L
// strips, run history. Rendering weekend cells with the same background as
// business days makes it hard to spot weekend gaps at a glance, and
// configured holidays (firm closures, market holidays) should be visually
// distinct too. This helper returns a Tailwind utility class for each kind
// of day so columns can shade themselves consistently.
//
// Day-of-week is computed in UTC because date-column data is keyed by the
// ISO calendar day, not the viewer's local zone — using local DOW would
// mis-shade Saturday timestamps that fall on Friday in the user's zone.

/** Tailwind class applied to regular business days. */
export const BUSINESS_DAY_SHADE = 'bg-transparent'
/** Tailwind class applied to weekend days. */
export const WEEKEND_SHADE = 'bg-slate-100'
/** Tailwind class applied to configured holidays. */
export const HOLIDAY_SHADE = 'bg-amber-50'

type DateInput = Date | string

/** Holiday set entries are ISO `YYYY-MM-DD` strings. */
export type HolidaySet = ReadonlySet<string>

function toDate(input: DateInput): Date {
  return input instanceof Date ? input : new Date(`${input}T00:00:00Z`)
}

function isoDay(date: Date): string {
  // UTC year-month-day, zero-padded.
  const y = date.getUTCFullYear().toString().padStart(4, '0')
  const m = (date.getUTCMonth() + 1).toString().padStart(2, '0')
  const d = date.getUTCDate().toString().padStart(2, '0')
  return `${y}-${m}-${d}`
}

function isWeekend(date: Date): boolean {
  const dow = date.getUTCDay()
  // 0 = Sunday, 6 = Saturday in JS Date semantics.
  return dow === 0 || dow === 6
}

/**
 * True when the date is a business day — neither a weekend nor a configured
 * holiday. Holidays are matched on the UTC calendar day.
 */
export function isBusinessDay(input: DateInput, holidays?: HolidaySet): boolean {
  const date = toDate(input)
  if (isWeekend(date)) return false
  if (holidays && holidays.has(isoDay(date))) return false
  return true
}

/**
 * Tailwind background class to apply to a date cell. Holidays take priority
 * over weekends so a holiday that falls on a weekend renders as a holiday.
 */
export function businessDayShade(input: DateInput, holidays?: HolidaySet): string {
  const date = toDate(input)
  if (holidays && holidays.has(isoDay(date))) return HOLIDAY_SHADE
  if (isWeekend(date)) return WEEKEND_SHADE
  return BUSINESS_DAY_SHADE
}
