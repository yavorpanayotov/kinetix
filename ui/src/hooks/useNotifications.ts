import { useCallback, useEffect, useMemo, useState } from 'react'
import { useAlertStream } from './useAlertStream'
import {
  fetchRules,
  createRule as apiCreateRule,
  deleteRule as apiDeleteRule,
  fetchAlerts,
  acknowledgeAlert as apiAcknowledgeAlert,
  escalateAlert as apiEscalateAlert,
  resolveAlert as apiResolveAlert,
  snoozeAlert as apiSnoozeAlert,
} from '../api/notifications'
import type { AlertRuleDto, AlertEventDto, CreateAlertRuleRequestDto } from '../types'

export interface UseNotificationsResult {
  rules: AlertRuleDto[]
  alerts: AlertEventDto[]
  loading: boolean
  error: string | null
  /**
   * Whether the live alert stream (`/ws/alerts`) is currently connected.
   * Consumers use this to distinguish a genuinely empty feed from a silently
   * dropped one — a `false` value means newly raised alerts may not be
   * arriving, so an empty list is NOT a guarantee that all is quiet.
   */
  connected: boolean
  createRule: (request: CreateAlertRuleRequestDto) => void
  deleteRule: (ruleId: string) => void
  acknowledgeAlert: (alertId: string, notes?: string) => Promise<void>
  escalateAlert: (
    alertId: string,
    reason: string,
    assignee?: string,
  ) => Promise<void>
  resolveAlert: (alertId: string, resolutionText: string) => Promise<void>
  snoozeAlert: (alertId: string, snoozedUntil: string) => Promise<void>
}

/**
 * Default acknowledger identity when no auth context is available (e.g. unit
 * tests that render the hook in isolation). Production callers should pass the
 * authenticated username from `useAuth()`.
 */
const DEFAULT_USER = 'unknown'

