import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchReportTemplates,
  generateReport,
  fetchReportOutput,
  downloadReportCsv,
  fetchRecentReports,
} from './reports'

describe('reports API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const template = {
    templateId: 'tpl-risk-summary',
    name: 'Risk Summary',
    templateType: 'RISK_SUMMARY',
    ownerUserId: 'SYSTEM',
    description: 'Per-book VaR and Greeks',
    source: 'risk_positions_flat',
  }

  const output = {
    outputId: 'out-abc',
    templateId: 'tpl-risk-summary',
    generatedAt: '2025-01-15T10:00:00Z',
    outputFormat: 'JSON',
    rowCount: 2,
  }

  describe('fetchReportTemplates', () => {
    it('returns list of templates', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([template]),
      })

      const result = await fetchReportTemplates()

      expect(result).toEqual([template])
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/reports/templates')
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchReportTemplates()).rejects.toThrow(
        'Failed to fetch report templates: 500 Internal Server Error',
      )
    })
  })

  describe('generateReport', () => {
    it('sends POST with request body and returns output', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(output),
      })

      const request = { templateId: 'tpl-risk-summary', bookId: 'BOOK-1', format: 'JSON' }
      const result = await generateReport(request)

      expect(result).toEqual(output)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/reports/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      })
    })

    it('sends optional date field when provided', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(output),
      })

      const request = { templateId: 'tpl-risk-summary', bookId: 'BOOK-1', date: '2025-01-15' }
      await generateReport(request)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/reports/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      })
    })

    it('throws on 422', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 422,
        statusText: 'Unprocessable Entity',
      })

      await expect(generateReport({ templateId: 'tpl-missing', bookId: 'BOOK-1' })).rejects.toThrow(
        'Failed to generate report: 422 Unprocessable Entity',
      )
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(generateReport({ templateId: 'tpl-risk-summary', bookId: 'BOOK-1' })).rejects.toThrow(
        'Failed to generate report: 500 Internal Server Error',
      )
    })

    // Plan §4.3 — when the gateway returns its canonical ErrorResponse
    // `{error, message}` body (the live deploy emits exactly that
    // `{"error":"upstream_error","message":"Report generation failed"}`
    // shape for the Reports 500), the api layer must extract the
    // `message` field so the ReportsTab toast can render it verbatim.
    it('includes the upstream message field from the gateway ErrorResponse body in the thrown error', async () => {
      const errorBody = {
        error: 'upstream_error',
        message: 'Report generation failed',
      }
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        clone: () => ({
          json: () => Promise.resolve(errorBody),
        }),
      })

      await expect(
        generateReport({ templateId: 'tpl-risk-summary', bookId: 'BOOK-1' }),
      ).rejects.toThrow('Failed to generate report: Report generation failed')
    })

    it('falls back to the HTTP status when the upstream body is not a valid ErrorResponse', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
        clone: () => ({
          json: () => Promise.reject(new SyntaxError('Unexpected token <')),
        }),
      })

      await expect(
        generateReport({ templateId: 'tpl-risk-summary', bookId: 'BOOK-1' }),
      ).rejects.toThrow('Failed to generate report: 502 Bad Gateway')
    })
  })

  describe('fetchReportOutput', () => {
    it('returns output for existing outputId', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(output),
      })

      const result = await fetchReportOutput('out-abc')

      expect(result).toEqual(output)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/reports/out-abc')
    })

    it('returns null on 404', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      const result = await fetchReportOutput('missing')

      expect(result).toBeNull()
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchReportOutput('out-abc')).rejects.toThrow(
        'Failed to fetch report output: 500 Internal Server Error',
      )
    })
  })

  describe('downloadReportCsv', () => {
    it('returns CSV text for existing outputId', async () => {
      const csv = 'book_id,instrument_id\nBOOK-1,AAPL'
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        text: () => Promise.resolve(csv),
      })

      const result = await downloadReportCsv('out-abc')

      expect(result).toBe(csv)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/reports/out-abc/csv')
    })

    it('returns null on 404', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      const result = await downloadReportCsv('missing')

      expect(result).toBeNull()
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(downloadReportCsv('out-abc')).rejects.toThrow(
        'Failed to download report CSV: 500 Internal Server Error',
      )
    })
  })

  describe('fetchRecentReports', () => {
    const recent = {
      outputId: 'out-3',
      templateId: 'tpl-risk-summary',
      timestamp: '2026-05-28T10:30:00Z',
      user: 'trader1',
      status: 'COMPLETE',
      downloadUrl: '/api/v1/reports/out-3/csv',
      rowCount: 42,
    }

    it('returns the list of recent reports', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([recent]),
      })

      const result = await fetchRecentReports()

      expect(result).toEqual([recent])
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/reports/recent')
    })

    it('forwards the limit query parameter when provided', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchRecentReports(5)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/reports/recent?limit=5')
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchRecentReports()).rejects.toThrow(
        'Failed to fetch recent reports: 500 Internal Server Error',
      )
    })
  })
})
