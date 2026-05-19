import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  chat,
  payloadToChatRequest,
  type ChatChunk,
  type ChatRequest,
  type Citation,
} from './copilot'

const mockAuthFetch = vi.fn()

vi.mock('../auth/authFetch', () => ({
  authFetch: (input: RequestInfo | URL, init?: RequestInit) =>
    mockAuthFetch(input, init),
}))

function streamFromChunks(chunks: string[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      const encoder = new TextEncoder()
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
}

function delayedStream(
  chunks: string[],
  options: { delayMs: number } = { delayMs: 5 },
): ReadableStream<Uint8Array> {
  return new ReadableStream({
    async start(controller) {
      const encoder = new TextEncoder()
      for (const chunk of chunks) {
        await new Promise((resolve) => setTimeout(resolve, options.delayMs))
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
}

async function readAll(stream: ReadableStream<ChatChunk>): Promise<ChatChunk[]> {
  const reader = stream.getReader()
  const out: ChatChunk[] = []
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    out.push(value)
  }
  return out
}

const baseRequest: ChatRequest = {
  message: 'why is VaR up?',
  page_context: { page: 'var-dashboard', book_id: 'fx-main' },
}

describe('copilot.chat', () => {
  beforeEach(() => {
    mockAuthFetch.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns a ReadableStream and yields delta chunks in order', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamFromChunks([
        'data: {"delta":"hello","done":false}\n\n',
        'data: {"delta":" world","done":false}\n\n',
      ]),
    })

    const stream = chat(baseRequest)
    const chunks = await readAll(stream)

    expect(chunks).toHaveLength(2)
    expect(chunks[0]).toEqual({ type: 'delta', delta: 'hello' })
    expect(chunks[1]).toEqual({ type: 'delta', delta: ' world' })
  })

  it('yields a done chunk on the terminal frame', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamFromChunks([
        'data: {"delta":"hi","done":false}\n\n',
        'data: {"done":true,"session_id":"sess-1","conversation_id":"conv-1","model":"canned-chat","mode":"canned"}\n\n',
      ]),
    })

    const stream = chat(baseRequest)
    const chunks = await readAll(stream)

    expect(chunks).toHaveLength(2)
    const last = chunks[chunks.length - 1]
    expect(last.type).toBe('done')
    if (last.type === 'done') {
      expect(last.session_id).toBe('sess-1')
      expect(last.conversation_id).toBe('conv-1')
      expect(last.model).toBe('canned-chat')
      expect(last.mode).toBe('canned')
    }
  })

  it('yields source chunks for event:source frames', async () => {
    const citation: Citation = {
      tool: 'get_book_var',
      params: { book_id: 'fx-main' },
      result_field: 'total_var',
      result_value: 1250000,
      result_currency: 'USD',
      as_of_timestamp: '2026-05-19T06:30:00Z',
      data_source: 'risk-orchestrator',
      freshness_seconds: 45,
      quality_flags: [],
    }
    mockAuthFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamFromChunks([
        `event: source\ndata: ${JSON.stringify([citation])}\n\n`,
        'data: {"delta":"VaR rose","done":false}\n\n',
      ]),
    })

    const stream = chat(baseRequest)
    const chunks = await readAll(stream)

    expect(chunks).toHaveLength(2)
    expect(chunks[0].type).toBe('source')
    if (chunks[0].type === 'source') {
      expect(chunks[0].citations).toHaveLength(1)
      expect(chunks[0].citations[0].tool).toBe('get_book_var')
    }
    expect(chunks[1].type).toBe('delta')
  })

  it('handles bytes arriving mid-frame (no double-encode)', async () => {
    const frame = 'data: {"delta":"hello world","done":false}\n\n'
    const splitPoint = 20 // splits inside the JSON payload
    const first = frame.slice(0, splitPoint)
    const second = frame.slice(splitPoint)

    mockAuthFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamFromChunks([first, second]),
    })

    const stream = chat(baseRequest)
    const chunks = await readAll(stream)

    expect(chunks).toHaveLength(1)
    expect(chunks[0]).toEqual({ type: 'delta', delta: 'hello world' })
  })

  it('errors the stream on non-2xx HTTP response', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      body: null,
    })

    const stream = chat(baseRequest)
    const reader = stream.getReader()
    await expect(reader.read()).rejects.toThrow(/500/)
  })

  it('closes cleanly when AbortSignal is aborted before stream consumed', async () => {
    const controller = new AbortController()
    mockAuthFetch.mockImplementation((_input, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        const signal = init?.signal
        if (signal) {
          if (signal.aborted) {
            const err = new DOMException('Aborted', 'AbortError')
            reject(err)
            return
          }
          signal.addEventListener('abort', () => {
            const err = new DOMException('Aborted', 'AbortError')
            reject(err)
          })
        }
      })
    })

    const stream = chat(baseRequest, { signal: controller.signal })
    // Abort before any read.
    controller.abort()

    const reader = stream.getReader()
    const result = await reader.read()
    expect(result.done).toBe(true)
  })

  it('closes cleanly when AbortSignal is aborted mid-stream', async () => {
    const controller = new AbortController()
    mockAuthFetch.mockImplementation((_input, init?: RequestInit) => {
      const signal = init?.signal
      const body = new ReadableStream<Uint8Array>({
        async start(streamController) {
          const encoder = new TextEncoder()
          streamController.enqueue(
            encoder.encode('data: {"delta":"first","done":false}\n\n'),
          )
          // Wait for the abort to fire from the consumer side.
          await new Promise<void>((resolve) => {
            if (signal?.aborted) {
              resolve()
              return
            }
            signal?.addEventListener('abort', () => resolve())
          })
          streamController.error(new DOMException('Aborted', 'AbortError'))
        },
      })
      return Promise.resolve({
        ok: true,
        status: 200,
        body,
      })
    })

    const stream = chat(baseRequest, { signal: controller.signal })
    const reader = stream.getReader()

    const first = await reader.read()
    expect(first.done).toBe(false)
    expect(first.value?.type).toBe('delta')

    controller.abort()

    const second = await reader.read()
    expect(second.done).toBe(true)
  })

  it('ignores empty frames and unknown event types', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamFromChunks([
        '\n\n',
        'event: unknown\ndata: {}\n\n',
        'data: {"delta":"keep","done":false}\n\n',
      ]),
    })

    const stream = chat(baseRequest)
    const chunks = await readAll(stream)

    expect(chunks).toHaveLength(1)
    expect(chunks[0]).toEqual({ type: 'delta', delta: 'keep' })
  })

  it('carries citations on terminal chunk when present', async () => {
    const citation: Citation = {
      tool: 'get_positions',
      params: { book_id: 'fx-main' },
      result_field: 'mtm',
      result_value: 1_000_000,
      result_currency: 'USD',
      as_of_timestamp: '2026-05-19T07:00:00Z',
      data_source: 'position-service',
      freshness_seconds: 12,
      quality_flags: ['fresh'],
    }
    const terminalPayload = {
      done: true,
      session_id: 's',
      conversation_id: 'c',
      model: 'canned-chat',
      mode: 'canned',
      citations: [citation],
    }
    mockAuthFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: streamFromChunks([
        `event: source\ndata: ${JSON.stringify([citation])}\n\n`,
        `data: ${JSON.stringify(terminalPayload)}\n\n`,
      ]),
    })

    const stream = chat(baseRequest)
    const chunks = await readAll(stream)

    const last = chunks[chunks.length - 1]
    expect(last.type).toBe('done')
    if (last.type === 'done') {
      expect(last.citations).toBeDefined()
      expect(last.citations).toHaveLength(1)
      expect(last.citations?.[0].tool).toBe('get_positions')
    }
  })

  it('streams chunks as they arrive (smoke test of pump)', async () => {
    mockAuthFetch.mockResolvedValue({
      ok: true,
      status: 200,
      body: delayedStream([
        'data: {"delta":"a","done":false}\n\n',
        'data: {"delta":"b","done":false}\n\n',
      ]),
    })

    const stream = chat(baseRequest)
    const chunks = await readAll(stream)

    expect(chunks.map((c) => (c.type === 'delta' ? c.delta : c.type))).toEqual([
      'a',
      'b',
    ])
  })
})

