import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DemoBootstrapGate } from './DemoBootstrapGate'

type BootstrapState = 'NOT_STARTED' | 'IN_PROGRESS' | 'READY' | 'FAILED'

interface BootstrapStatusBody {
  state: BootstrapState
  successCount?: number | null
  failureCount?: number | null
  failedBooks?: string[] | null
}

function makeFetchMock(
  responses: BootstrapStatusBody[] | BootstrapStatusBody,
  init: { status?: number } = {},
) {
  const queue = Array.isArray(responses) ? [...responses] : [responses]
  return vi.fn(async () => {
    const next = queue.length > 1 ? queue.shift()! : queue[0]
    return new Response(JSON.stringify(next), {
      status: init.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
}

describe('DemoBootstrapGate', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('renders children immediately and shows the splash overlay while IN_PROGRESS', async () => {
    const fetchMock = makeFetchMock({
      state: 'IN_PROGRESS',
      successCount: 3,
      failureCount: 0,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <DemoBootstrapGate>
        <div data-testid="child">child content</div>
      </DemoBootstrapGate>,
    )

    // Children always render (the gate is non-blocking).
    expect(screen.getByTestId('child')).toBeInTheDocument()

    // After the first poll resolves, the splash overlay must appear with the
    // expected copy ("N of 8 books ready").
    await waitFor(() => {
      expect(screen.getByTestId('demo-bootstrap-splash')).toBeInTheDocument()
    })
    expect(screen.getByTestId('demo-bootstrap-splash')).toHaveTextContent(
      /Demo environment initializing/i,
    )
    expect(screen.getByTestId('demo-bootstrap-splash')).toHaveTextContent(
      /3 of 8 books ready/i,
    )
  })

  it('shows the splash while NOT_STARTED', async () => {
    const fetchMock = makeFetchMock({
      state: 'NOT_STARTED',
      successCount: 0,
      failureCount: 0,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <DemoBootstrapGate>
        <div>x</div>
      </DemoBootstrapGate>,
    )

    await waitFor(() => {
      expect(screen.getByTestId('demo-bootstrap-splash')).toBeInTheDocument()
    })
  })

  it('dismisses the splash when state becomes READY', async () => {
    const fetchMock = makeFetchMock({
      state: 'READY',
      successCount: 8,
      failureCount: 0,
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <DemoBootstrapGate>
        <div data-testid="child">child</div>
      </DemoBootstrapGate>,
    )

    // The child still renders.
    expect(screen.getByTestId('child')).toBeInTheDocument()

    // The splash must never appear, because the very first poll returns READY.
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(screen.queryByTestId('demo-bootstrap-splash')).not.toBeInTheDocument()
    })
  })

  it('dismisses immediately (and logs) when the endpoint is unreachable', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    vi.stubGlobal('fetch', fetchMock)
    const errorSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    render(
      <DemoBootstrapGate>
        <div data-testid="child">child</div>
      </DemoBootstrapGate>,
    )

    expect(screen.getByTestId('child')).toBeInTheDocument()

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled()
    })

    // No splash ever rendered.
    expect(screen.queryByTestId('demo-bootstrap-splash')).not.toBeInTheDocument()
    errorSpy.mockRestore()
  })

  it('dismisses immediately when the endpoint returns 404 (non-demo environment)', async () => {
    const fetchMock = makeFetchMock(
      { state: 'NOT_STARTED' } as BootstrapStatusBody,
      { status: 404 },
    )
    vi.stubGlobal('fetch', fetchMock)

    render(
      <DemoBootstrapGate>
        <div data-testid="child">child</div>
      </DemoBootstrapGate>,
    )

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled()
    })

    // 404 marks the gate as permanently dismissed; no splash ever renders.
    expect(screen.queryByTestId('demo-bootstrap-splash')).not.toBeInTheDocument()
  })

  it('dismisses the splash and logs to console when state becomes FAILED', async () => {
    const fetchMock = makeFetchMock({
      state: 'FAILED',
      successCount: 5,
      failureCount: 3,
      failedBooks: ['BOOK_A', 'BOOK_B', 'BOOK_C'],
    })
    vi.stubGlobal('fetch', fetchMock)
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    render(
      <DemoBootstrapGate>
        <div data-testid="child">child</div>
      </DemoBootstrapGate>,
    )

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled()
    })

    // Splash dismisses; child still visible.
    await waitFor(() => {
      expect(screen.queryByTestId('demo-bootstrap-splash')).not.toBeInTheDocument()
    })
    expect(screen.getByTestId('child')).toBeInTheDocument()

    // FAILED is logged so an operator can see it in the browser console.
    expect(errorSpy).toHaveBeenCalled()
    errorSpy.mockRestore()
  })

  it('polls again after 2s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const fetchMock = makeFetchMock([
      { state: 'IN_PROGRESS', successCount: 1, failureCount: 0 },
      { state: 'IN_PROGRESS', successCount: 2, failureCount: 0 },
      { state: 'IN_PROGRESS', successCount: 3, failureCount: 0 },
    ])
    vi.stubGlobal('fetch', fetchMock)

    render(
      <DemoBootstrapGate>
        <div>x</div>
      </DemoBootstrapGate>,
    )

    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000)
    })
    await vi.waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })
  })
})
