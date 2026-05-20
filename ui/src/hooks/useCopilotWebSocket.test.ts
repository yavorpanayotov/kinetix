import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CopilotPushEvent } from '../api/copilot'
import { useCopilotWebSocket } from './useCopilotWebSocket'

vi.mock('../auth/authFetch', () => ({
  getAuthToken: vi.fn(() => 'test-token'),
}))

class MockWebSocket {
  static instances: MockWebSocket[] = []

  url: string
  onopen: (() => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  readyState = 0
  sent: string[] = []
  closed = false

  constructor(url: string) {
    this.url = url
    MockWebSocket.instances.push(this)
  }

  send(data: string) {
    this.sent.push(data)
  }

  close() {
    this.closed = true
    this.readyState = 3
  }

  simulateOpen() {
    this.readyState = 1
    this.onopen?.()
  }

  simulateClose() {
    this.readyState = 3
    this.onclose?.()
  }

  simulateMessage(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) })
  }

  /** Send a raw (already-stringified) frame — used to test malformed input. */
  simulateRawMessage(data: string) {
    this.onmessage?.({ data })
  }
}

const makePush = (overrides: Partial<CopilotPushEvent> = {}): CopilotPushEvent => ({
  alert_type: 'var_breach',
  severity: 'high',
  book_id: 'book-1',
  headline: 'VaR limit breached on book-1',
  context_bullets: ['1-day VaR rose to 1.2M', 'Limit is 1.0M'],
  sources: [],
  session_id: 'sess-abc',
  generated_at: '2026-05-20T10:15:00Z',
  ...overrides,
})

/** Wrap a push payload in the gateway's `/ws/copilot` frame envelope. */
const frame = (push: CopilotPushEvent) => ({ type: 'intraday_push', push })

