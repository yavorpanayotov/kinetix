import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchAllCounterpartyExposures,
  fetchCounterpartyExposure,
  fetchCounterpartyExposureHistory,
  triggerPFEComputation,
  triggerCVAComputation,
} from './counterpartyRisk'

describe('counterpartyRisk API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  const sampleExposure = {
    counterpartyId: 'CP-GS',
    calculatedAt: '2026-03-24T10:00:00Z',
    currentNetExposure: 2_000_000,
    peakPfe: 1_800_000,
    cva: 12_500,
    cvaEstimated: false,
    currency: 'USD',
    pfeProfile: [],
  }

  // ---------------------------------------------------------------------------
  // fetchAllCounterpartyExposures
  // ---------------------------------------------------------------------------

  describe('fetchAllCounterpartyExposures', () => {
    it('returns parsed array on 200', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleExposure]),
      })

      const result = await fetchAllCounterpartyExposures()

      expect(result).toHaveLength(1)
      expect(result[0].counterpartyId).toBe('CP-GS')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/counterparty-risk',
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      )
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchAllCounterpartyExposures()).rejects.toThrow(
        'Failed to fetch counterparty exposures: 500 Internal Server Error',
      )
    })

    it('rejects when the gateway hangs past the client timeout', async () => {
      // Simulate a gateway that never responds: fetch only settles when the
      // AbortController fires. Without a client-side timeout this would hang
      // forever, leaving the UI stuck on a perpetual spinner (kx-qfqn).
      mockFetch.mockImplementation((_input: RequestInfo | URL, init?: RequestInit) => {
        return new Promise((_resolve, reject) => {
          const signal = init?.signal
          if (signal) {
            signal.addEventListener('abort', () => {
              reject(new DOMException('Aborted', 'AbortError'))
            })
          }
        })
      })

      vi.useFakeTimers()
      const promise = fetchAllCounterpartyExposures()
      const assertion = expect(promise).rejects.toThrow()
      await vi.advanceTimersByTimeAsync(20_000)
      await assertion
      // The request must have been issued with an abort signal.
      expect(mockFetch.mock.calls[0]?.[1]?.signal).toBeInstanceOf(AbortSignal)
    })
  })

  // ---------------------------------------------------------------------------
  // fetchCounterpartyExposure
  // ---------------------------------------------------------------------------

  describe('fetchCounterpartyExposure', () => {
    it('returns parsed object on 200', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(sampleExposure),
      })

      const result = await fetchCounterpartyExposure('CP-GS')

      expect(result?.counterpartyId).toBe('CP-GS')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/counterparty-risk/CP-GS',
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      )
    })

    it('returns null on 404', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      const result = await fetchCounterpartyExposure('CP-UNKNOWN')

      expect(result).toBeNull()
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchCounterpartyExposure('CP-GS')).rejects.toThrow(
        'Failed to fetch counterparty exposure: 500 Internal Server Error',
      )
    })
  })

  // ---------------------------------------------------------------------------
  // fetchCounterpartyExposureHistory
  // ---------------------------------------------------------------------------

  describe('fetchCounterpartyExposureHistory', () => {
    it('returns list with default limit of 90', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([sampleExposure]),
      })

      const result = await fetchCounterpartyExposureHistory('CP-GS')

      expect(result).toHaveLength(1)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/counterparty-risk/CP-GS/history?limit=90',
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      )
    })

    it('passes custom limit', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve([]),
      })

      await fetchCounterpartyExposureHistory('CP-GS', 30)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/counterparty-risk/CP-GS/history?limit=30',
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      )
    })
  })

  // ---------------------------------------------------------------------------
  // triggerPFEComputation
  // ---------------------------------------------------------------------------

  describe('triggerPFEComputation', () => {
    it('posts to pfe endpoint and returns result', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(sampleExposure),
      })

      const result = await triggerPFEComputation('CP-GS')

      expect(result.counterpartyId).toBe('CP-GS')
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/counterparty-risk/CP-GS/pfe',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    it('throws on error with message from body', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: () => Promise.resolve({ message: 'Counterparty not found' }),
      })

      await expect(triggerPFEComputation('CP-UNKNOWN')).rejects.toThrow('Counterparty not found')
    })

    it('throws with status text when body has no message', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: () => Promise.reject(new Error('no json')),
      })

      await expect(triggerPFEComputation('CP-UNKNOWN')).rejects.toThrow('400 Bad Request')
    })
  })

  // ---------------------------------------------------------------------------
  // triggerCVAComputation
  // ---------------------------------------------------------------------------

  describe('triggerCVAComputation', () => {
    it('posts to cva endpoint and returns result', async () => {
      const withCva = { ...sampleExposure, cva: 15_000 }
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(withCva),
      })

      const result = await triggerCVAComputation('CP-GS')

      expect(result?.cva).toBe(15_000)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/counterparty-risk/CP-GS/cva',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    it('returns null on 404 (no PFE snapshot)', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      const result = await triggerCVAComputation('CP-NEW')

      expect(result).toBeNull()
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(triggerCVAComputation('CP-GS')).rejects.toThrow(
        'Failed to compute CVA: 500 Internal Server Error',
      )
    })
  })
})
