import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useCrossBookVaR } from './useCrossBookVaR'

vi.mock('../api/risk', () => ({
  fetchCrossBookVaR: vi.fn(),
  triggerCrossBookVaR: vi.fn(),
}))

import * as riskApi from '../api/risk'

const mockFetch = vi.mocked(riskApi.fetchCrossBookVaR)
const mockTrigger = vi.mocked(riskApi.triggerCrossBookVaR)

const SAMPLE_RESULT = {
  portfolioGroupId: 'firm',
  bookIds: ['port-1', 'port-2'],
  calculationType: 'PARAMETRIC',
  confidenceLevel: 'CL_95',
  varValue: '120000.00',
  expectedShortfall: '150000.00',
  componentBreakdown: [],
  bookContributions: [],
  totalStandaloneVar: '150000.00',
  diversificationBenefit: '30000.00',
  calculatedAt: '2026-05-19T12:00:00Z',
}

describe('useCrossBookVaR', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFetch.mockResolvedValue(SAMPLE_RESULT)
    mockTrigger.mockResolvedValue(SAMPLE_RESULT)
  })

  it('refresh sends portfolioGroupId on the POST body (and not bookGroupId)', async () => {
    const { result } = renderHook(() =>
      useCrossBookVaR(['port-1', 'port-2'], 'firm'),
    )

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalled()
    })

    await act(async () => {
      await result.current.refresh()
    })

    expect(mockTrigger).toHaveBeenCalledTimes(1)
    const requestArg = mockTrigger.mock.calls[0][0] as unknown as Record<string, unknown>
    expect(requestArg).toMatchObject({
      bookIds: ['port-1', 'port-2'],
      portfolioGroupId: 'firm',
    })
    // The gateway DTO is strict — `bookGroupId` would fail kotlinx
    // deserialisation and the global error handler would flatten to 500.
    expect(requestArg).not.toHaveProperty('bookGroupId')
  })

  it('does not call trigger when there is no group id', async () => {
    const { result } = renderHook(() => useCrossBookVaR(['port-1'], null))

    await act(async () => {
      await result.current.refresh()
    })

    expect(mockTrigger).not.toHaveBeenCalled()
  })
})
