import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { CommandPalette, type CommandItem } from './CommandPalette'
import type { ChatChunk, ChatRequest, ToolCall } from '../api/copilot'

/** Signature of the injectable `chatFn` prop — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

function buildItems(activate: (id: string) => void): CommandItem[] {
  return [
    { id: 'tab:positions', group: 'Tabs', label: 'Positions', onActivate: () => activate('tab:positions') },
    { id: 'tab:trades', group: 'Tabs', label: 'Trades', onActivate: () => activate('tab:trades') },
    { id: 'tab:risk', group: 'Tabs', label: 'Risk', onActivate: () => activate('tab:risk') },
    { id: 'book:book-1', group: 'Books', label: 'book-1', onActivate: () => activate('book:book-1') },
    { id: 'instrument:AAPL', group: 'Instruments', label: 'AAPL', onActivate: () => activate('instrument:AAPL') },
    { id: 'scenario:MARKET_CRASH', group: 'Scenarios', label: 'MARKET_CRASH', onActivate: () => activate('scenario:MARKET_CRASH') },
  ]
}

const RECENT_KEY = 'kinetix:command-palette:recent'

describe('CommandPalette', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it('renders nothing when open is false', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={false}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    expect(screen.queryByTestId('command-palette')).not.toBeInTheDocument()
  })

  it('renders dialog with input when open', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const dialog = screen.getByTestId('command-palette')
    expect(dialog).toBeInTheDocument()
    expect(dialog).toHaveAttribute('role', 'dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAttribute('aria-label', 'Command palette')
    expect(screen.getByTestId('command-palette-input')).toBeInTheDocument()
  })

  it('autofocuses the input on open', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    expect(document.activeElement).toBe(input)
  })

  it('filters items by exact substring match', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'rades' } })

    expect(screen.getByTestId('command-palette-item-tab:trades')).toBeInTheDocument()
    expect(screen.queryByTestId('command-palette-item-tab:positions')).not.toBeInTheDocument()
  })

  it('filters items by subsequence match when substring does not match', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    // 'mch' is a subsequence of MARKET_CRASH (M..C...H... actually 'mch' -> M,C,H)
    fireEvent.change(input, { target: { value: 'mch' } })
    expect(screen.getByTestId('command-palette-item-scenario:MARKET_CRASH')).toBeInTheDocument()
  })

  it('shows empty-state when no items match', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch' } })
    expect(screen.getByTestId('command-palette-empty')).toBeInTheDocument()
  })

  it('arrow down moves selection, Enter activates the selected item', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    const input = screen.getByTestId('command-palette-input')

    // Typing 'a' substring-matches: Trades, AAPL, MARKET_CRASH (in input order).
    fireEvent.change(input, { target: { value: 'a' } })

    // First item (Trades) is highlighted by default — ArrowDown selects AAPL.
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(activate).toHaveBeenCalledWith('instrument:AAPL')
  })

  it('arrow up moves selection backwards', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'a' } })

    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowUp' })
    fireEvent.keyDown(input, { key: 'Enter' })

    // 0 (Trades) -> 1 (AAPL) -> 2 (MARKET_CRASH) -> 1 (AAPL)
    expect(activate).toHaveBeenCalledWith('instrument:AAPL')
  })

  it('Escape closes the palette', () => {
    const onClose = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={onClose}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('clicking an item activates it', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:risk'))
    expect(activate).toHaveBeenCalledWith('tab:risk')
  })

  it('activating an item also closes the palette', () => {
    const activate = vi.fn()
    const onClose = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={onClose}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:risk'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('shows group headings when results span multiple groups', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    // Empty query → all items shown → multiple groups
    expect(screen.getByTestId('command-palette-group-Tabs')).toBeInTheDocument()
    expect(screen.getByTestId('command-palette-group-Books')).toBeInTheDocument()
    expect(screen.getByTestId('command-palette-group-Scenarios')).toBeInTheDocument()
  })

  it('renders Recent group at top when input is empty and recent items exist', () => {
    window.localStorage.setItem(
      RECENT_KEY,
      JSON.stringify(['tab:risk', 'scenario:MARKET_CRASH']),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const recentGroup = screen.getByTestId('command-palette-group-Recent')
    expect(recentGroup).toBeInTheDocument()
    // The recent group should appear before the Tabs group in the DOM.
    const tabsGroup = screen.getByTestId('command-palette-group-Tabs')
    expect(
      recentGroup.compareDocumentPosition(tabsGroup) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    // Recent entries should reference the persisted item IDs
    expect(screen.getByTestId('command-palette-item-recent-tab:risk')).toBeInTheDocument()
    expect(screen.getByTestId('command-palette-item-recent-scenario:MARKET_CRASH')).toBeInTheDocument()
  })

  it('hides Recent group when input is non-empty', () => {
    window.localStorage.setItem(
      RECENT_KEY,
      JSON.stringify(['tab:risk']),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'pos' } })
    expect(screen.queryByTestId('command-palette-group-Recent')).not.toBeInTheDocument()
  })

  it('persists activated item to recent list (capped at 5, newest first)', () => {
    const activate = vi.fn()
    const { rerender } = render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:risk'))

    // After activation, the recent-items list should be persisted
    const persisted = JSON.parse(
      window.localStorage.getItem(RECENT_KEY) ?? '[]',
    ) as string[]
    expect(persisted[0]).toBe('tab:risk')

    // Activate another → it goes to the front, previous moves down
    rerender(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:trades'))
    const updated = JSON.parse(
      window.localStorage.getItem(RECENT_KEY) ?? '[]',
    ) as string[]
    expect(updated[0]).toBe('tab:trades')
    expect(updated[1]).toBe('tab:risk')
  })
})

/**
 * Build a ``ReadableStream<ChatChunk>`` that emits the supplied chunks
 * synchronously inside ``start`` and then closes. Mirrors the
 * ``streamOf`` helpers in ``AIInsightPanel.test.tsx`` /
 * ``StreamingNarrative.test.tsx``.
 */
