import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useTapeReplayStatus } from './useTapeReplayStatus'

vi.mock('../api/tapeReplay', () => ({
  fetchTapeReplayStatus: vi.fn(),
}))

import { fetchTapeReplayStatus } from '../api/tapeReplay'

const mockFetch = vi.mocked(fetchTapeReplayStatus)

describe('useTapeReplayStatus', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('fetches the replay status on mount', async () => {
    mockFetch.mockResolvedValue({ status: 'ACTIVE' })

    const { result } = renderHook(() => useTapeReplayStatus())

    expect(result.current.loading).toBe(true)

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(result.current.status).toBe('ACTIVE')
    expect(result.current.error).toBeNull()
  })

  it('records the error on fetch failure', async () => {
    mockFetch.mockRejectedValue(new Error('boom'))

    const { result } = renderHook(() => useTapeReplayStatus())

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('boom')
    expect(result.current.status).toBeNull()
  })
})
