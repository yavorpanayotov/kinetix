import { renderHook, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useRebalancing } from './useRebalancing'
import type { TradeFormEntry } from './useWhatIf'

const mockRebalancingResponse = {
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
  tradeContributions: [
    {
      instrumentId: 'AAPL',
      side: 'SELL',
      quantity: '50',
      marginalVarImpact: '-12000.00',
      executionCost: '42.50',
    },
  ],
  estimatedExecutionCost: '42.50',
  calculatedAt: '2026-03-25T10:00:00Z',
}

const sampleTrades: TradeFormEntry[] = [
  {
    instrumentId: 'AAPL',
    instrumentType: 'CASH_EQUITY',
    assetClass: 'EQUITY',
    side: 'SELL',
    quantity: '50',
    priceAmount: '170.00',
    priceCurrency: 'USD',
    bidAskSpreadBps: '5',
  },
]

describe('useRebalancing', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts with null result and no loading or error', () => {
    const { result } = renderHook(() => useRebalancing())

    expect(result.current.rebalancingResult).toBeNull()
    expect(result.current.rebalancingLoading).toBe(false)
    expect(result.current.rebalancingError).toBeNull()
  })

  it('sets rebalancingResult after successful submission', async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(mockRebalancingResponse),
    })

    const { result } = renderHook(() => useRebalancing())

    await act(async () => {
      await result.current.submitRebalancing('book-1', sampleTrades)
    })

    expect(result.current.rebalancingResult).toEqual(mockRebalancingResponse)
    expect(result.current.rebalancingLoading).toBe(false)
    expect(result.current.rebalancingError).toBeNull()
  })

  it('sends trades with default bidAskSpreadBps of 5 when not specified', async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(mockRebalancingResponse),
    })

    const tradesWithoutSpread: TradeFormEntry[] = [
      { instrumentId: 'AAPL', instrumentType: 'CASH_EQUITY', assetClass: 'EQUITY', side: 'SELL', quantity: '50', priceAmount: '170.00', priceCurrency: 'USD' },
    ]

    const { result } = renderHook(() => useRebalancing())

    await act(async () => {
      await result.current.submitRebalancing('book-1', tradesWithoutSpread)
    })

    const body = JSON.parse((mockFetch.mock.calls[0][1] as RequestInit).body as string)
    expect(body.trades[0].bidAskSpreadBps).toBe(5)
  })

  it('sends the provided bidAskSpreadBps value', async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(mockRebalancingResponse),
    })

    const { result } = renderHook(() => useRebalancing())

    await act(async () => {
      await result.current.submitRebalancing('book-1', sampleTrades)
    })

    const body = JSON.parse((mockFetch.mock.calls[0][1] as RequestInit).body as string)
    expect(body.trades[0].bidAskSpreadBps).toBe(5)
  })

  it('sets rebalancingError on API failure', async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
    })

    const { result } = renderHook(() => useRebalancing())

    await act(async () => {
      await result.current.submitRebalancing('book-1', sampleTrades)
    })

    expect(result.current.rebalancingResult).toBeNull()
    expect(result.current.rebalancingError).not.toBeNull()
  })

  it('clears result and error on resetRebalancing', async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(mockRebalancingResponse),
    })

    const { result } = renderHook(() => useRebalancing())

    await act(async () => {
      await result.current.submitRebalancing('book-1', sampleTrades)
    })

    expect(result.current.rebalancingResult).not.toBeNull()

    act(() => {
      result.current.resetRebalancing()
    })

    expect(result.current.rebalancingResult).toBeNull()
    expect(result.current.rebalancingError).toBeNull()
  })
})
