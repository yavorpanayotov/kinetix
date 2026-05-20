/**
 * Streaming client for the v2 Copilot chat endpoint.
 *
 * Wraps ``POST /api/v1/insights/chat`` — a Server-Sent Events stream of
 * delta + source + done frames — into a ``ReadableStream<ChatChunk>`` so
 * UI components can ``for await`` over typed chunks without parsing SSE
 * by hand. The endpoint contract is documented in
 * ``ai-insights-service/src/kinetix_insights/routes/chat.py``.
 *
 * Why ``fetch`` instead of the native ``EventSource`` API: ``EventSource``
 * only supports ``GET`` and cannot carry a JSON body, but the chat
 * endpoint is ``POST`` with a JSON payload (message + page context +
 * conversation ids). We therefore use ``fetch`` for the transport, drive
 * a manual reader pump over ``response.body``, and reassemble SSE frames
 * (``data:``/``event:`` lines separated by ``\n\n``) into typed
 * ``ChatChunk`` values. The public shape — a ``ReadableStream<ChatChunk>``
 * — keeps the consumer-side ergonomics that the plan's "EventSource +
 * reader pump" wording intended.
 *
 * Abort semantics: passing an ``AbortSignal`` cancels the underlying
 * ``fetch`` and closes the returned stream cleanly (the consumer's next
 * ``read()`` resolves with ``{done: true}`` rather than rejecting). This
 * matches how UI components mount/unmount mid-stream.
 */

import { authFetch } from '../auth/authFetch'

/**
 * Citation provenance attached to a chat chunk (mirrors the server-side
 * ``Citation`` model — see
 * ``ai-insights-service/src/kinetix_insights/citations/models.py``).
 */
export interface Citation {
  tool: string
  params: Record<string, unknown>
  result_field: string
  result_value: number | string
  result_currency: string | null
  as_of_timestamp: string
  data_source: string
  freshness_seconds: number
  quality_flags: string[]
}

/**
 * Discriminated union of chunk shapes streamed by ``/api/v1/insights/chat``.
 *
 * - ``delta`` — incremental text token; emitted as a ``data`` frame.
 * - ``source`` — citations attached to a forthcoming chunk; emitted
 *   as an ``event: source`` frame.
 * - ``done`` — terminal frame; carries session / conversation ids, the
 *   model name, mode (``live`` | ``canned``), and optionally an
 *   ``error_code`` when a guardrail tripped server-side.
 */
export type ChatChunk =
  | {
      type: 'delta'
      delta: string
      citations?: Citation[]
    }
  | {
      type: 'source'
      citations: Citation[]
    }
  | {
      type: 'done'
      session_id: string
      conversation_id: string
      model: string
      mode: 'live' | 'canned'
      citations?: Citation[]
      error_code?: string
    }

/**
 * A single intraday Copilot push event delivered over the ``/ws/copilot``
 * WebSocket channel (PR 7 / ADR-0036).
 *
 * Mirrors the gateway ``CopilotPushRequest`` DTO
 * (``gateway/.../dtos/CopilotPushRequest.kt``), which in turn mirrors the
 * Python ``IntradayPush`` model: a firing intraday threshold composed into a
 * sourced, dismissible alert. Field names stay snake_case because that is the
 * wire shape the gateway forwards verbatim — the same snake_case convention
 * already used by {@link Citation} on the chat channel.
 *
 * ``sources`` carries the provenance trail; each entry is {@link Citation}-shaped.
 */
export interface CopilotPushEvent {
  alert_type: string
  severity: string
  book_id: string
  headline: string
  context_bullets: string[]
  sources: Citation[]
  session_id: string
  generated_at: string
}

/**
 * Discriminated payload variants the UI sends to ``/chat``. Each variant
 * carries the page-specific context the model needs to answer. The route
 * accepts the same JSON shape regardless — this union just gives
 * components a typed way to build it.
 */
export type ExplainPayload =
  | { kind: 'var-dashboard'; book_id: string; as_of?: string }
  | { kind: 'positions'; book_id: string; instrument_id?: string }
  | { kind: 'pnl-attribution'; book_id: string; date?: string }
  | { kind: 'alerts'; book_id: string; alert_id?: string }
  | { kind: 'free-form'; page: string; selection?: Record<string, unknown> }

