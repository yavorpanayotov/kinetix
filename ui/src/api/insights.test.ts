import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  explainReport,
  explainVar,
  type ExplainReportRequest,
  type ExplainVarRequest,
  type InsightResponse,
} from './insights'

describe('insights API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const varRequest: ExplainVarRequest = {
    method: 'historical',
    confidence: 0.99,
    horizon_days: 1,
    value_usd: 1_250_000,
    top_contributors: [
      { instrument: 'AAPL', contribution_pct: 32.5 },
      { instrument: 'MSFT', contribution_pct: 18.0 },
    ],
    regime: 'risk-off',
  }

  const reportRequest: ExplainReportRequest = {
    template_id: 'daily-risk',
    report_date: '2026-05-18',
    summary_metrics: { var99: 1_250_000, expected_shortfall: 1_500_000 },
    top_drivers: [
      { name: 'EQUITY', contribution_usd: 800_000 },
      { name: 'RATES', contribution_usd: 250_000 },
    ],
    breaches: ['book-1: VaR limit exceeded'],
  }

  const insightResponse: InsightResponse = {
    narrative: 'Risk concentration is elevated in equities.',
    bullets: ['AAPL drove 32.5% of VaR', 'Regime shifted to risk-off'],
    model: 'claude-sonnet-4-6',
    mode: 'live',
  }

  describe('explainVar', () => {
    it('posts to /api/v1/insights/explain/var with JSON body', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(insightResponse),
      })

      await explainVar(varRequest)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/insights/explain/var', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(varRequest),
      })
    })

    it('returns the parsed InsightResponse', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(insightResponse),
      })

      const result = await explainVar(varRequest)

      expect(result).toEqual(insightResponse)
    })

    it('throws on non-ok response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(explainVar(varRequest)).rejects.toThrow(
        'Failed to explain VaR: 500 Internal Server Error',
      )
    })
  })

  describe('explainReport', () => {
    it('posts to /api/v1/insights/explain/report with JSON body', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(insightResponse),
      })

      await explainReport(reportRequest)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/insights/explain/report',
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(reportRequest),
        },
      )
    })

    it('returns the parsed InsightResponse', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(insightResponse),
      })

      const result = await explainReport(reportRequest)

      expect(result).toEqual(insightResponse)
    })

    it('throws on non-ok response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
      })

      await expect(explainReport(reportRequest)).rejects.toThrow(
        'Failed to explain report: 502 Bad Gateway',
      )
    })
  })
})
