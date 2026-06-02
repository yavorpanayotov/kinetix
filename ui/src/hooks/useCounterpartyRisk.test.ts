import { renderHook, waitFor, act } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCounterpartyRisk } from './useCounterpartyRisk'

vi.mock('../api/counterpartyRisk', () => ({
  fetchAllCounterpartyExposures: vi.fn(),
  fetchCounterpartyExposure: vi.fn(),
  fetchCounterpartyExposureHistory: vi.fn(),
  triggerPFEComputation: vi.fn(),
  triggerCVAComputation: vi.fn(),
}))

import {
  fetchAllCounterpartyExposures,
  fetchCounterpartyExposure,
  fetchCounterpartyExposureHistory,
  triggerPFEComputation,
  triggerCVAComputation,
} from '../api/counterpartyRisk'

const mockFetchAll = vi.mocked(fetchAllCounterpartyExposures)
const mockFetchOne = vi.mocked(fetchCounterpartyExposure)
const mockFetchHistory = vi.mocked(fetchCounterpartyExposureHistory)
const mockTriggerPFE = vi.mocked(triggerPFEComputation)
const mockTriggerCVA = vi.mocked(triggerCVAComputation)

const SAMPLE_EXPOSURE = {
  counterpartyId: 'CP-GS',
  calculatedAt: '2026-03-24T10:00:00Z',
  currentNetExposure: 2_000_000,
  peakPfe: 1_800_000,
  cva: 12_500,
  cvaEstimated: false,
  currency: 'USD',
  pfeProfile: [
    { tenor: '1Y', tenorYears: 1, expectedExposure: 1_500_000, pfe95: 1_800_000, pfe99: 2_000_000 },
    { tenor: '2Y', tenorYears: 2, expectedExposure: 1_200_000, pfe95: 1_500_000, pfe99: 1_700_000 },
  ],
}

describe('useCounterpartyRisk', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFetchAll.mockResolvedValue([])
  })

  afterEach(() => {
    vi.resetAllMocks()
  })

  it('loads all exposures on mount', async () => {
    mockFetchAll.mockResolvedValue([SAMPLE_EXPOSURE])

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.exposures).toHaveLength(1)
    expect(result.current.exposures[0].counterpartyId).toBe('CP-GS')
  })

  it('sets error when load fails', async () => {
    mockFetchAll.mockRejectedValue(new Error('Network error'))

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('Network error')
    expect(result.current.exposures).toHaveLength(0)
  })

  it('clears the spinner and surfaces an error when the fetch times out (no perpetual spinner)', async () => {
    // A hung gateway: the API layer enforces a client-side timeout and rejects
    // with an abort error. The hook must end with loading=false and error set
    // rather than spinning forever (kx-qfqn).
    mockFetchAll.mockRejectedValue(new DOMException('Aborted', 'AbortError'))

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).not.toBeNull()
    expect(result.current.exposures).toHaveLength(0)
  })

  it('selectCounterparty fetches detail and history', async () => {
    mockFetchOne.mockResolvedValue(SAMPLE_EXPOSURE)
    mockFetchHistory.mockResolvedValue([SAMPLE_EXPOSURE])

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => {
      result.current.selectCounterparty('CP-GS')
    })

    await waitFor(() => {
      expect(result.current.selected).not.toBeNull()
    })

    expect(result.current.selected?.counterpartyId).toBe('CP-GS')
    expect(result.current.history).toHaveLength(1)
    expect(mockFetchOne).toHaveBeenCalledWith('CP-GS')
    expect(mockFetchHistory).toHaveBeenCalledWith('CP-GS')
  })

  it('computePFE triggers computation and updates selected', async () => {
    const updatedExposure = { ...SAMPLE_EXPOSURE, peakPfe: 2_200_000 }
    mockTriggerPFE.mockResolvedValue(updatedExposure)
    mockFetchAll.mockResolvedValue([SAMPLE_EXPOSURE])

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => {
      await result.current.computePFE('CP-GS')
    })

    expect(result.current.selected?.peakPfe).toBe(2_200_000)
    expect(result.current.computing).toBe(false)
  })

  it('computeCVA updates selected when PFE snapshot exists', async () => {
    const withCva = { ...SAMPLE_EXPOSURE, cva: 15_000 }
    mockTriggerCVA.mockResolvedValue(withCva)

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => {
      await result.current.computeCVA('CP-GS')
    })

    expect(result.current.selected?.cva).toBe(15_000)
    expect(result.current.computing).toBe(false)
  })

  it('computeCVA sets error when no PFE snapshot exists', async () => {
    mockTriggerCVA.mockResolvedValue(null)

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => {
      await result.current.computeCVA('CP-NEW')
    })

    expect(result.current.error).toContain('No PFE snapshot')
    expect(result.current.selected).toBeNull()
  })

  it('refresh reloads all exposures', async () => {
    mockFetchAll.mockResolvedValue([SAMPLE_EXPOSURE])

    const { result } = renderHook(() => useCounterpartyRisk())

    await waitFor(() => expect(result.current.loading).toBe(false))

    const updatedExposure = { ...SAMPLE_EXPOSURE, currentNetExposure: 3_000_000 }
    mockFetchAll.mockResolvedValue([updatedExposure])

    await act(async () => {
      result.current.refresh()
    })

    await waitFor(() => {
      expect(result.current.exposures[0].currentNetExposure).toBe(3_000_000)
    })
  })
})
