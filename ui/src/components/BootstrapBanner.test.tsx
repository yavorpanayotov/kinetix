import { act, render, screen, waitFor } from '@testing-library/react'
import { fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BootstrapBanner, BOOTSTRAP_DISMISS_SESSION_KEY } from './BootstrapBanner'

type BootstrapState = 'NOT_STARTED' | 'IN_PROGRESS' | 'READY' | 'FAILED'

interface BootstrapStatusBody {
  state: BootstrapState
  successCount?: number | null
  failureCount?: number | null
  sodSuccessCount?: number | null
  sodFailureCount?: number | null
}

function makeFetchMock(responses: BootstrapStatusBody[] | BootstrapStatusBody) {
  const queue = Array.isArray(responses) ? [...responses] : [responses]
  // When the queue runs out, keep returning the last response so polling tests
  // don't unexpectedly fail.
  return vi.fn(async () => {
    const next = queue.length > 1 ? queue.shift()! : queue[0]
    return new Response(JSON.stringify(next), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
}

describe('BootstrapBanner', () => {
  beforeEach(() => {
    sessionStorage.removeItem(BOOTSTRAP_DISMISS_SESSION_KEY)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
    sessionStorage.removeItem(BOOTSTRAP_DISMISS_SESSION_KEY)
  })

  it('renders when state is IN_PROGRESS', async () => {
    const fetchMock = makeFetchMock({
      state: 'IN_PROGRESS',
      successCount: 3,
      failureCount: 0,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<BootstrapBanner />)

    await waitFor(() => {
      expect(screen.getByTestId('bootstrap-banner')).toBeInTheDocument()
    })
    expect(screen.getByTestId('bootstrap-banner')).toHaveTextContent(
      /Initialising demo data/i,
    )
  })

  it('renders when state is NOT_STARTED', async () => {
    const fetchMock = makeFetchMock({
      state: 'NOT_STARTED',
      successCount: null,
      failureCount: null,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<BootstrapBanner />)

    await waitFor(() => {
      expect(screen.getByTestId('bootstrap-banner')).toBeInTheDocument()
    })
    expect(screen.getByTestId('bootstrap-banner')).toHaveTextContent(
      /Initialising demo data/i,
    )
  })

  it('hides when state is READY', async () => {
    const fetchMock = makeFetchMock({
      state: 'READY',
      successCount: 8,
      failureCount: 0,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<BootstrapBanner />)

    // Allow the initial fetch + state update to settle. The banner must not
    // appear because the bootstrap is already done.
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled()
    })

    // Give React a tick to render after the fetch resolves.
    await waitFor(() => {
      expect(screen.queryByTestId('bootstrap-banner')).not.toBeInTheDocument()
    })
  })

  it('renders failure variant when state is FAILED', async () => {
    const fetchMock = makeFetchMock({
      state: 'FAILED',
      successCount: 5,
      failureCount: 3,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<BootstrapBanner />)

    await waitFor(() => {
      expect(screen.getByTestId('bootstrap-banner')).toBeInTheDocument()
    })
    expect(screen.getByTestId('bootstrap-banner')).toHaveTextContent(
      /Initialisation failed/i,
    )
    // Should be styled differently — we surface this via a data attribute the
    // test can read without coupling to specific Tailwind classes.
    expect(screen.getByTestId('bootstrap-banner')).toHaveAttribute(
      'data-variant',
      'failed',
    )
  })

  it('manual dismiss removes the banner for the session', async () => {
    const fetchMock = makeFetchMock({
      state: 'IN_PROGRESS',
      successCount: 1,
      failureCount: 0,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<BootstrapBanner />)

    await waitFor(() => {
      expect(screen.getByTestId('bootstrap-banner')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByTestId('bootstrap-banner-dismiss'))

    await waitFor(() => {
      expect(screen.queryByTestId('bootstrap-banner')).not.toBeInTheDocument()
    })

    // Session flag should have been set so a re-mount within the same session
    // does NOT show the banner again.
    expect(sessionStorage.getItem(BOOTSTRAP_DISMISS_SESSION_KEY)).toBe('true')
  })

  it('polls every 3 seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const fetchMock = makeFetchMock([
      { state: 'IN_PROGRESS', successCount: 1, failureCount: 0 },
      { state: 'IN_PROGRESS', successCount: 2, failureCount: 0 },
      { state: 'IN_PROGRESS', successCount: 3, failureCount: 0 },
    ])
    vi.stubGlobal('fetch', fetchMock)

    render(<BootstrapBanner />)

    // Initial fetch on mount.
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })

    // After ~3s the next poll fires.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })

    // And again after another ~3s.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(3)
    })
  })
})