export interface ChatRequest {
  message: string
  page_context: Record<string, unknown>
  session_id?: string
  conversation_id?: string
}

const CHAT_ENDPOINT = '/api/v1/insights/chat'
const FRAME_SEPARATOR = '\n\n'

/**
 * One parsed SSE frame — the union of ``event:`` / ``data:`` lines.
 *
 * ``event`` is ``"message"`` for the default (un-named) frames the route
 * emits for deltas and the terminal chunk; ``"source"`` for citation
 * frames. Anything else is ignored by the pump.
 */
interface ParsedFrame {
  event: string
  data: string
}

function isAbortError(err: unknown): boolean {
  if (err instanceof DOMException && err.name === 'AbortError') return true
  if (err instanceof Error && err.name === 'AbortError') return true
  return false
}

function parseFrame(raw: string): ParsedFrame | null {
  // Empty separator-only payloads (e.g. heartbeat blank frames) are
  // legitimate SSE noise and must be dropped silently.
  const trimmed = raw.replace(/\r/g, '')
  if (trimmed.length === 0) return null

  let event = 'message'
  const dataParts: string[] = []

  for (const line of trimmed.split('\n')) {
    if (line.length === 0) continue
    if (line.startsWith(':')) continue // SSE comment line
    const colonIdx = line.indexOf(':')
    if (colonIdx === -1) continue
    const field = line.slice(0, colonIdx)
    // Per SSE: an optional single space after the colon is stripped.
    let value = line.slice(colonIdx + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataParts.push(value)
    }
    // ``id`` and ``retry`` are unused by the chat protocol — ignore.
  }

  if (dataParts.length === 0) return null
  return { event, data: dataParts.join('\n') }
}

interface TerminalPayload {
  done: true
  session_id?: string
  conversation_id?: string
  model?: string
  mode?: 'live' | 'canned'
  citations?: Citation[]
  error_code?: string
}

interface DeltaPayload {
  done?: false
  delta?: string
  citations?: Citation[]
}

function frameToChunk(frame: ParsedFrame): ChatChunk | null {
  if (frame.event === 'source') {
    try {
      const parsed = JSON.parse(frame.data) as Citation[]
      if (!Array.isArray(parsed)) return null
      return { type: 'source', citations: parsed }
    } catch {
      return null
    }
  }

  if (frame.event !== 'message') {
    // Unknown event types are silently skipped — the route only ever
    // emits ``source`` and the default (unnamed/``message``) event.
    return null
  }

  let parsed: TerminalPayload | DeltaPayload
  try {
    parsed = JSON.parse(frame.data) as TerminalPayload | DeltaPayload
  } catch {
    return null
  }

  if (parsed && typeof parsed === 'object' && parsed.done === true) {
    const terminal = parsed as TerminalPayload
    const chunk: ChatChunk = {
      type: 'done',
      session_id: terminal.session_id ?? '',
      conversation_id: terminal.conversation_id ?? '',
      model: terminal.model ?? '',
      mode: terminal.mode ?? 'canned',
    }
    if (terminal.citations !== undefined) chunk.citations = terminal.citations
    if (terminal.error_code !== undefined) chunk.error_code = terminal.error_code
    return chunk
  }

  if (parsed && typeof (parsed as DeltaPayload).delta === 'string') {
    const delta = parsed as DeltaPayload
    const chunk: ChatChunk = { type: 'delta', delta: delta.delta as string }
    if (delta.citations !== undefined) chunk.citations = delta.citations
    return chunk
  }

  // Lenient: drop frames we don't recognise rather than erroring.
  return null
}

/**
 * Stream chat chunks from ``POST /api/v1/insights/chat``.
 *
 * Returns a fresh ``ReadableStream<ChatChunk>`` synchronously; the
 * network call happens inside ``start()``. Cancel by calling
 * ``reader.cancel()`` or by aborting the supplied signal — both close
 * the underlying ``fetch`` and stop the pump cleanly.
 */
