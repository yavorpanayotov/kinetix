import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlertRuleDto, AlertEventDto } from '../types'

vi.mock('../api/notifications')

import {
  fetchRules,
  createRule,
  deleteRule,
  fetchAlerts,
  acknowledgeAlert,
  escalateAlert,
  resolveAlert,
  snoozeAlert,
} from '../api/notifications'
import { useNotifications } from './useNotifications'

const mockFetchRules = vi.mocked(fetchRules)
const mockCreateRule = vi.mocked(createRule)
const mockDeleteRule = vi.mocked(deleteRule)
const mockFetchAlerts = vi.mocked(fetchAlerts)
const mockAcknowledgeAlert = vi.mocked(acknowledgeAlert)
const mockEscalateAlert = vi.mocked(escalateAlert)
const mockResolveAlert = vi.mocked(resolveAlert)
const mockSnoozeAlert = vi.mocked(snoozeAlert)

const rule: AlertRuleDto = {
  id: 'rule-1',
  name: 'VaR Breach',
  type: 'VAR_BREACH',
  threshold: 1000000,
  operator: 'GREATER_THAN',
  severity: 'CRITICAL',
  channels: ['IN_APP'],
  enabled: true,
}

const alert: AlertEventDto = {
  id: 'alert-1',
  ruleId: 'rule-1',
  ruleName: 'VaR Breach',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR exceeded threshold',
  currentValue: 1500000,
  threshold: 1000000,
  bookId: 'book-1',
  triggeredAt: '2025-01-15T10:30:00Z',
  status: 'TRIGGERED',
}

