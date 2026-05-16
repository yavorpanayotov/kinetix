import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { runWhatIfAnalysis, runRebalancingAnalysis } from './whatIf'
import type { WhatIfRequestDto, WhatIfResponseDto, RebalancingRequestDto, RebalancingResponseDto } from '../types'

describe('whatIf API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const whatIfResponse: WhatIfResponseDto = {
    baseVaR: '100000.00',
    baseExpectedShortfall: '130000.00',
    baseGreeks: null,
    basePositionRisk: [],
    hypotheticalVaR: '85000.00',
    hypotheticalExpectedShortfall: '110000.00',
    hypotheticalGreeks: null,
    hypotheticalPositionRisk: [],
    varChange: '-15000.00',
    esChange: '-20000.00',
    calculatedAt: '2025-01-15T10:00:00Z',
  }

  const request: WhatIfRequestDto = {
    hypotheticalTrades: [
      {
        instrumentId: 'SPY',
        instrumentType: 'CASH_EQUITY',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '100',
        priceAmount: '450.00',
        priceCurrency: 'USD',
      },
    ],
  }

  describe('runWhatIfAnalysis', () => {
    it('sends POST with request body and returns result', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(whatIfResponse),
      })

      const result = await runWhatIfAnalysis('book-1', request)

      expect(result).toEqual(whatIfResponse)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/risk/what-if/book-1', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      })
    })

    it('URL-encodes the bookId', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(whatIfResponse),
      })

      await runWhatIfAnalysis('book/special & id', request)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/risk/what-if/book%2Fspecial%20%26%20id',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    it('throws on 404', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      await expect(runWhatIfAnalysis('book-1', request)).rejects.toThrow(
        'Failed to run what-if analysis: 404 Not Found',
      )
    })

    it('throws on 500', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(runWhatIfAnalysis('book-1', request)).rejects.toThrow(
        'Failed to run what-if analysis: 500 Internal Server Error',
      )
    })
  })

  describe('runRebalancingAnalysis', () => {
    const rebalancingRequest: RebalancingRequestDto = {
      trades: [
        {
          instrumentId: 'AAPL',
          assetClass: 'EQUITY',
          side: 'SELL',
          quantity: '50',
          priceAmount: '170.00',
          priceCurrency: 'USD',
          bidAskSpreadBps: 5,
        },
      ],
    }

    const rebalancingResponse: RebalancingResponseDto = {
      baseVar: '100000.00',
      rebalancedVar: '80000.00',
      varChange: '-20000.00',
      varChangePct: '-20.00',
      baseExpectedShortfall: '130000.00',
      rebalancedExpectedShortfall: '104000.00',
      esChange: '-26000.00',
      baseGreeks: null,
      rebalancedGreeks: null,
      greeksChange: {
        deltaChange: '-5000.000000',
        gammaChange: '-100.000000',
        vegaChange: '-500.000000',
        thetaChange: '50.000000',
        rhoChange: '-20.000000',
      },
      tradeContributions: [],
      estimatedExecutionCost: '42.50',
      calculatedAt: '2026-03-25T10:00:00Z',
    }

    it('sends POST to rebalance endpoint with request body and returns result', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(rebalancingResponse),
      })

      const result = await runRebalancingAnalysis('book-1', rebalancingRequest)

      expect(result).toEqual(rebalancingResponse)
      expect(mockFetch).toHaveBeenCalledWith('/api/v1/risk/what-if/book-1/rebalance', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(rebalancingRequest),
      })
    })

    it('URL-encodes the bookId in the rebalance endpoint', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(rebalancingResponse),
      })

      await runRebalancingAnalysis('book/special & id', rebalancingRequest)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/risk/what-if/book%2Fspecial%20%26%20id/rebalance',
        expect.objectContaining({ method: 'POST' }),
      )
    })

    it('throws FetchError on non-ok response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
      })

      await expect(runRebalancingAnalysis('book-1', rebalancingRequest)).rejects.toThrow(
        'Failed to run rebalancing analysis: 502 Bad Gateway',
      )
    })
  })
})