export function chat(
  request: ChatRequest,
  options?: { signal?: AbortSignal },
): ReadableStream<ChatChunk> {
  const signal = options?.signal
  return new ReadableStream<ChatChunk>({
    async start(controller) {
      // Wire abort -> close so a pre-aborted signal short-circuits
      // without ever issuing the request.
      if (signal?.aborted) {
        controller.close()
        return
      }

      let response: Response
      try {
        response = await authFetch(CHAT_ENDPOINT, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
          },
          body: JSON.stringify(request),
          signal,
        })
      } catch (err) {
        if (isAbortError(err) || signal?.aborted) {
          controller.close()
          return
        }
        controller.error(err)
        return
      }

      if (!response.ok) {
        controller.error(
          new Error(
            `Failed to open chat stream: ${response.status} ${response.statusText}`,
          ),
        )
        return
      }

      const body = response.body
      if (!body) {
        controller.close()
        return
      }

      const reader = body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      // Make sure an abort mid-stream tears the reader down so the
      // pump's next read resolves and we can close the controller.
      const onAbort = () => {
        // ``cancel`` is fire-and-forget — the running ``read()`` call
        // will reject (or resolve with ``done``) shortly afterwards.
        void reader.cancel().catch(() => {})
      }
      if (signal) {
        if (signal.aborted) {
          onAbort()
        } else {
          signal.addEventListener('abort', onAbort, { once: true })
        }
      }

      try {
        for (;;) {
          let read: ReadableStreamReadResult<Uint8Array>
          try {
            read = await reader.read()
          } catch (err) {
            if (isAbortError(err) || signal?.aborted) {
              break
            }
            controller.error(err)
            return
          }

          if (read.done) {
            // Flush any trailing complete frame still in the buffer.
            buffer += decoder.decode()
            if (buffer.length > 0) {
              const frame = parseFrame(buffer)
              if (frame) {
                const chunk = frameToChunk(frame)
                if (chunk) controller.enqueue(chunk)
              }
              buffer = ''
            }
            break
          }

          buffer += decoder.decode(read.value, { stream: true })

          let sepIdx = buffer.indexOf(FRAME_SEPARATOR)
          while (sepIdx !== -1) {
            const rawFrame = buffer.slice(0, sepIdx)
            buffer = buffer.slice(sepIdx + FRAME_SEPARATOR.length)
            const frame = parseFrame(rawFrame)
            if (frame) {
              const chunk = frameToChunk(frame)
              if (chunk) controller.enqueue(chunk)
            }
            sepIdx = buffer.indexOf(FRAME_SEPARATOR)
          }
        }
        controller.close()
      } finally {
        if (signal) signal.removeEventListener('abort', onAbort)
      }
    },
    cancel(reason) {
      // Best-effort: if the consumer cancels the public stream we have
      // no direct handle on the underlying reader here (it lives in the
      // ``start`` closure), but ``ReadableStream`` will propagate
      // back-pressure via the controller. Surfacing the reason keeps
      // debugging sane.
      void reason
    },
  })
}

/**
 * Helper: convert an ``ExplainPayload`` discriminator into the
 * ``(message, page_context)`` fields the chat endpoint expects.
 *
 * The route doesn't care about the shape of ``page_context`` (it's a
 * free-form dict server-side); this helper exists so UI components can
 * author callers without stringly-typed dict literals.
 */
export function payloadToChatRequest(
  payload: ExplainPayload,
  message: string,
): ChatRequest {
  const context: Record<string, unknown> = { page: payload.kind }

  switch (payload.kind) {
    case 'var-dashboard':
      context.book_id = payload.book_id
      if (payload.as_of !== undefined) context.as_of = payload.as_of
      break
    case 'positions':
      context.book_id = payload.book_id
      if (payload.instrument_id !== undefined)
        context.instrument_id = payload.instrument_id
      break
    case 'pnl-attribution':
      context.book_id = payload.book_id
      if (payload.date !== undefined) context.date = payload.date
      break
    case 'alerts':
      context.book_id = payload.book_id
      if (payload.alert_id !== undefined) context.alert_id = payload.alert_id
      break
    case 'free-form':
      // ``page`` was already stamped above with the discriminator value
      // (``"free-form"``); ``payload.page`` is reserved for the UI's
      // free-form override (currently always equal to ``kind``).
      if (payload.selection !== undefined) context.selection = payload.selection
      break
  }

  return { message, page_context: context }
}
