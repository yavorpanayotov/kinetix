import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useRunAllScenarios } from './useRunAllScenarios'

vi.mock('../api/stress', () => ({
  fetchScenarios: vi.fn(),
  getLatestStressBatch: vi.fn(),
  runAllStressTests: vi.fn(),
}))

import * as stressApi from '../api/stress'

const mockFetchScenarios = vi.mocked(stressApi.fetchScenarios)
const mockGetLatest = vi.mocked(stressApi.getLatestStressBatch)
const mockRunAll = vi.mocked(stressApi.runAllStressTests)

const STORED_BATCH = {
  results: [
    { scenarioName: 'COVID_2020', baseVar: '50000.00', stressedVar: '70000.00', pnlImpact: '-150000.00' },
    { scenarioName: 'GFC_2008', baseVar: '50000.00', stressedVar: '80000.00', pnlImpact: '-400000.00' },
  ],
  failedScenarios: [],
  worstScenarioName: 'GFC_2008',
  worstPnlImpact: '-400000.00',
} as unknown as Awaited<ReturnType<typeof stressApi.getLatestStressBatch>>

describe('useRunAllScenarios', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFetchScenarios.mockResolvedValue(['GFC_2008', 'COVID_2020'])
    mockGetLatest.mockResolvedValue(null)
    mockRunAll.mockResolvedValue([])
  })

  it('populates results from the persisted batch on mount, ranked by |pnlImpact|', async () => {
    mockGetLatest.mockResolvedValue(STORED_BATCH)

    const { result } = renderHook(() => useRunAllScenarios('book-1'))

    await waitFor(() => {
      expect(result.current.results.length).toBe(2)
    })
    expect(mockGetLatest).toHaveBeenCalledWith('book-1')
    // Worst (most negative) first.
    expect(result.current.results[0].scenarioName).toBe('GFC_2008')
    expect(result.current.results[1].scenarioName).toBe('COVID_2020')
    // Does NOT auto-recompute on mount.
    expect(mockRunAll).not.toHaveBeenCalled()
  })

  it('leaves results empty when no batch has been persisted (404 -> null)', async () => {
    mockGetLatest.mockResolvedValue(null)

    const { result } = renderHook(() => useRunAllScenarios('book-1'))

    await waitFor(() => {
      expect(mockGetLatest).toHaveBeenCalledWith('book-1')
    })
    expect(result.current.results).toEqual([])
    expect(result.current.error).toBeNull()
  })

  it('swallows a failed stored-batch fetch without surfacing an error', async () => {
    mockGetLatest.mockRejectedValue(new Error('network down'))

    const { result } = renderHook(() => useRunAllScenarios('book-1'))

    await waitFor(() => {
      expect(mockGetLatest).toHaveBeenCalledWith('book-1')
    })
    expect(result.current.results).toEqual([])
    expect(result.current.error).toBeNull()
  })

  it('runAll recomputes and replaces the stored results', async () => {
    mockGetLatest.mockResolvedValue(STORED_BATCH)
    mockRunAll.mockResolvedValue([
      { scenarioName: 'GFC_2008', baseVar: '50000.00', stressedVar: '90000.00', pnlImpact: '-500000.00' } as never,
    ])

    const { result } = renderHook(() => useRunAllScenarios('book-1'))

    await waitFor(() => {
      expect(result.current.results.length).toBe(2)
    })

    await act(async () => {
      result.current.runAll()
    })

    await waitFor(() => {
      expect(mockRunAll).toHaveBeenCalled()
    })
    expect(result.current.results.length).toBe(1)
    expect(result.current.results[0].scenarioName).toBe('GFC_2008')
  })
})