describe('useCopilotWebSocket', () => {
  beforeEach(() => {
    MockWebSocket.instances = []
    vi.stubGlobal('WebSocket', MockWebSocket)
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  describe('connect', () => {
    it('opens a WebSocket to /ws/copilot with the auth token', () => {
      renderHook(() => useCopilotWebSocket())

      expect(MockWebSocket.instances).toHaveLength(1)
      expect(MockWebSocket.instances[0].url).toContain('/ws/copilot')
      expect(MockWebSocket.instances[0].url).toContain('token=test-token')
    })

    it('honours an explicit wsUrl override', () => {
      renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      expect(MockWebSocket.instances[0].url).toContain('ws://localhost/ws')
      expect(MockWebSocket.instances[0].url).toContain('token=test-token')
    })

    it('starts disconnected with no events', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      expect(result.current.connected).toBe(false)
      expect(result.current.events).toEqual([])
    })

    it('reports connected once the socket opens', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })

      expect(result.current.connected).toBe(true)
    })

    it('exposes received push events as a state stream, newest first', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage(
          frame(makePush({ headline: 'first' })),
        )
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage(
          frame(makePush({ headline: 'second' })),
        )
      })

      expect(result.current.events).toHaveLength(2)
      expect(result.current.events[0].headline).toBe('second')
      expect(result.current.events[1].headline).toBe('first')
    })

    it('ignores frames whose type is not intraday_push', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage({ type: 'ping' })
      })

      expect(result.current.events).toEqual([])
    })

    it('ignores malformed (non-JSON) frames without crashing', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateRawMessage('not json{')
      })

      expect(result.current.events).toEqual([])
    })
  })

  describe('disconnect', () => {
    it('reports disconnected when the socket closes', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      expect(result.current.connected).toBe(true)

      act(() => {
        MockWebSocket.instances[0].simulateClose()
      })
      expect(result.current.connected).toBe(false)
    })

    it('closes the socket on unmount', () => {
      const { unmount } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })

      unmount()

      expect(MockWebSocket.instances[0].closed).toBe(true)
    })

    it('does not reconnect after unmount', () => {
      const { unmount } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      unmount()
      act(() => {
        vi.advanceTimersByTime(60000)
      })

      expect(MockWebSocket.instances).toHaveLength(1)
    })
  })

  describe('reconnect', () => {
    it('reconnects with backoff after the socket closes', () => {
      renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateClose()
      })

      // Before the backoff delay elapses, no new socket exists.
      expect(MockWebSocket.instances).toHaveLength(1)

      act(() => {
        vi.advanceTimersByTime(1000)
      })
      expect(MockWebSocket.instances).toHaveLength(2)
    })

    it('sets reconnecting while waiting to retry', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateClose()
      })

      expect(result.current.reconnecting).toBe(true)
    })

    it('grows the backoff delay exponentially on repeated failures', () => {
      renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      // First close -> retry after ~1000ms.
      act(() => {
        MockWebSocket.instances[0].simulateClose()
      })
      act(() => {
        vi.advanceTimersByTime(1000)
      })
      expect(MockWebSocket.instances).toHaveLength(2)

      // Second close -> retry after ~2000ms (not yet at 1000ms).
      act(() => {
        MockWebSocket.instances[1].simulateClose()
      })
      act(() => {
        vi.advanceTimersByTime(1000)
      })
      expect(MockWebSocket.instances).toHaveLength(2)
      act(() => {
        vi.advanceTimersByTime(1000)
      })
      expect(MockWebSocket.instances).toHaveLength(3)
    })

    it('clears reconnecting once a retry connection opens', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateClose()
      })
      expect(result.current.reconnecting).toBe(true)

      act(() => {
        vi.advanceTimersByTime(1000)
      })
      act(() => {
        MockWebSocket.instances[1].simulateOpen()
      })

      expect(result.current.reconnecting).toBe(false)
      expect(result.current.connected).toBe(true)
    })

    it('stops retrying after the maximum number of attempts (exhausted)', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateClose()
      })
      for (let i = 0; i < 20; i++) {
        act(() => {
          vi.advanceTimersByTime(30000)
        })
        const ws = MockWebSocket.instances[MockWebSocket.instances.length - 1]
        act(() => {
          ws.simulateClose()
        })
      }

      expect(result.current.exhausted).toBe(true)
      expect(result.current.reconnecting).toBe(false)
    })
  })

  describe('scope-filter', () => {
    it('surfaces every push the server delivers (server scope-filters by book)', () => {
      // The gateway only sends pushes for books the user is entitled to,
      // so the hook trusts the stream and surfaces all delivered events.
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage(
          frame(makePush({ book_id: 'book-1' })),
        )
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage(
          frame(makePush({ book_id: 'book-2' })),
        )
      })

      expect(result.current.events.map((e) => e.book_id)).toEqual([
        'book-2',
        'book-1',
      ])
    })

    it('filters events to a single book when bookId is supplied', () => {
      const { result } = renderHook(() =>
        useCopilotWebSocket('ws://localhost/ws', 'book-1'),
      )

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage(
          frame(makePush({ book_id: 'book-1', headline: 'mine' })),
        )
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage(
          frame(makePush({ book_id: 'book-2', headline: 'not mine' })),
        )
      })

      expect(result.current.events).toHaveLength(1)
      expect(result.current.events[0].headline).toBe('mine')
      expect(result.current.events[0].book_id).toBe('book-1')
    })

    it('preserves the push payload fields and sources verbatim', () => {
      const { result } = renderHook(() => useCopilotWebSocket('ws://localhost/ws'))

      const push = makePush({
        alert_type: 'liquidity_drain',
        severity: 'critical',
        context_bullets: ['Order book thinning'],
        sources: [
          {
            tool: 'liquidity.depth',
            params: { book_id: 'book-1' },
            result_field: 'depth_usd',
            result_value: 250000,
            result_currency: 'USD',
            as_of_timestamp: '2026-05-20T10:14:55Z',
            data_source: 'liquidity-service',
            freshness_seconds: 5,
            quality_flags: [],
          },
        ],
      })

      act(() => {
        MockWebSocket.instances[0].simulateOpen()
      })
      act(() => {
        MockWebSocket.instances[0].simulateMessage(frame(push))
      })

      expect(result.current.events[0]).toEqual(push)
      expect(result.current.events[0].sources[0].tool).toBe('liquidity.depth')
    })
  })
})
