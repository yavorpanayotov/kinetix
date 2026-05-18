import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchRules,
  createRule,
  deleteRule,
  fetchAlerts,
  fetchEscalatedAlerts,
  acknowledgeAlert,
} from './notifications'

describe('notifications API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const sampleRule = {
    id: 'rule-1',
    name: 'VaR Limit',
    type: 'VAR_BREACH',
    threshold: 100000,
    operator: 'GREATER_THAN',
    severity: 'CRITICAL',
    channels: ['IN_APP', 'EMAIL'],
    enabled: true,
  }

  const sampleAlert = {
    id: 'evt-1',
    ruleId: 'rule-1',
    ruleName: 'VaR Limit',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR exceeded threshold',
    currentValue: 150000,
    threshold: 100000,
    bookId: 'book-1',
    triggeredAt: '2025-01-15T10:00:00Z',
  }

  describe('fetchRules', () => {
    it('returns array', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleRule]),
      })

      const result = await fetchRules()

      expect(result).toEqual([sampleRule])
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/rules')
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchRules()).rejects.toThrow(
        'Failed to fetch rules: 500 Internal Server Error',
      )
    })
  })

  describe('createRule', () => {
    it('sends POST', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 201,
        json: () => Promise.resolve(sampleRule),
      })

      const request = {
        name: 'VaR Limit',
        type: 'VAR_BREACH',
        threshold: 100000,
        operator: 'GREATER_THAN',
        severity: 'CRITICAL',
        channels: ['IN_APP', 'EMAIL'],
      }
      const result = await createRule(request)

      expect(result).toEqual(sampleRule)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      })
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(
        createRule({
          name: 'test',
          type: 'VAR_BREACH',
          threshold: 100000,
          operator: 'GREATER_THAN',
          severity: 'CRITICAL',
          channels: ['IN_APP'],
        }),
      ).rejects.toThrow('Failed to create rule: 500 Internal Server Error')
    })
  })

  describe('deleteRule', () => {
    it('sends DELETE', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 204,
      })

      await deleteRule('rule-1')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/notifications/rules/rule-1',
        { method: 'DELETE' },
      )
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(deleteRule('rule-1')).rejects.toThrow(
        'Failed to delete rule: 500 Internal Server Error',
      )
    })
  })

  describe('fetchAlerts', () => {
    it('returns array', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleAlert]),
      })

      const result = await fetchAlerts()

      expect(result).toEqual([sampleAlert])
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/alerts')
    })

    it('passes limit parameter', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleAlert]),
      })

      await fetchAlerts(10)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/notifications/alerts?limit=10',
      )
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchAlerts()).rejects.toThrow(
        'Failed to fetch alerts: 500 Internal Server Error',
      )
    })
  })

  describe('acknowledgeAlert', () => {
    it('sends POST to the acknowledge endpoint with acknowledgedBy and notes', async () => {
      const acknowledgedAlert = { ...sampleAlert, status: 'ACKNOWLEDGED' }
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(acknowledgedAlert),
      })

      const result = await acknowledgeAlert('evt-1', 'alice', 'investigating')

      expect(result).toEqual(acknowledgedAlert)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/notifications/alerts/evt-1/acknowledge',
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ acknowledgedBy: 'alice', notes: 'investigating' }),
        },
      )
    })

    it('omits notes when not provided', async () => {
      const acknowledgedAlert = { ...sampleAlert, status: 'ACKNOWLEDGED' }
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(acknowledgedAlert),
      })

      await acknowledgeAlert('evt-1', 'alice')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/notifications/alerts/evt-1/acknowledge',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ acknowledgedBy: 'alice', notes: undefined }),
        }),
      )
    })

    it('url-encodes the alert id', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(sampleAlert),
      })

      await acknowledgeAlert('alert with space', 'alice')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/notifications/alerts/alert%20with%20space/acknowledge',
        expect.anything(),
      )
    })

    it('throws on non-2xx', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 409,
        statusText: 'Conflict',
      })

      await expect(acknowledgeAlert('evt-1', 'alice')).rejects.toThrow(
        'Failed to acknowledge alert: 409 Conflict',
      )
    })
  })

  describe('fetchEscalatedAlerts', () => {
    it('fetches from escalated endpoint', async () => {
      const escalatedAlert = {
        ...sampleAlert,
        id: 'esc-1',
        status: 'ESCALATED',
        escalatedAt: '2025-01-15T09:35:00Z',
        escalatedTo: 'risk-manager,cro',
      }
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([escalatedAlert]),
      })

      const result = await fetchEscalatedAlerts()

      expect(result).toEqual([escalatedAlert])
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/notifications/alerts/escalated')
    })

    it('throws on failure', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 503,
        statusText: 'Service Unavailable',
      })

      await expect(fetchEscalatedAlerts()).rejects.toThrow(
        'Failed to fetch escalated alerts: 503 Service Unavailable',
      )
    })
  })
})