describe('useNotifications', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('loads rules and alerts on mount', async () => {
    mockFetchRules.mockResolvedValue([rule])
    mockFetchAlerts.mockResolvedValue([alert])

    const { result } = renderHook(() => useNotifications())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.rules).toEqual([rule])
    expect(result.current.alerts).toEqual([alert])
    expect(result.current.error).toBeNull()
  })

  it('sets error on fetch failure', async () => {
    mockFetchRules.mockRejectedValue(new Error('Rules failed'))
    mockFetchAlerts.mockResolvedValue([])

    const { result } = renderHook(() => useNotifications())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('Rules failed')
  })

  it('creates a rule and refreshes rules list', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([])
    mockCreateRule.mockResolvedValue(rule)

    const { result } = renderHook(() => useNotifications())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    mockFetchRules.mockResolvedValue([rule])

    await act(async () => {
      result.current.createRule({
        name: 'VaR Breach',
        type: 'VAR_BREACH',
        threshold: 1000000,
        operator: 'GREATER_THAN',
        severity: 'CRITICAL',
        channels: ['IN_APP'],
      })
    })

    await waitFor(() => {
      expect(result.current.rules).toEqual([rule])
    })

    expect(mockCreateRule).toHaveBeenCalled()
  })

  it('acknowledges an alert optimistically and uses the server response', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    const acknowledged: AlertEventDto = {
      ...alert,
      status: 'ACKNOWLEDGED',
    }
    mockAcknowledgeAlert.mockResolvedValue(acknowledged)

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.alerts[0].status).toBe('TRIGGERED')

    await act(async () => {
      await result.current.acknowledgeAlert('alert-1', 'investigating')
    })

    expect(mockAcknowledgeAlert).toHaveBeenCalledWith(
      'alert-1',
      'alice',
      'investigating',
    )
    expect(result.current.alerts[0].status).toBe('ACKNOWLEDGED')
    expect(result.current.error).toBeNull()
  })

  it('reverts the alert status on acknowledge failure and surfaces an error', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    mockAcknowledgeAlert.mockRejectedValue(new Error('Conflict'))

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    await act(async () => {
      try {
        await result.current.acknowledgeAlert('alert-1')
      } catch {
        // expected
      }
    })

    expect(result.current.alerts[0].status).toBe('TRIGGERED')
    expect(result.current.error).toContain('Conflict')
  })

  it('escalates an alert optimistically and uses the server response', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    const escalated: AlertEventDto = {
      ...alert,
      status: 'ESCALATED',
      escalatedAt: '2025-01-15T11:00:00Z',
      escalatedTo: 'risk-manager',
    }
    mockEscalateAlert.mockResolvedValue(escalated)

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.alerts[0].status).toBe('TRIGGERED')

    await act(async () => {
      await result.current.escalateAlert(
        'alert-1',
        'unack timeout',
        'risk-manager',
      )
    })

    expect(mockEscalateAlert).toHaveBeenCalledWith(
      'alert-1',
      'unack timeout',
      'risk-manager',
    )
    expect(result.current.alerts[0].status).toBe('ESCALATED')
    expect(result.current.alerts[0].escalatedTo).toBe('risk-manager')
    expect(result.current.error).toBeNull()
  })

  it('reverts the alert status on escalate failure and surfaces an error', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    mockEscalateAlert.mockRejectedValue(new Error('Conflict'))

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    await act(async () => {
      try {
        await result.current.escalateAlert('alert-1', 'urgent')
      } catch {
        // expected
      }
    })

    expect(result.current.alerts[0].status).toBe('TRIGGERED')
    expect(result.current.error).toContain('Conflict')
  })

  it('resolves an alert optimistically and uses the server response', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    const resolved: AlertEventDto = {
      ...alert,
      status: 'RESOLVED',
      resolvedAt: '2025-01-15T11:00:00Z',
      resolvedReason: 'positions reduced',
    }
    mockResolveAlert.mockResolvedValue(resolved)

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.alerts[0].status).toBe('TRIGGERED')

    await act(async () => {
      await result.current.resolveAlert('alert-1', 'positions reduced')
    })

    expect(mockResolveAlert).toHaveBeenCalledWith(
      'alert-1',
      'positions reduced',
    )
    expect(result.current.alerts[0].status).toBe('RESOLVED')
    expect(result.current.alerts[0].resolvedReason).toBe('positions reduced')
    expect(result.current.error).toBeNull()
  })

  it('reverts the alert status on resolve failure and surfaces an error', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    mockResolveAlert.mockRejectedValue(new Error('Conflict'))

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    await act(async () => {
      try {
        await result.current.resolveAlert('alert-1', 'done')
      } catch {
        // expected
      }
    })

    expect(result.current.alerts[0].status).toBe('TRIGGERED')
    expect(result.current.error).toContain('Conflict')
  })

  it('snoozes an alert optimistically and uses the server response', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    const snoozedUntil = '2025-01-15T11:00:00Z'
    const snoozed: AlertEventDto = {
      ...alert,
      snoozedUntil,
    }
    mockSnoozeAlert.mockResolvedValue(snoozed)

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.alerts[0].snoozedUntil).toBeUndefined()

    await act(async () => {
      await result.current.snoozeAlert('alert-1', snoozedUntil)
    })

    expect(mockSnoozeAlert).toHaveBeenCalledWith('alert-1', snoozedUntil)
    expect(result.current.alerts[0].snoozedUntil).toBe(snoozedUntil)
    expect(result.current.error).toBeNull()
  })

  it('reverts the alert snoozedUntil on snooze failure and surfaces an error', async () => {
    mockFetchRules.mockResolvedValue([])
    mockFetchAlerts.mockResolvedValue([alert])
    mockSnoozeAlert.mockRejectedValue(new Error('Conflict'))

    const { result } = renderHook(() => useNotifications('alice'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    await act(async () => {
      try {
        await result.current.snoozeAlert('alert-1', '2025-01-15T11:00:00Z')
      } catch {
        // expected
      }
    })

    expect(result.current.alerts[0].snoozedUntil).toBeUndefined()
    expect(result.current.error).toContain('Conflict')
  })

  it('deletes a rule and refreshes rules list', async () => {
    mockFetchRules.mockResolvedValue([rule])
    mockFetchAlerts.mockResolvedValue([])
    mockDeleteRule.mockResolvedValue(undefined)

    const { result } = renderHook(() => useNotifications())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    mockFetchRules.mockResolvedValue([])

    await act(async () => {
      result.current.deleteRule('rule-1')
    })

    await waitFor(() => {
      expect(result.current.rules).toEqual([])
    })

    expect(mockDeleteRule).toHaveBeenCalledWith('rule-1')
  })
})