function streamOf(...chunks: ChatChunk[]): ReadableStream<ChatChunk> {
  return new ReadableStream<ChatChunk>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    },
  })
}

const doneChunk: ChatChunk = {
  type: 'done',
  session_id: 's-1',
  conversation_id: 'c-1',
  model: 'claude-opus-4-7',
  mode: 'live',
}

describe('CommandPalette — copilot mode', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it('does not render the copilot zone when copilotMode is false', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    expect(
      screen.queryByTestId('command-palette-copilot-zone'),
    ).not.toBeInTheDocument()
  })

  it('renders the copilot zone and hint when copilotMode is true', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
      />,
    )
    expect(
      screen.getByTestId('command-palette-copilot-zone'),
    ).toBeInTheDocument()
    expect(
      screen.getByTestId('command-palette-copilot-hint'),
    ).toBeInTheDocument()
    expect(
      screen.queryByTestId('command-palette-copilot-response'),
    ).not.toBeInTheDocument()
  })

  it('changes the input placeholder in copilot mode', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    expect(input.getAttribute('placeholder') ?? '').toMatch(/copilot/i)
  })

  it('fires chat() and renders StreamingNarrative when a free-form query is submitted', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Your VaR rose on tech beta.' }, doneChunk),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'why is my VaR elevated' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(chatFn).toHaveBeenCalledTimes(1)
    expect(chatFn.mock.calls[0][0]).toMatchObject({
      message: 'why is my VaR elevated',
      page_context: {},
    })
    expect(
      screen.getByTestId('command-palette-copilot-response'),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(
        screen.getByTestId('streaming-narrative-text'),
      ).toHaveTextContent('Your VaR rose on tech beta.'),
    )
  })

  it('renders CitationList after the copilot stream completes with citations', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'VaR is 5.2M USD.' }, {
          ...doneChunk,
          citations: [
            {
              tool: 'risk.var',
              params: { book_id: 'BOOK-1' },
              result_field: 'value',
              result_value: 5.2,
              result_currency: 'USD',
              as_of_timestamp: '2026-05-20T00:00:00Z',
              data_source: 'risk-orchestrator',
              freshness_seconds: 30,
              quality_flags: [],
            },
          ],
        }),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'explain my VaR breakdown' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(
        screen.getByTestId('command-palette-copilot-citations'),
      ).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(
        screen.getByTestId('streaming-narrative-text'),
      ).toHaveTextContent('VaR is 5.2M USD.'),
    )
  })

  it('Enter still activates a matching command in copilot mode', () => {
    const activate = vi.fn()
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> => streamOf(doneChunk),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'Trades' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(activate).toHaveBeenCalledWith('tab:trades')
    expect(chatFn).not.toHaveBeenCalled()
  })

  it('Enter fires the copilot when the query matches no command', async () => {
    const activate = vi.fn()
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Answer.' }, doneChunk),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch what should i do' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(chatFn).toHaveBeenCalledTimes(1)
    expect(activate).not.toHaveBeenCalled()
    // Let the injected stream settle so trailing setState calls land
    // inside act().
    await waitFor(() =>
      expect(
        screen.getByTestId('streaming-narrative-text'),
      ).toHaveTextContent('Answer.'),
    )
  })

  it('renders a follow-up textarea after the first answer', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'First answer.' }, doneChunk),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch help' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(
        screen.getByTestId('command-palette-copilot-followup'),
      ).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(
        screen.getByTestId('streaming-narrative-text'),
      ).toHaveTextContent('First answer.'),
    )
  })

  it('follow-up textarea: Enter sends, Shift+Enter inserts a newline', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'An answer.' }, doneChunk),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch help' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    const followup = await screen.findByTestId('command-palette-copilot-followup')
    expect(chatFn).toHaveBeenCalledTimes(1)

    // Shift+Enter must NOT send — the component leaves the keystroke to
    // the browser's native textarea behaviour (newline insertion). jsdom
    // does not synthesise that insertion, so we model it with a change
    // event carrying the newline, then assert the value holds it.
    fireEvent.change(followup, { target: { value: 'tell me more' } })
    fireEvent.keyDown(followup, { key: 'Enter', shiftKey: true })
    expect(chatFn).toHaveBeenCalledTimes(1)
    fireEvent.change(followup, { target: { value: 'tell me more\nand more' } })
    expect((followup as HTMLTextAreaElement).value).toContain('\n')
    expect(chatFn).toHaveBeenCalledTimes(1)

    // Enter (no shift) sends a second chat() call.
    fireEvent.keyDown(followup, { key: 'Enter' })
    expect(chatFn).toHaveBeenCalledTimes(2)
    // Settle the second stream so trailing setState calls land in act().
    await waitFor(() =>
      expect(
        screen.getByTestId('streaming-narrative-text'),
      ).toHaveTextContent('An answer.'),
    )
  })

  it('follow-up Enter with an empty textarea does nothing', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'An answer.' }, doneChunk),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch help' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    const followup = await screen.findByTestId('command-palette-copilot-followup')
    expect(chatFn).toHaveBeenCalledTimes(1)
    await waitFor(() =>
      expect(
        screen.getByTestId('streaming-narrative-text'),
      ).toHaveTextContent('An answer.'),
    )

    fireEvent.keyDown(followup, { key: 'Enter' })
    expect(chatFn).toHaveBeenCalledTimes(1)
  })

  it('does not show the copilot zone when the palette is closed even with copilotMode', () => {
    render(
      <CommandPalette
        open={false}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
      />,
    )
    expect(screen.queryByTestId('command-palette')).not.toBeInTheDocument()
    expect(
      screen.queryByTestId('command-palette-copilot-zone'),
    ).not.toBeInTheDocument()
  })

  it('shows the source-of-truth footnote in the empty-state of copilot mode', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
      />,
    )
    expect(
      screen.getByText(/Dashboards remain the source of truth/i),
    ).toBeInTheDocument()
  })

  it('renders a Demo mode badge after a canned (offline) stream completes', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'A canned answer.' }, {
          ...doneChunk,
          model: 'canned-chat',
          mode: 'canned',
        }),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch help' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(
        screen.getByTestId('command-palette-copilot-demo-badge'),
      ).toHaveTextContent('Demo mode'),
    )
  })

  it('does not render the Demo mode badge when the stream completes in live mode', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'A live answer.' }, doneChunk),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch help' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(
        screen.getByTestId('streaming-narrative-text'),
      ).toHaveTextContent('A live answer.'),
    )
    expect(
      screen.queryByTestId('command-palette-copilot-demo-badge'),
    ).not.toBeInTheDocument()
  })
})

