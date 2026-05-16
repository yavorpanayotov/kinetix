import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { WhatIfResponseDto } from '../types'

vi.mock('../api/whatIf')

import { runWhatIfAnalysis } from '../api/whatIf'
import { useWhatIf } from './useWhatIf'

const mockRunWhatIfAnalysis = vi.mocked(runWhatIfAnalysis)

const whatIfResponse: WhatIfResponseDto = {
  baseVaR: '100000.00',
  baseExpectedShortfall: '130000.00',
  baseGreeks: {
    bookId: 'book-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '50000.00', gamma: '1200.00', vega: '8000.00' },
    ],
    theta: '-500.00',
    rho: '200.00',
    calculatedAt: '2025-01-15T10:00:00Z',
  },
  basePositionRisk: [],
  hypotheticalVaR: '85000.00',
  hypotheticalExpectedShortfall: '110000.00',
  hypotheticalGreeks: {
    bookId: 'book-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '45000.00', gamma: '1100.00', vega: '7500.00' },
    ],
    theta: '-450.00',
    rho: '180.00',
    calculatedAt: '2025-01-15T10:00:00Z',
  },
  hypotheticalPositionRisk: [],
  varChange: '-15000.00',
  esChange: '-20000.00',
  calculatedAt: '2025-01-15T10:00:00Z',
}

