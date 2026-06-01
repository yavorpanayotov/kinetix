import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchScenarios, getLatestStressBatch, runStressTest } from './stress'

describe('stress API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const stressResult = {
    scenarioName: 'GFC_2008',
    baseVar: '100000.00',
    stressedVar: '300000.00',
    pnlImpact: '-550000.00',
    assetClassImpacts: [
      { assetClass: 'EQUITY', baseExposure: '1000000.00', stressedExposure: '600000.00', pnlImpact: '-400000.00' },
    ],
    calculatedAt: '2025-01-15T10:00:00Z',
  }

  describe('fetchScenarios', () => {
    it('returns array of scenario names', async () => {
      const scenarios = ['GFC_2008', 'COVID_2020', 'TAPER_TANTRUM_2013', 'EURO_CRISIS_2011']
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(scenarios),
      })

      const result = await fetchScenarios()

      expect(result).toEqual(scenarios)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/risk/stress/scenarios')
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(fetchScenarios()).rejects.toThrow(
        'Failed to fetch scenarios: 500 Internal Server Error',
      )
    })
  })

  describe('runStressTest', () => {
    it('sends POST and returns result', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(stressResult),
      })

      const result = await runStressTest('book-1', 'GFC_2008')

      expect(result).toEqual(stressResult)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/risk/stress/book-1', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scenarioName: 'GFC_2008' }),
      })
    })

    it('returns null on 404', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      const result = await runStressTest('book-1', 'NONEXISTENT')

      expect(result).toBeNull()
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(runStressTest('book-1', 'GFC_2008')).rejects.toThrow(
        'Failed to run stress test: 500 Internal Server Error',
      )
    })
  })

  describe('getLatestStressBatch', () => {
    const batch = {
      results: [
        { scenarioName: 'GFC_2008', baseVar: '50000.00', stressedVar: '80000.00', pnlImpact: '-400000.00' },
        { scenarioName: 'COVID_2020', baseVar: '50000.00', stressedVar: '70000.00', pnlImpact: '-150000.00' },
      ],
      failedScenarios: [],
      worstScenarioName: 'GFC_2008',
      worstPnlImpact: '-400000.00',
    }

    it('GETs the stored batch and returns it', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(batch),
      })

      const result = await getLatestStressBatch('book-1')

      expect(result).toEqual(batch)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/risk/stress/book-1/batch')
    })

    it('returns null on 404 (no batch stored yet)', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      const result = await getLatestStressBatch('empty-book')

      expect(result).toBeNull()
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(getLatestStressBatch('book-1')).rejects.toThrow(
        'Failed to fetch latest stress batch: 500 Internal Server Error',
      )
    })
  })
})