describe('payloadToChatRequest', () => {
  it('var-dashboard maps to expected page_context', () => {
    const request = payloadToChatRequest(
      { kind: 'var-dashboard', book_id: 'fx-main', as_of: '2026-05-19' },
      'why is VaR up?',
    )

    expect(request.message).toBe('why is VaR up?')
    expect(request.page_context).toEqual({
      page: 'var-dashboard',
      book_id: 'fx-main',
      as_of: '2026-05-19',
    })
  })

  it('positions maps to expected page_context', () => {
    const request = payloadToChatRequest(
      { kind: 'positions', book_id: 'fx-main', instrument_id: 'EURUSD' },
      'top movers?',
    )

    expect(request.page_context).toEqual({
      page: 'positions',
      book_id: 'fx-main',
      instrument_id: 'EURUSD',
    })
  })

  it('pnl-attribution maps to expected page_context', () => {
    const request = payloadToChatRequest(
      { kind: 'pnl-attribution', book_id: 'fx-main', date: '2026-05-18' },
      'what drove pnl?',
    )

    expect(request.page_context).toEqual({
      page: 'pnl-attribution',
      book_id: 'fx-main',
      date: '2026-05-18',
    })
  })

  it('alerts maps to expected page_context', () => {
    const request = payloadToChatRequest(
      { kind: 'alerts', book_id: 'fx-main', alert_id: 'a-1' },
      'explain the breach',
    )

    expect(request.page_context).toEqual({
      page: 'alerts',
      book_id: 'fx-main',
      alert_id: 'a-1',
    })
  })

  it('free-form preserves arbitrary selection', () => {
    const request = payloadToChatRequest(
      {
        kind: 'free-form',
        page: 'free-form',
        selection: { book_id: 'x', extra: 42 },
      },
      'open question',
    )

    expect(request.page_context.page).toBe('free-form')
    expect(
      (request.page_context.selection as Record<string, unknown>).book_id,
    ).toBe('x')
    expect(
      (request.page_context.selection as Record<string, unknown>).extra,
    ).toBe(42)
  })

  it('omits undefined optional fields from page_context', () => {
    const request = payloadToChatRequest(
      { kind: 'var-dashboard', book_id: 'fx-main' },
      'hello',
    )

    expect(request.page_context).toEqual({
      page: 'var-dashboard',
      book_id: 'fx-main',
    })
    expect(Object.prototype.hasOwnProperty.call(request.page_context, 'as_of')).toBe(
      false,
    )
  })
})