describe('useWhatIf', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    sessionStorage.clear()
  })

  it('initialises with one empty trade in the form', () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    expect(result.current.trades).toHaveLength(1)
    expect(result.current.trades[0].instrumentId).toBe('')
    expect(result.current.trades[0].side).toBe('BUY')
    expect(result.current.trades[0].quantity).toBe('')
    expect(result.current.trades[0].priceAmount).toBe('')
    expect(result.current.result).toBeNull()
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBeNull()
  })

  it('adds a trade to the form', () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.addTrade()
    })

    expect(result.current.trades).toHaveLength(2)
  })

  it('removes a trade from the form', () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.addTrade()
    })

    expect(result.current.trades).toHaveLength(2)

    act(() => {
      result.current.removeTrade(0)
    })

    expect(result.current.trades).toHaveLength(1)
  })

  it('does not remove the last trade', () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    expect(result.current.trades).toHaveLength(1)

    act(() => {
      result.current.removeTrade(0)
    })

    expect(result.current.trades).toHaveLength(1)
  })

  it('updates a trade field', () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
    })

    expect(result.current.trades[0].instrumentId).toBe('SPY')

    act(() => {
      result.current.updateTrade(0, 'side', 'SELL')
    })

    expect(result.current.trades[0].side).toBe('SELL')

    act(() => {
      result.current.updateTrade(0, 'quantity', '100')
    })

    expect(result.current.trades[0].quantity).toBe('100')
  })

  it('submits the form and returns results', async () => {
    mockRunWhatIfAnalysis.mockResolvedValue(whatIfResponse)

    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
      result.current.updateTrade(0, 'assetClass', 'EQUITY')
      result.current.updateTrade(0, 'quantity', '100')
      result.current.updateTrade(0, 'priceAmount', '450.00')
    })

    await act(async () => {
      await result.current.submit()
    })

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.result).toEqual(whatIfResponse)
    expect(mockRunWhatIfAnalysis).toHaveBeenCalledWith('book-1', {
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
    })
  })

  it('sends instrumentType when the form dropdown changes it', async () => {
    // Required so the orchestrator's HypotheticalTradeDto.instrumentType
    // (non-null, parsed via InstrumentTypeCode.fromString) can deserialize.
    mockRunWhatIfAnalysis.mockResolvedValue(whatIfResponse)

    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'AAPL-C-200')
      result.current.updateTrade(0, 'instrumentType', 'EQUITY_OPTION')
      result.current.updateTrade(0, 'assetClass', 'EQUITY')
      result.current.updateTrade(0, 'quantity', '5')
      result.current.updateTrade(0, 'priceAmount', '12.50')
    })

    await act(async () => {
      await result.current.submit()
    })

    expect(mockRunWhatIfAnalysis).toHaveBeenCalledWith(
      'book-1',
      expect.objectContaining({
        hypotheticalTrades: [
          expect.objectContaining({ instrumentType: 'EQUITY_OPTION' }),
        ],
      }),
    )
  })

  it('sets error when submission fails', async () => {
    mockRunWhatIfAnalysis.mockRejectedValue(new Error('Analysis failed'))

    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
      result.current.updateTrade(0, 'assetClass', 'EQUITY')
      result.current.updateTrade(0, 'quantity', '100')
      result.current.updateTrade(0, 'priceAmount', '450.00')
    })

    await act(async () => {
      await result.current.submit()
    })

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('Analysis failed')
    expect(result.current.result).toBeNull()
  })

  it('resets the form state', async () => {
    mockRunWhatIfAnalysis.mockResolvedValue(whatIfResponse)

    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
      result.current.updateTrade(0, 'quantity', '100')
      result.current.updateTrade(0, 'priceAmount', '450.00')
    })

    await act(async () => {
      await result.current.submit()
    })

    await waitFor(() => {
      expect(result.current.result).not.toBeNull()
    })

    act(() => {
      result.current.addTrade()
    })

    expect(result.current.trades).toHaveLength(2)

    act(() => {
      result.current.reset()
    })

    expect(result.current.trades).toHaveLength(1)
    expect(result.current.trades[0].instrumentId).toBe('')
    expect(result.current.result).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('computes impact from results', async () => {
    mockRunWhatIfAnalysis.mockResolvedValue(whatIfResponse)

    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
      result.current.updateTrade(0, 'assetClass', 'EQUITY')
      result.current.updateTrade(0, 'quantity', '100')
      result.current.updateTrade(0, 'priceAmount', '450.00')
    })

    await act(async () => {
      await result.current.submit()
    })

    await waitFor(() => {
      expect(result.current.result).not.toBeNull()
    })

    expect(result.current.impact).not.toBeNull()
    expect(result.current.impact!.varChange).toBe('-15000.00')
    expect(result.current.impact!.esChange).toBe('-20000.00')
    // Delta: base 50000 - hypothetical 45000 = -5000
    expect(result.current.impact!.deltaChange).toBe(-5000)
    // Gamma: base 1200 - hypothetical 1100 = -100
    expect(result.current.impact!.gammaChange).toBe(-100)
    // Vega: base 8000 - hypothetical 7500 = -500
    expect(result.current.impact!.vegaChange).toBe(-500)
    // Theta: hypothetical -450 - base -500 = 50
    expect(result.current.impact!.thetaChange).toBe(50)
    // Rho: hypothetical 180 - base 200 = -20
    expect(result.current.impact!.rhoChange).toBe(-20)
  })

  it('does not submit when bookId is null', async () => {
    const { result } = renderHook(() => useWhatIf(null))

    await act(async () => {
      await result.current.submit()
    })

    expect(mockRunWhatIfAnalysis).not.toHaveBeenCalled()
  })

  it('rejects submission with empty instrumentId and sets validation errors', async () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    // Leave instrumentId empty but fill other fields
    act(() => {
      result.current.updateTrade(0, 'quantity', '100')
      result.current.updateTrade(0, 'priceAmount', '450.00')
    })

    await act(async () => {
      await result.current.submit()
    })

    expect(mockRunWhatIfAnalysis).not.toHaveBeenCalled()
    expect(result.current.validationErrors[0]?.instrumentId).toBeDefined()
  })

  it('rejects submission with non-numeric quantity', async () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
      result.current.updateTrade(0, 'quantity', 'abc')
      result.current.updateTrade(0, 'priceAmount', '450.00')
    })

    await act(async () => {
      await result.current.submit()
    })

    expect(mockRunWhatIfAnalysis).not.toHaveBeenCalled()
    expect(result.current.validationErrors[0]?.quantity).toBeDefined()
  })

  it('rejects submission with negative price', async () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
      result.current.updateTrade(0, 'quantity', '100')
      result.current.updateTrade(0, 'priceAmount', '-10')
    })

    await act(async () => {
      await result.current.submit()
    })

    expect(mockRunWhatIfAnalysis).not.toHaveBeenCalled()
    expect(result.current.validationErrors[0]?.priceAmount).toBeDefined()
  })

  it('clears validation errors on reset', async () => {
    const { result } = renderHook(() => useWhatIf('book-1'))

    // Trigger validation errors
    await act(async () => {
      await result.current.submit()
    })

    expect(Object.keys(result.current.validationErrors).length).toBeGreaterThan(0)

    act(() => {
      result.current.reset()
    })

    expect(result.current.validationErrors).toEqual({})
  })

  it('displays actual error message from failed API call', async () => {
    mockRunWhatIfAnalysis.mockRejectedValue(
      new Error('Failed to run what-if analysis: 502 Bad Gateway'),
    )

    const { result } = renderHook(() => useWhatIf('book-1'))

    act(() => {
      result.current.updateTrade(0, 'instrumentId', 'SPY')
      result.current.updateTrade(0, 'quantity', '100')
      result.current.updateTrade(0, 'priceAmount', '450.00')
    })

    await act(async () => {
      await result.current.submit()
    })

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe(
      'Failed to run what-if analysis: 502 Bad Gateway',
    )
    expect(result.current.result).toBeNull()
  })
})
