// Auto-refreshing relative time formatter (kx-z5tm).
//
// Many UI surfaces render timestamps that should "age" while the user is
// looking at them — last-run cards, alert toasts, audit log entries. Rather
// than have every consumer wire its own setInterval, this module exposes a
// pure formatter plus a tiny React hook that re-renders on a shared cadence.
//
// The cadence is intentionally coarse (ten seconds): consumers usually only
// care about minute-level resolution, and refreshing more frequently churns
// React renders without changing the displayed string. Seconds-level deltas
// still update on the next tick — close enough for human perception.

import { useEffect, useState } from 'react'

/** How often the `useRelativeTime` hook re-evaluates the formatter. */
export const RELATIVE_TIME_REFRESH_MS = 10_000

type TimeInput = Date | string | number

function toDate(input: TimeInput): Date {
  if (input instanceof Date) return input
  if (typeof input === 'number') return new Date(input)
  return new Date(input)
}

function pluralise(n: number, unit: string): string {
  const abs = Math.abs(n)
  return `${abs} ${unit}${abs === 1 ? '' : 's'}`
}

/**
 * Format a timestamp relative to `now` (defaults to the current wall clock).
 * Returns strings like "3 minutes ago", "in 5 minutes", or "just now".
 *
 * Past tense uses "<n> <unit> ago"; future tense uses "in <n> <unit>".
 * Sub-five-second deltas collapse to "just now" so the readout doesn't churn
 * on freshly-arrived data.
 */
export function formatRelativeTime(input: TimeInput, now: Date = new Date()): string {
  const then = toDate(input)
  const deltaSeconds = Math.round((now.getTime() - then.getTime()) / 1000)
  const past = deltaSeconds >= 0
  const abs = Math.abs(deltaSeconds)

  if (abs < 5) return 'just now'

  let label: string
  if (abs < 60) {
    label = pluralise(abs, 'second')
  } else if (abs < 3600) {
    label = pluralise(Math.floor(abs / 60), 'minute')
  } else if (abs < 86_400) {
    label = pluralise(Math.floor(abs / 3600), 'hour')
  } else {
    label = pluralise(Math.floor(abs / 86_400), 'day')
  }

  return past ? `${label} ago` : `in ${label}`
}

/**
 * React hook that returns the relative-time string for `input` and refreshes
 * every {@link RELATIVE_TIME_REFRESH_MS} milliseconds. Consumers re-render on
 * each tick so "3 minutes ago" can age into "4 minutes ago" without manual
 * timer wiring.
 */
export function useRelativeTime(input: TimeInput): string {
  // Tick counter; we don't actually need its value, just a way to re-render
  // every RELATIVE_TIME_REFRESH_MS so the derived label below refreshes.
  const [, setTick] = useState(0)

  useEffect(() => {
    const id = setInterval(() => {
      setTick((t) => t + 1)
    }, RELATIVE_TIME_REFRESH_MS)
    return () => clearInterval(id)
  }, [input])

  return formatRelativeTime(input)
}
