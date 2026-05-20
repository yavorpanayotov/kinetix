import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PnlWaterfallChart } from './PnlWaterfallChart'
import type { PnlAttributionDto } from '../types'
import type { ChatChunk, ChatRequest } from '../api/copilot'

/**
 * Build a `ReadableStream<ChatChunk>` that emits a couple of delta
 * chunks then a terminal `done` — the streaming-explainer test double.
 */
function fakeChatStream(): ReadableStream<ChatChunk> {
  return new ReadableStream<ChatChunk>({
    start(controller) {
      controller.enqueue({ type: 'delta', delta: 'Delta drove the gain.' })
      controller.enqueue({
        type: 'done',
        session_id: 's',
        conversation_id: 'c',
        model: 'canned-chat',
        mode: 'canned',
      })
      controller.close()
    },
  })
}

const attribution: PnlAttributionDto = {
  bookId: 'book-1',
  date: '2025-01-15',
  totalPnl: '15000.00',
  deltaPnl: '8000.00',
  gammaPnl: '2500.00',
  vegaPnl: '3000.00',
  thetaPnl: '-1500.00',
  rhoPnl: '500.00',
  unexplainedPnl: '2500.00',
  positionAttributions: [],
  calculatedAt: '2025-01-15T10:30:00Z',
}

describe('PnlWaterfallChart', () => {
  it('renders all factor bars plus total', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByTestId('waterfall-chart')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-delta')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-gamma')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-vega')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-theta')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-rho')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-unexplained')).toBeInTheDocument()
    expect(screen.getByTestId('waterfall-bar-total')).toBeInTheDocument()
  })

  it('displays factor labels', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByText('Delta')).toBeInTheDocument()
    expect(screen.getByText('Gamma')).toBeInTheDocument()
    expect(screen.getByText('Vega')).toBeInTheDocument()
    expect(screen.getByText('Theta')).toBeInTheDocument()
    expect(screen.getByText('Rho')).toBeInTheDocument()
    expect(screen.getByText('Unexplained')).toBeInTheDocument()
    expect(screen.getByText('Total')).toBeInTheDocument()
  })

  it('displays formatted amounts for each factor', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByTestId('waterfall-value-delta')).toHaveTextContent('8,000.00')
    expect(screen.getByTestId('waterfall-value-theta')).toHaveTextContent('-1,500.00')
    expect(screen.getByTestId('waterfall-value-total')).toHaveTextContent('15,000.00')
  })

  it('prefixes a + on positive factor values and not on negative or zero', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByTestId('waterfall-value-delta').textContent).toBe('+8,000.00')
    expect(screen.getByTestId('waterfall-value-total').textContent).toBe('+15,000.00')
    expect(screen.getByTestId('waterfall-value-theta').textContent).toBe('-1,500.00')
  })

  it('applies green color class to positive values and red to negative', () => {
    render(<PnlWaterfallChart data={attribution} />)

    const deltaValue = screen.getByTestId('waterfall-value-delta')
    expect(deltaValue.className).toContain('text-green-600')

    const thetaValue = screen.getByTestId('waterfall-value-theta')
    expect(thetaValue.className).toContain('text-red-600')
  })

  it('renders an Explain button above the waterfall body', () => {
    render(<PnlWaterfallChart data={attribution} />)

    expect(screen.getByTestId('explain-pnl-attribution')).toBeInTheDocument()
    // No insight panel before the button is clicked.
    expect(screen.queryByTestId('pnl-explain-panel')).not.toBeInTheDocument()
  })

  it('opens the streaming insight panel when the Explain button is clicked', async () => {
    const chatFn = vi.fn(() => fakeChatStream())
    render(<PnlWaterfallChart data={attribution} chatFn={chatFn} />)

    await userEvent.click(screen.getByTestId('explain-pnl-attribution'))

    expect(screen.getByTestId('pnl-explain-panel')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
        'Delta drove the gain.',
      ),
    )
  })

  it('attaches the attribution date and top-N drivers to the explain page_context', async () => {
    const chatFn = vi.fn(() => fakeChatStream())
    render(<PnlWaterfallChart data={attribution} chatFn={chatFn} />)

    await userEvent.click(screen.getByTestId('explain-pnl-attribution'))

    expect(chatFn).toHaveBeenCalledTimes(1)
    const request = chatFn.mock.calls[0][0] as ChatRequest
    const ctx = request.page_context as Record<string, unknown>
    expect(ctx.page).toBe('pnl-attribution')
    expect(ctx.date).toBe('2025-01-15')
    expect(ctx.book_id).toBe('book-1')

    // Top drivers — three factors sorted by absolute contribution.
    // delta 8000 > vega 3000 > gamma 2500 (theta -1500, rho 500, total excluded).
    const drivers = ctx.top_drivers as { factor: string; value: number }[]
    expect(drivers).toHaveLength(3)
    expect(drivers.map((d) => d.factor)).toEqual(['Delta', 'Vega', 'Gamma'])
    expect(drivers[0].value).toBe(8000)
  })

  it('a rapid double-click does not fire a duplicate chat request', async () => {
    const chatFn = vi.fn(() => fakeChatStream())
    render(<PnlWaterfallChart data={attribution} chatFn={chatFn} />)

    const button = screen.getByTestId('explain-pnl-attribution')
    await userEvent.click(button)
    await userEvent.click(button)

    expect(chatFn).toHaveBeenCalledTimes(1)
    expect(screen.getAllByTestId('pnl-explain-panel')).toHaveLength(1)
  })

  it('the panel close button dismisses the explainer', async () => {
    const chatFn = vi.fn(() => fakeChatStream())
    render(<PnlWaterfallChart data={attribution} chatFn={chatFn} />)

    await userEvent.click(screen.getByTestId('explain-pnl-attribution'))
    expect(screen.getByTestId('pnl-explain-panel')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('ai-insight-close'))
    expect(screen.queryByTestId('pnl-explain-panel')).not.toBeInTheDocument()
  })
})
