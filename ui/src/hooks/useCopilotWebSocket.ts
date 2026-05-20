import { useEffect, useRef, useState } from 'react'
import { getAuthToken } from '../auth/authFetch'
import type { CopilotPushEvent } from '../api/copilot'

/**
 * React hook for the intraday Copilot push channel (PR 7 / ADR-0036).
 *
 * Connects to the gateway ``/ws/copilot`` WebSocket route, decodes the
 * ``{type: "intraday_push", push: {...}}`` envelope produced by
 * ``CopilotBroadcaster``, and exposes the received {@link CopilotPushEvent}s as
 * a React state stream (newest first). Mirrors the established WebSocket-hook
 * conventions of {@link useAlertStream} / {@link usePriceStream}:
 *
 *  - URL: ``wss://`` (or ``ws://``) on the current host, with the JWT supplied
 *    as a ``?token=`` query parameter — the auth scheme the gateway expects.
 *  - Auto-reconnect with exponential backoff, capped at
 *    {@link MAX_BACKOFF_MS}; gives up after {@link MAX_RECONNECT_ATTEMPTS}.
 *  - Cleans up the socket and any pending reconnect timer on unmount.
 *
 * Scope filtering is performed server-side: the gateway only delivers pushes
 * for books the connecting user is entitled to (the JWT ``books`` claim). The
 * hook therefore trusts the stream; the optional ``bookId`` argument is a
 * convenience client-side narrowing for single-book views.
 */

const MAX_RECONNECT_ATTEMPTS = 20
const MAX_BACKOFF_MS = 30000
const BASE_BACKOFF_MS = 1000

/** Envelope wrapping a single intraday push, as sent over ``/ws/copilot``. */
interface CopilotWebSocketMessage {
  type: 'intraday_push'
  push: CopilotPushEvent
}

interface UseCopilotWebSocketResult {
  /** Received intraday push events, newest first. */
  events: CopilotPushEvent[]
  /** True while the socket is open. */
  connected: boolean
  /** True while waiting to retry after a disconnect. */
  reconnecting: boolean
  /** True once the reconnect budget is exhausted. */
  exhausted: boolean
}

export function useCopilotWebSocket(
  wsUrl?: string,
  bookId?: string,
): UseCopilotWebSocketResult {
  const [events, setEvents] = useState<CopilotPushEvent[]>([])
  const [connected, setConnected] = useState(false)
  const [reconnecting, setReconnecting] = useState(false)
  const [exhausted, setExhausted] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const attemptRef = useRef(0)
  const unmountedRef = useRef(false)

  const connectRef = useRef<() => void>(() => {})

  useEffect(() => {
    connectRef.current = () => {
      const baseUrl =
        wsUrl ??
        `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/copilot`
      const token = getAuthToken()
      const url = token
        ? `${baseUrl}${baseUrl.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
        : baseUrl
      const ws = new WebSocket(url)
      wsRef.current = ws

      ws.onopen = () => {
        setConnected(true)
        setReconnecting(false)
        setExhausted(false)
        attemptRef.current = 0
      }

      ws.onmessage = (event: { data: string }) => {
        try {
          const msg = JSON.parse(event.data) as CopilotWebSocketMessage
          if (msg.type !== 'intraday_push' || !msg.push) return
          const push = msg.push
          if (bookId && push.book_id !== bookId) return
          setEvents((prev) => [push, ...prev])
        } catch {
          // Ignore malformed frames.
        }
      }

      const scheduleReconnect = () => {
        if (unmountedRef.current) return
        if (attemptRef.current >= MAX_RECONNECT_ATTEMPTS) {
          setReconnecting(false)
          setExhausted(true)
          return
        }

        setConnected(false)
        setReconnecting(true)

        const backoff = Math.min(
          BASE_BACKOFF_MS * Math.pow(2, attemptRef.current),
          MAX_BACKOFF_MS,
        )
        attemptRef.current += 1

        reconnectTimerRef.current = setTimeout(() => {
          if (!unmountedRef.current) {
            connectRef.current()
          }
        }, backoff)
      }

      ws.onclose = () => {
        setConnected(false)
        scheduleReconnect()
      }

      ws.onerror = () => {
        // onclose also fires after onerror.
      }
    }

    unmountedRef.current = false
    connectRef.current()

    return () => {
      unmountedRef.current = true
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
      const ws = wsRef.current
      if (ws) {
        ws.onclose = null
        ws.onerror = null
        ws.close()
      }
    }
  }, [wsUrl, bookId])

  return { events, connected, reconnecting, exhausted }
}