export function useNotifications(username: string | null = null): UseNotificationsResult {
  const [rules, setRules] = useState<AlertRuleDto[]>([])
  const [alerts, setAlerts] = useState<AlertEventDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Live alert push over /ws/alerts (kx-66x). The REST fetch below is a
  // one-shot snapshot; without the stream, alerts raised while the page is
  // open never appear until a reload. `useAlertStream` owns the socket,
  // reconnect/backoff, and polling fallback — we only consume its list.
  const { alerts: streamAlerts, connected } = useAlertStream()

  /**
   * Merged view returned to callers: stream-only alerts (newest first, as
   * `useAlertStream` prepends them) ahead of the REST-fetched list, deduped
   * by alert id. The REST copy wins for ids present in both because the
   * lifecycle actions below apply their optimistic updates to it.
   */
  const mergedAlerts = useMemo(() => {
    if (streamAlerts.length === 0) return alerts
    const known = new Set(alerts.map((a) => a.id))
    const fresh = streamAlerts.filter((a) => !known.has(a.id))
    return fresh.length > 0 ? [...fresh, ...alerts] : alerts
  }, [alerts, streamAlerts])

  /**
   * Optimistically apply `patch` to the alert with `alertId`. An alert that
   * arrived over the live stream only is first copied into local state so
   * the optimistic update (and the later server reconcile) have a row to
   * land on; rolling back to the pre-action snapshot then drops that copy,
   * and the merged view falls back to the untouched stream original.
   */
  const optimisticUpsert = useCallback(
    (alertId: string, patch: Partial<AlertEventDto>) => {
      setAlerts((current) => {
        if (current.some((a) => a.id === alertId)) {
          return current.map((a) =>
            a.id === alertId ? { ...a, ...patch } : a,
          )
        }
        const fromStream = streamAlerts.find((a) => a.id === alertId)
        return fromStream ? [{ ...fromStream, ...patch }, ...current] : current
      })
    },
    [streamAlerts],
  )

  const loadData = useCallback(async () => {
    try {
      const [rulesData, alertsData] = await Promise.all([
        fetchRules(),
        fetchAlerts(),
      ])
      setRules(rulesData)
      setAlerts(alertsData)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadData()
  }, [loadData])

  const createRule = useCallback(async (request: CreateAlertRuleRequestDto) => {
    try {
      await apiCreateRule(request)
      const updatedRules = await fetchRules()
      setRules(updatedRules)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }, [])

  const deleteRule = useCallback(async (ruleId: string) => {
    try {
      await apiDeleteRule(ruleId)
      const updatedRules = await fetchRules()
      setRules(updatedRules)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }, [])

  /**
   * Acknowledge an alert with optimistic update and rollback on error.
   *
   * - Immediately flips the alert's status to ACKNOWLEDGED in local state so
   *   the UI feels responsive.
   * - On API failure, reverts to the previous status and surfaces the error.
   */
  const acknowledgeAlert = useCallback(
    async (alertId: string, notes?: string) => {
      const previous = alerts
      // Optimistic update — flip the matching alert to ACKNOWLEDGED.
      optimisticUpsert(alertId, { status: 'ACKNOWLEDGED' })
      setError(null)
      try {
        const updated = await apiAcknowledgeAlert(
          alertId,
          username ?? DEFAULT_USER,
          notes,
        )
        // Reconcile with the server response (e.g. server-side timestamps).
        setAlerts((current) =>
          current.map((a) => (a.id === alertId ? updated : a)),
        )
      } catch (err) {
        // Rollback.
        setAlerts(previous)
        const message = err instanceof Error ? err.message : String(err)
        setError(message)
        throw err
      }
    },
    [alerts, username, optimisticUpsert],
  )

  /**
   * Manually escalate an alert with optimistic update and rollback on error.
   *
   * Mirrors {@link acknowledgeAlert}: flips the matching alert's status to
   * ESCALATED in local state immediately, reconciles with the server payload
   * on success, and rolls back to the previous list on failure.
   */
  const escalateAlert = useCallback(
    async (alertId: string, reason: string, assignee?: string) => {
      const previous = alerts
      optimisticUpsert(
        alertId,
        assignee !== undefined
          ? { status: 'ESCALATED', escalatedTo: assignee }
          : { status: 'ESCALATED' },
      )
      setError(null)
      try {
        const updated = await apiEscalateAlert(alertId, reason, assignee)
        setAlerts((current) =>
          current.map((a) => (a.id === alertId ? updated : a)),
        )
      } catch (err) {
        setAlerts(previous)
        const message = err instanceof Error ? err.message : String(err)
        setError(message)
        throw err
      }
    },
    [alerts, optimisticUpsert],
  )

  /**
   * Resolve an alert with optimistic update and rollback on error.
   *
   * Mirrors {@link acknowledgeAlert}: flips status to RESOLVED locally,
   * reconciles with the server response, rolls back on failure.
   */
  const resolveAlert = useCallback(
    async (alertId: string, resolutionText: string) => {
      const previous = alerts
      optimisticUpsert(alertId, {
        status: 'RESOLVED',
        resolvedReason: resolutionText,
      })
      setError(null)
      try {
        const updated = await apiResolveAlert(alertId, resolutionText)
        setAlerts((current) =>
          current.map((a) => (a.id === alertId ? updated : a)),
        )
      } catch (err) {
        setAlerts(previous)
        const message = err instanceof Error ? err.message : String(err)
        setError(message)
        throw err
      }
    },
    [alerts, optimisticUpsert],
  )

  /**
   * Snooze an alert until the given ISO-8601 timestamp with optimistic update
   * and rollback on error.
   *
   * Mirrors the other lifecycle actions: sets `snoozedUntil` locally
   * immediately, reconciles with the server response, rolls back on failure.
   * Status itself is not flipped — snoozing leaves the lifecycle state alone
   * (per the backend contract).
   */
  const snoozeAlert = useCallback(
    async (alertId: string, snoozedUntil: string) => {
      const previous = alerts
      optimisticUpsert(alertId, { snoozedUntil })
      setError(null)
      try {
        const updated = await apiSnoozeAlert(alertId, snoozedUntil)
        setAlerts((current) =>
          current.map((a) => (a.id === alertId ? updated : a)),
        )
      } catch (err) {
        setAlerts(previous)
        const message = err instanceof Error ? err.message : String(err)
        setError(message)
        throw err
      }
    },
    [alerts, optimisticUpsert],
  )

  return {
    rules,
    alerts: mergedAlerts,
    loading,
    error,
    connected,
    createRule,
    deleteRule,
    acknowledgeAlert,
    escalateAlert,
    resolveAlert,
    snoozeAlert,
  }
}
