import { useCallback, useEffect, useState } from 'react'
import {
  fetchRules,
  createRule as apiCreateRule,
  deleteRule as apiDeleteRule,
  fetchAlerts,
  acknowledgeAlert as apiAcknowledgeAlert,
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

  return {
    rules,
    alerts,
    loading,
    error,
    createRule,
    deleteRule,
    acknowledgeAlert,
  }
}
