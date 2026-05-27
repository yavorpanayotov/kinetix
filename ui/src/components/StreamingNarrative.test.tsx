import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatChunk, Citation, ToolCall } from '../api/copilot'
import { StreamingNarrative } from './StreamingNarrative'

/**
 * Build a ``ReadableStream<ChatChunk>`` that emits the supplied chunks
 * synchronously inside ``start`` and then closes the controller. Suitable
 * for "happy path" tests where every chunk is available up front.
 */
function streamOf(...chunks: ChatChunk[]): ReadableStream<ChatChunk> {
  return new ReadableStream<ChatChunk>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    },
  })
}

/**
 * Build a stream whose underlying source's ``cancel`` method is a
 * ``vi.fn()`` we can assert against from outside. The stream is left
 * open (never enqueues, never closes) so the consumer can ``cancel()``
 * deterministically.
 */
function pendingStreamWithCancelSpy(): {
  stream: ReadableStream<ChatChunk>
  cancelSpy: ReturnType<typeof vi.fn>
} {
  const cancelSpy = vi.fn()
  const stream = new ReadableStream<ChatChunk>({
    start() {
      // Intentionally idle — the test will unmount before any data
      // arrives so the component must invoke ``reader.cancel`` on
      // teardown. ``reader.cancel`` is plumbed through to the
      // underlying source's ``cancel`` callback, which we capture
      // here.
    },
    cancel(reason) {
      cancelSpy(reason)
    },
  })
  return { stream, cancelSpy }
}

/**
 * Resolve all pending microtasks plus one RAF + 50 ms throttle cycle so
 * the component flushes buffered tokens deterministically. The component
 * uses ``requestAnimationFrame`` + a 50 ms gate, so we have to
 * (1) let the ``reader.read`` promise chain settle (microtasks) and
 * (2) advance fake timers past the throttle, then (3) let the post-flush
 * microtasks (``setRenderedText``) settle again.
 */
async function flushPumpAndRaf(): Promise<void> {
  await act(async () => {
    // Drain queued promise resolutions so the pump's ``await
    // reader.read()`` lands inside our render path.
    await Promise.resolve()
    await Promise.resolve()
    // Step over the RAF + throttle window — 50 ms is the gate, but we
    // step 80 ms to keep tests resilient to scheduling drift.
    vi.advanceTimersByTime(80)
    await Promise.resolve()
    await Promise.resolve()
  })
}

function makeCitation(overrides: Partial<Citation> = {}): Citation {
  return {
    tool: 'get_book_var',
    params: { book_id: 'book-1' },
    result_field: 'var_total',
    result_value: 1_250_000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-19T12:00:00Z',
    data_source: 'risk-engine',
    freshness_seconds: 30,
    quality_flags: [],
    ...overrides,
  }
}

function makeToolCall(overrides: Partial<ToolCall> = {}): ToolCall {
  return {
    name: 'get_book_var',
    params: { book_id: 'fx-main' },
    status: 'ok',
    started_at: '2026-05-19T08:00:00.000Z',
    completed_at: '2026-05-19T08:00:00.250Z',
    ...overrides,
  }
}

