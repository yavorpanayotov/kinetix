import type { AlertRuleDto, AlertEventDto, CreateAlertRuleRequestDto } from '../types'
import { authFetch } from '../auth/authFetch'

export async function fetchRules(): Promise<AlertRuleDto[]> {
  const response = await authFetch('/api/v1/notifications/rules')
  if (!response.ok) {
    throw new Error(
      `Failed to fetch rules: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function createRule(
  request: CreateAlertRuleRequestDto,
): Promise<AlertRuleDto> {
  const response = await authFetch('/api/v1/notifications/rules', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!response.ok) {
    throw new Error(
      `Failed to create rule: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function deleteRule(ruleId: string): Promise<void> {
  const response = await authFetch(
    `/api/v1/notifications/rules/${encodeURIComponent(ruleId)}`,
    { method: 'DELETE' },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to delete rule: ${response.status} ${response.statusText}`,
    )
  }
}

export async function fetchAlerts(
  limit?: number,
): Promise<AlertEventDto[]> {
  const params = limit ? `?limit=${limit}` : ''
  const response = await authFetch(`/api/v1/notifications/alerts${params}`)
  if (!response.ok) {
    throw new Error(
      `Failed to fetch alerts: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function fetchEscalatedAlerts(): Promise<AlertEventDto[]> {
  const response = await authFetch('/api/v1/notifications/alerts/escalated')
  if (!response.ok) {
    throw new Error(
      `Failed to fetch escalated alerts: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function acknowledgeAlert(
  alertId: string,
  acknowledgedBy: string,
  notes?: string,
): Promise<AlertEventDto> {
  const response = await authFetch(
    `/api/v1/notifications/alerts/${encodeURIComponent(alertId)}/acknowledge`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ acknowledgedBy, notes }),
    },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to acknowledge alert: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

/**
 * Manually escalate an alert via `POST /api/v1/notifications/alerts/{id}/escalate`.
 *
 * - `reason` is required and must be non-blank (the backend rejects blank reasons
 *   with HTTP 400).
 * - `assignee` is optional; the backend falls back to a severity-based default
 *   assignee when omitted.
 *
 * The server returns the updated alert reflecting the new ESCALATED status and
 * the populated `escalatedAt` / `escalatedTo` fields.
 */
export async function escalateAlert(
  alertId: string,
  reason: string,
  assignee?: string,
): Promise<AlertEventDto> {
  const response = await authFetch(
    `/api/v1/notifications/alerts/${encodeURIComponent(alertId)}/escalate`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason, assignee }),
    },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to escalate alert: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

/**
 * Snooze an alert until the given ISO-8601 timestamp via
 * `POST /api/v1/notifications/alerts/{id}/snooze`.
 *
 * The backend skips firing the rule again until `snoozedUntil` passes. The
 * returned alert reflects the new `snoozedUntil` value. See plan §3.1b.3 /
 * §3.1b.4.
 */
export async function snoozeAlert(
  alertId: string,
  snoozedUntil: string,
): Promise<AlertEventDto> {
  const response = await authFetch(
    `/api/v1/notifications/alerts/${encodeURIComponent(alertId)}/snooze`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ snoozedUntil }),
    },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to snooze alert: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

/**
 * Resolve an alert via `POST /api/v1/notifications/alerts/{id}/resolve`.
 *
 * `resolutionText` is required and must be non-blank — the backend records it
 * on the alert as `resolvedReason`.
 */
export async function resolveAlert(
  alertId: string,
  resolutionText: string,
): Promise<AlertEventDto> {
  const response = await authFetch(
    `/api/v1/notifications/alerts/${encodeURIComponent(alertId)}/resolve`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ resolutionText }),
    },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to resolve alert: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}
