import { useCallback, useEffect, useState } from 'react'
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
      setAlerts((current) =>
        current.map((a) =>
          a.id === alertId ? { ...a, status: 'ACKNOWLEDGED' } : a,
        ),
      )
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
    [alerts, username],
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
      setAlerts((current) =>
        current.map((a) =>
          a.id === alertId
            ? {
                ...a,
                status: 'ESCALATED',
                escalatedTo: assignee ?? a.escalatedTo,
              }
            : a,
        ),
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
    [alerts],
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
      setAlerts((current) =>
        current.map((a) =>
          a.id === alertId
            ? { ...a, status: 'RESOLVED', resolvedReason: resolutionText }
            : a,
        ),
      )
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
    [alerts],
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
      setAlerts((current) =>
        current.map((a) =>
          a.id === alertId ? { ...a, snoozedUntil } : a,
        ),
      )
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
    [alerts],
  )

  return {
    rules,
    alerts,
    loading,
    error,
    createRule,
    deleteRule,
    acknowledgeAlert,
    escalateAlert,
    resolveAlert,
    snoozeAlert,
  }
}