describe('StreamingNarrative', () => {
  beforeEach(() => {
    // ``toFake`` keeps queueMicrotask / Promise unaffected so
    // ``await Promise.resolve()`` still flushes microtasks while we
    // step ``requestAnimationFrame`` + ``setTimeout`` manually.
    vi.useFakeTimers({
      toFake: ['setTimeout', 'clearTimeout', 'requestAnimationFrame', 'cancelAnimationFrame', 'Date'],
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders skeleton state when stream is provided but no chunks yet', async () => {
    const stream = new ReadableStream<ChatChunk>({
      start() {
        /* never enqueues, never closes */
      },
    })

    render(<StreamingNarrative stream={stream} reducedMotion />)

    const wrapper = screen.getByTestId('streaming-narrative')
    expect(wrapper).toHaveAttribute('data-state', 'skeleton')
    expect(screen.getByTestId('streaming-narrative-skeleton')).toBeInTheDocument()
  })

  it('keeps skeleton state until the first delta arrives (no implicit "awaiting-first-token" transition on time alone)', async () => {
    const stream = new ReadableStream<ChatChunk>({
      start() {
        /* never enqueues */
      },
    })

    render(<StreamingNarrative stream={stream} reducedMotion />)

    // Advance well past any plausible "stream attached" debounce. The
    // contract pinned here: with zero deltas the state remains
    // ``skeleton``. Components that flip to ``awaiting-first-token``
    // on time alone would fail this assertion — chosen to avoid
    // surfacing a transient state the user can't act on.
    await act(async () => {
      vi.advanceTimersByTime(500)
      await Promise.resolve()
    })

    expect(screen.getByTestId('streaming-narrative')).toHaveAttribute(
      'data-state',
      'skeleton',
    )
  })

  it('transitions to token-flow on the first delta', async () => {
    const stream = streamOf({ type: 'delta', delta: 'Hello' })

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()

    const wrapper = screen.getByTestId('streaming-narrative')
    expect(wrapper).toHaveAttribute('data-state', 'token-flow')
    expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
      'Hello',
    )
  })

  it('accumulates multiple delta chunks into the rendered text', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Hello ' },
      { type: 'delta', delta: 'world' },
      { type: 'delta', delta: '!' },
    )

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()
    // A second RAF + throttle window in case the deltas spilled across
    // batches — the final text must still equal the full concatenation.
    await flushPumpAndRaf()

    expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
      'Hello world!',
    )
  })

  it('transitions to complete on the done chunk and stops rendering the animated cursor', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Final answer.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
      },
    )

    render(<StreamingNarrative stream={stream} />)

    // Flush twice — once for the delta + ``done`` reads to land and
    // once more so the terminal ``setState`` batch commits.
    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const wrapper = screen.getByTestId('streaming-narrative')
    expect(wrapper).toHaveAttribute('data-state', 'complete')
    expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
      'Final answer.',
    )
    // The blinking caret only exists while we're in ``token-flow``;
    // once ``complete`` arrives it must be removed from the DOM.
    expect(
      screen.queryByTestId('streaming-narrative-cursor'),
    ).not.toBeInTheDocument()
  })

  it('invokes onComplete exactly once with the final narrative, citations, model, and mode', async () => {
    const onComplete = vi.fn()
    const citation = makeCitation()
    const stream = streamOf(
      { type: 'delta', delta: 'VaR is up 12% ' },
      { type: 'delta', delta: 'on tech beta.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
        citations: [citation],
      },
    )

    render(<StreamingNarrative stream={stream} onComplete={onComplete} />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    expect(onComplete).toHaveBeenCalledTimes(1)
    expect(onComplete).toHaveBeenCalledWith({
      narrative: 'VaR is up 12% on tech beta.',
      citations: [citation],
      model: 'claude-opus-4-7',
      mode: 'live',
      errorCode: undefined,
    })
  })

  it('renders POLICY_VIOLATION as a rose panel with mapped title and body — not the raw code', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Partial answer before guardrail.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'canned',
        error_code: 'POLICY_VIOLATION',
      },
    )

    render(<StreamingNarrative stream={stream} />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const wrapper = screen.getByTestId('streaming-narrative')
    expect(wrapper).toHaveAttribute('data-state', 'error')

    const banner = screen.getByTestId('streaming-narrative-error')
    expect(banner).toHaveAttribute('role', 'alert')
    // Raw code must NOT appear.
    expect(banner).not.toHaveTextContent('POLICY_VIOLATION')
    // Mapped title and body must appear.
    expect(banner).toHaveTextContent("I can't answer that here")
    expect(banner).toHaveTextContent(
      'I can only narrate data, not advise on actions. Try rephrasing as a question about what the numbers show.',
    )
    // Rose panel classes.
    expect(banner).toHaveClass('bg-rose-500/10')
    expect(banner).toHaveClass('border-rose-500/30')
    // Accumulated narrative remains visible alongside the error.
    expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
      'Partial answer before guardrail.',
    )
  })

  it('renders CITATION_UNVERIFIABLE as a rose panel with mapped title and body', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Some numbers here.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'canned',
        error_code: 'CITATION_UNVERIFIABLE',
      },
    )

    render(<StreamingNarrative stream={stream} />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const wrapper = screen.getByTestId('streaming-narrative')
    expect(wrapper).toHaveAttribute('data-state', 'error')

    const banner = screen.getByTestId('streaming-narrative-error')
    expect(banner).toHaveAttribute('role', 'alert')
    // Raw code must NOT appear.
    expect(banner).not.toHaveTextContent('CITATION_UNVERIFIABLE')
    // Mapped title and body must appear.
    expect(banner).toHaveTextContent("I couldn't verify that answer")
    expect(banner).toHaveTextContent(
      "I couldn't verify one of the numbers in this answer. Please cross-check on the dashboard.",
    )
    // Rose panel classes.
    expect(banner).toHaveClass('bg-rose-500/10')
    expect(banner).toHaveClass('border-rose-500/30')
  })

  it('renders an unknown error code using the fallback title and body in a rose panel', async () => {
    const stream = streamOf({
      type: 'done',
      session_id: 's-1',
      conversation_id: 'c-1',
      model: 'claude-opus-4-7',
      mode: 'canned',
      error_code: 'COMPLETELY_UNKNOWN_CODE',
    })

    render(<StreamingNarrative stream={stream} />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const banner = screen.getByTestId('streaming-narrative-error')
    // Raw code must NOT appear.
    expect(banner).not.toHaveTextContent('COMPLETELY_UNKNOWN_CODE')
    // Fallback strings.
    expect(banner).toHaveTextContent('Something went wrong')
    expect(banner).toHaveTextContent('Something went wrong generating this answer.')
    // Rose panel.
    expect(banner).toHaveClass('bg-rose-500/10')
  })

  it('attaches citations from source chunks and the done chunk uniquely', async () => {
    const citation = makeCitation({ result_field: 'var_total' })
    const stream = streamOf(
      { type: 'source', citations: [citation] },
      { type: 'delta', delta: 'See cited values.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
        // Same citation again — the merge must dedupe so the rendered
        // list has length 1.
        citations: [citation],
      },
    )

    render(<StreamingNarrative stream={stream} />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const list = screen.getByTestId('streaming-narrative-citations')
    expect(list.querySelectorAll('li')).toHaveLength(1)
  })

  it('respects reducedMotion=true and does not render an animate-pulse cursor', async () => {
    const stream = streamOf({ type: 'delta', delta: 'Steady prose.' })

    const { container } = render(
      <StreamingNarrative stream={stream} reducedMotion />,
    )

    await flushPumpAndRaf()

    expect(screen.getByTestId('streaming-narrative')).toHaveAttribute(
      'data-state',
      'token-flow',
    )
    expect(container.querySelector('.animate-pulse')).toBeNull()
  })

  it('renders an animated cursor while in token-flow when reducedMotion is false', async () => {
    const stream = streamOf({ type: 'delta', delta: 'Streaming...' })

    const { container } = render(
      <StreamingNarrative stream={stream} reducedMotion={false} />,
    )

    await flushPumpAndRaf()

    expect(screen.getByTestId('streaming-narrative')).toHaveAttribute(
      'data-state',
      'token-flow',
    )
    // The blinking caret uses Tailwind's ``animate-pulse`` utility.
    expect(container.querySelector('.animate-pulse')).not.toBeNull()
  })

  it('cleans up the reader on unmount', async () => {
    const { stream, cancelSpy } = pendingStreamWithCancelSpy()

    const { unmount } = render(
      <StreamingNarrative stream={stream} reducedMotion />,
    )

    unmount()
    // ``reader.cancel`` is fire-and-forget; let the microtask queue
    // drain so the call lands.
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(cancelSpy).toHaveBeenCalledTimes(1)
  })

  it('batches rapid deltas via requestAnimationFrame (final text is the full concatenation)', async () => {
    const deltas: ChatChunk[] = Array.from({ length: 10 }, (_, i) => ({
      type: 'delta',
      delta: `${i}`,
    }))
    const stream = streamOf(...deltas)

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
      '0123456789',
    )
  })

  it('renders an idle wrapper when the stream prop is null (no skeleton, no cursor)', () => {
    render(<StreamingNarrative stream={null} />)

    const wrapper = screen.getByTestId('streaming-narrative')
    expect(wrapper).toHaveAttribute('data-state', 'idle')
    expect(
      screen.queryByTestId('streaming-narrative-skeleton'),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByTestId('streaming-narrative-cursor'),
    ).not.toBeInTheDocument()
  })

  it('applies the aria-label override to the wrapper', () => {
    render(
      <StreamingNarrative stream={null} ariaLabel="my custom narrative" />,
    )

    expect(screen.getByLabelText('my custom narrative')).toBeInTheDocument()
  })

  it('defaults the aria-label to "AI narrative"', () => {
    render(<StreamingNarrative stream={null} />)

    expect(screen.getByLabelText('AI narrative')).toBeInTheDocument()
  })

  it('falls back to the error state when the stream rejects mid-pump', async () => {
    const stream = new ReadableStream<ChatChunk>({
      start(controller) {
        controller.enqueue({ type: 'delta', delta: 'Partial.' })
        controller.error(new Error('boom'))
      },
    })

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const wrapper = screen.getByTestId('streaming-narrative')
    expect(wrapper).toHaveAttribute('data-state', 'error')
    // STREAM_ERROR is an unknown code — falls through to the fallback.
    const banner = screen.getByTestId('streaming-narrative-error')
    expect(banner).not.toHaveTextContent('STREAM_ERROR')
    expect(banner).toHaveTextContent('Something went wrong')
    expect(banner).toHaveTextContent('Something went wrong generating this answer.')
    expect(banner).toHaveClass('bg-rose-500/10')
  })

  it('renders the ToolCallList below the citation list when the done chunk includes tool_calls', async () => {
    const toolCalls = [
      makeToolCall({ name: 'get_book_var' }),
      makeToolCall({ name: 'get_greeks_summary', started_at: '2026-05-19T08:00:00.250Z', completed_at: '2026-05-19T08:00:00.480Z' }),
    ]
    const stream = streamOf(
      { type: 'delta', delta: 'VaR is elevated.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'canned-chat',
        mode: 'canned',
        tool_calls: toolCalls,
      },
    )

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    expect(screen.getByTestId('streaming-narrative')).toHaveAttribute('data-state', 'complete')
    // The reasoning panel must appear.
    expect(screen.getByTestId('tool-call-list')).toBeInTheDocument()
    // Both rows must render.
    expect(screen.getAllByTestId('tool-call-row')).toHaveLength(2)
  })

  it('does not render the ToolCallList when done chunk has no tool_calls', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Short answer.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'canned-chat',
        mode: 'canned',
      },
    )

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    expect(screen.queryByTestId('tool-call-list')).not.toBeInTheDocument()
  })

  it('renders streamed markdown — **bold** becomes <strong>, not raw asterisks', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Driver: **tech beta** rose.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
      },
    )

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const wrapper = screen.getByTestId('streaming-narrative-text')
    expect(wrapper.querySelector('strong')?.textContent).toBe('tech beta')
    // The asterisk source must not survive into the rendered text.
    expect(wrapper.textContent).not.toContain('**')
  })

  it('renders streamed markdown lists — "- item" becomes a <ul><li>', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Drivers:\n\n- AAPL\n- MSFT' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
      },
    )

    render(<StreamingNarrative stream={stream} reducedMotion />)

    await flushPumpAndRaf()
    await flushPumpAndRaf()

    const wrapper = screen.getByTestId('streaming-narrative-text')
    const list = wrapper.querySelector('ul')
    expect(list).not.toBeNull()
    expect(list?.querySelectorAll('li')).toHaveLength(2)
  })
})
