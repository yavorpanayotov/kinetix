import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuditEventDto, AuditVerifyResultDto } from './audit'
import { fetchAuditEvents, verifyAuditChain } from './audit'

describe('audit API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const sampleEvent: AuditEventDto = {
    id: 42,
    tradeId: 'trd-1',
    bookId: 'book-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '100',
    priceAmount: '150.00',
    priceCurrency: 'USD',
    tradedAt: '2026-05-19T09:00:00Z',
    receivedAt: '2026-05-19T09:00:01Z',
    previousHash: 'abc',
    recordHash: 'def',
    userId: 'alice',
    userRole: 'TRADER',
    eventType: 'TRADE_BOOKED',
    modelName: null,
    scenarioId: null,
    limitId: null,
    submissionId: null,
    details: null,
    sequenceNumber: 7,
  }

  describe('fetchAuditEvents', () => {
    it('GETs /api/v1/audit/events with no query string when no filters are given', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleEvent]),
      })

      const result = await fetchAuditEvents()

      expect(result).toEqual([sampleEvent])
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/audit/events')
    })

    it('parses a successful response into the typed shape', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleEvent]),
      })

      const result = await fetchAuditEvents()

      expect(result).toHaveLength(1)
      expect(result[0].id).toBe(42)
      expect(result[0].eventType).toBe('TRADE_BOOKED')
    })

    it('returns an empty list when there are no events', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      const result = await fetchAuditEvents({ bookId: 'unknown' })

      expect(result).toEqual([])
    })

    it('appends bookId as a query param', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchAuditEvents({ bookId: 'book-1' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/audit/events?bookId=book-1',
      )
    })

    it('appends tradeId as a query param', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchAuditEvents({ tradeId: 'trd-1' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/audit/events?tradeId=trd-1',
      )
    })

    it('appends eventType as a query param', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchAuditEvents({ eventType: 'TRADE_BOOKED' })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/audit/events?eventType=TRADE_BOOKED',
      )
    })

    it('appends from and to time-range params', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchAuditEvents({
        from: '2026-05-01T00:00:00Z',
        to: '2026-05-20T00:00:00Z',
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/audit/events?from=2026-05-01T00%3A00%3A00Z&to=2026-05-20T00%3A00%3A00Z',
      )
    })

    it('appends afterId and limit cursor pagination params', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchAuditEvents({ afterId: 100, limit: 50 })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/audit/events?afterId=100&limit=50',
      )
    })

    it('combines every filter into a single query string', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchAuditEvents({
        bookId: 'book-1',
        tradeId: 'trd-1',
        eventType: 'TRADE_BOOKED',
        from: '2026-05-01T00:00:00Z',
        to: '2026-05-20T00:00:00Z',
        afterId: 100,
        limit: 50,
      })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/audit/events?bookId=book-1&tradeId=trd-1&eventType=TRADE_BOOKED' +
          '&from=2026-05-01T00%3A00%3A00Z&to=2026-05-20T00%3A00%3A00Z' +
          '&afterId=100&limit=50',
      )
    })

    it('omits absent filters from the query string', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchAuditEvents({ bookId: 'book-1', limit: 25 })

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/audit/events?bookId=book-1&limit=25',
      )
    })

    it('throws with status and statusText on a 500 response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchAuditEvents()).rejects.toThrow(
        'Failed to fetch audit events: 500 Internal Server Error',
      )
    })

    it('throws with status and statusText on a 400 response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
      })

      await expect(
        fetchAuditEvents({ eventType: 'NOT_A_TYPE' }),
      ).rejects.toThrow('Failed to fetch audit events: 400 Bad Request')
    })
  })

  describe('verifyAuditChain', () => {
    it('GETs /api/v1/audit/verify and returns the parsed result', async () => {
      const verifyResult: AuditVerifyResultDto = {
        valid: true,
        eventCount: 1234,
      }
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(verifyResult),
      })

      const result = await verifyAuditChain()

      expect(result).toEqual(verifyResult)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/audit/verify')
    })

    it('parses an invalid-chain result', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ valid: false, eventCount: 9 }),
      })

      const result = await verifyAuditChain()

      expect(result.valid).toBe(false)
      expect(result.eventCount).toBe(9)
    })

    it('throws with status and statusText on a 503 response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 503,
        statusText: 'Service Unavailable',
      })

      await expect(verifyAuditChain()).rejects.toThrow(
        'Failed to verify audit chain: 503 Service Unavailable',
      )
    })
  })
})