describe('CommandPalette — book-boundary conversation reset', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it('resets copilot state and shows banner when bookId changes while copilot state is non-empty', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Answer about book A.' }, doneChunk),
    )
    const { rerender } = render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="book-A"
      />,
    )
    // Ask a question to put non-empty copilot state in place.
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch var' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    // Wait for the stream to settle so copilotAnswered / copilotStream are non-empty.
    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent('Answer about book A.'),
    )

    // Switch to a different book.
    rerender(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="book-B"
      />,
    )

    // Banner must appear.
    await waitFor(() =>
      expect(screen.getByTestId('command-palette-book-reset-banner')).toBeInTheDocument(),
    )
    // Copilot response is cleared.
    expect(screen.queryByTestId('command-palette-copilot-response')).not.toBeInTheDocument()
  })

  it('banner text contains the new book name', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Some answer.' }, doneChunk),
    )
    const { rerender } = render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="alpha-book"
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch question' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent('Some answer.'),
    )

    rerender(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="beta-book"
      />,
    )

    await waitFor(() =>
      expect(screen.getByTestId('command-palette-book-reset-banner')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('command-palette-book-reset-banner')).toHaveTextContent('beta-book')
  })

  it('typing a new message dismisses the banner', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Answer.' }, doneChunk),
    )
    const { rerender } = render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="book-X"
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch question' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent('Answer.'),
    )

    rerender(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="book-Y"
      />,
    )

    await waitFor(() =>
      expect(screen.getByTestId('command-palette-book-reset-banner')).toBeInTheDocument(),
    )

    // Typing any character dismisses the banner.
    fireEvent.change(input, { target: { value: 'h' } })
    expect(screen.queryByTestId('command-palette-book-reset-banner')).not.toBeInTheDocument()
  })

  it('re-rendering with the same bookId does NOT reset or show a banner', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Stable answer.' }, doneChunk),
    )
    const { rerender } = render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="same-book"
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch question' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent('Stable answer.'),
    )

    rerender(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="same-book"
      />,
    )

    expect(screen.queryByTestId('command-palette-book-reset-banner')).not.toBeInTheDocument()
    // Copilot response persists.
    expect(screen.getByTestId('command-palette-copilot-response')).toBeInTheDocument()
  })

  it('re-rendering with bookId=null while previously set DOES reset', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Initial answer.' }, doneChunk),
    )
    const { rerender } = render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId="some-book"
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch question' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent('Initial answer.'),
    )

    rerender(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
        bookId={null}
      />,
    )

    await waitFor(() =>
      expect(screen.getByTestId('command-palette-book-reset-banner')).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('command-palette-copilot-response')).not.toBeInTheDocument()
  })

  it('renders the ToolCallList reasoning panel when the done chunk includes tool_calls', async () => {
    const toolCalls: ToolCall[] = [
      {
        name: 'get_book_var',
        params: { book_id: 'fx-main' },
        status: 'ok',
        started_at: '2026-05-19T08:00:00.000Z',
        completed_at: '2026-05-19T08:00:00.250Z',
      },
    ]
    const doneWithToolCalls: ChatChunk = {
      type: 'done',
      session_id: 's-tc',
      conversation_id: 'c-tc',
      model: 'canned-chat',
      mode: 'canned',
      tool_calls: toolCalls,
    }
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'VaR is up.' }, doneWithToolCalls),
    )

    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )

    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch var question' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent('VaR is up.'),
    )

    // The reasoning panel must appear inside the copilot response zone.
    expect(screen.getByTestId('tool-call-list')).toBeInTheDocument()
    expect(screen.getAllByTestId('tool-call-row')).toHaveLength(1)
  })

  it('does not render the ToolCallList when done chunk has no tool_calls', async () => {
    const chatFn = vi.fn<ChatFn>(
      (): ReadableStream<ChatChunk> =>
        streamOf({ type: 'delta', delta: 'Answer.' }, doneChunk),
    )

    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
        copilotMode
        chatFn={chatFn}
      />,
    )

    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch question' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent('Answer.'),
    )

    expect(screen.queryByTestId('tool-call-list')).not.toBeInTheDocument()
  })
})
