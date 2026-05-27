// Timezone toggle between the user's local clock and UTC (kx-ssj6).
//
// Operators on a trading desk routinely cross-reference timestamps with
// counterparties in other regions and with exchange schedules quoted in UTC.
// Forcing a single rendering on everyone — local or UTC — always frustrates
// half the room, so we let each user pick. The choice is persisted in
// localStorage so a refresh or new tab keeps the same convention.
//
// `formatTimestamp` is a pure helper used by tables, audit logs, and toasts.
// `useTimezoneMode` is the React hook that owns the persisted preference and
// exposes a toggle / setter pair for the toolbar control.

import { useCallback, useEffect, useState } from 'react'

/** localStorage key under which the persisted preference is stored. */
export const TIMEZONE_STORAGE_KEY = 'kinetix.timezoneMode'

export type TimezoneMode = 'local' | 'utc'

type TimeInput = Date | string | number

function toDate(input: TimeInput): Date {
  if (input instanceof Date) return input
  if (typeof input === 'number') return new Date(input)
  return new Date(input)
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/**
 * Render a timestamp in either the user's local timezone or UTC, in an
 * ISO-like format (YYYY-MM-DD HH:MM:SS) with a trailing zone marker.
 *
 * UTC output uses the suffix `UTC` to make the zone explicit; local output
 * uses `local` rather than the raw IANA zone name because the latter is
 * implementation-dependent and noisier than what tables need.
 */
export function formatTimestamp(input: TimeInput, mode: TimezoneMode): string {
  const d = toDate(input)
  if (mode === 'utc') {
    const yyyy = d.getUTCFullYear()
    const mm = pad2(d.getUTCMonth() + 1)
    const dd = pad2(d.getUTCDate())
    const hh = pad2(d.getUTCHours())
    const mi = pad2(d.getUTCMinutes())
    const ss = pad2(d.getUTCSeconds())
    return `${yyyy}-${mm}-${dd} ${hh}:${mi}:${ss} UTC`
  }
  const yyyy = d.getFullYear()
  const mm = pad2(d.getMonth() + 1)
  const dd = pad2(d.getDate())
  const hh = pad2(d.getHours())
  const mi = pad2(d.getMinutes())
  const ss = pad2(d.getSeconds())
  return `${yyyy}-${mm}-${dd} ${hh}:${mi}:${ss} local`
}

function readStoredMode(): TimezoneMode {
  try {
    const raw = window.localStorage.getItem(TIMEZONE_STORAGE_KEY)
    if (raw === 'utc' || raw === 'local') return raw
  } catch {
    // localStorage may throw in privacy modes; treat as no preference.
  }
  return 'local'
}

function writeStoredMode(mode: TimezoneMode): void {
  try {
    window.localStorage.setItem(TIMEZONE_STORAGE_KEY, mode)
  } catch {
    // Quota or privacy errors are non-fatal — the in-memory state still
    // reflects the user's choice for the current session.
  }
}

interface TimezoneModeApi {
  mode: TimezoneMode
  setMode: (next: TimezoneMode) => void
  toggle: () => void
}

/**
 * React hook owning the persisted timezone preference. Reads the initial
 * value from localStorage (defaulting to "local" when absent or invalid),
 * exposes `setMode`/`toggle` that update both state and localStorage, and
 * survives storage failures without throwing.
 */
export function useTimezoneMode(): TimezoneModeApi {
  const [mode, setModeState] = useState<TimezoneMode>(() => readStoredMode())

  // Re-sync once on mount in case the initialiser ran before localStorage was
  // populated (e.g. SSR hydration). Harmless when the value matches.
  useEffect(() => {
    const stored = readStoredMode()
    if (stored !== mode) setModeState(stored)
    // We intentionally run this only on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const setMode = useCallback((next: TimezoneMode) => {
    setModeState(next)
    writeStoredMode(next)
  }, [])

  const toggle = useCallback(() => {
    setModeState(prev => {
      const next: TimezoneMode = prev === 'local' ? 'utc' : 'local'
      writeStoredMode(next)
      return next
    })
  }, [])

  return { mode, setMode, toggle }
}
