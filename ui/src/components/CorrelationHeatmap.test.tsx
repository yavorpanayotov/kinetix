import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import type { ChatChunk, ChatRequest } from '../api/copilot'
import { CorrelationHeatmap } from './CorrelationHeatmap'

/**
 * Build a `ReadableStream<ChatChunk>` that emits one delta chunk then a
 * terminal `done` — the streaming-explainer test double (plan §9.5).
 */
function fakeChatStream(): ReadableStream<ChatChunk> {
  return new ReadableStream<ChatChunk>({
    start(controller) {
      controller.enqueue({ type: 'delta', delta: 'Equity and derivatives co-move.' })
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

describe('CorrelationHeatmap', () => {
  it('renders heatmap with all 5 asset classes by default', () => {
    render(<CorrelationHeatmap />)

    expect(screen.getByTestId('correlation-heatmap')).toBeInTheDocument()

    // 5x5 = 25 cells
    const allClasses = ['EQUITY', 'FIXED_INCOME', 'FX', 'COMMODITY', 'DERIVATIVE']
    for (const row of allClasses) {
      for (const col of allClasses) {
        expect(screen.getByTestId(`correlation-cell-${row}-${col}`)).toBeInTheDocument()
      }
    }
  })

  it('filters to provided asset classes', () => {
    render(<CorrelationHeatmap assetClasses={['EQUITY', 'FX']} />)

    // Should have 2x2 = 4 cells
    expect(screen.getByTestId('correlation-cell-EQUITY-EQUITY')).toBeInTheDocument()
    expect(screen.getByTestId('correlation-cell-EQUITY-FX')).toBeInTheDocument()
    expect(screen.getByTestId('correlation-cell-FX-EQUITY')).toBeInTheDocument()
    expect(screen.getByTestId('correlation-cell-FX-FX')).toBeInTheDocument()

    // Should NOT have cells for other asset classes
    expect(screen.queryByTestId('correlation-cell-EQUITY-FIXED_INCOME')).not.toBeInTheDocument()
    expect(screen.queryByTestId('correlation-cell-COMMODITY-COMMODITY')).not.toBeInTheDocument()
  })

  it('displays correct correlation value in cells', () => {
    render(<CorrelationHeatmap />)

    const equityFxCell = screen.getByTestId('correlation-cell-EQUITY-FX')
    expect(equityFxCell).toHaveTextContent('0.30')

    const fiEquityCell = screen.getByTestId('correlation-cell-FIXED_INCOME-EQUITY')
    expect(fiEquityCell).toHaveTextContent('-0.20')
  })

  it('diagonal cells show 1.00', () => {
    render(<CorrelationHeatmap />)

    expect(screen.getByTestId('correlation-cell-EQUITY-EQUITY')).toHaveTextContent('1.00')
    expect(screen.getByTestId('correlation-cell-FX-FX')).toHaveTextContent('1.00')
    expect(screen.getByTestId('correlation-cell-DERIVATIVE-DERIVATIVE')).toHaveTextContent('1.00')
  })

  describe('matrix-level inline explainer (plan §9.5)', () => {
    it('renders a matrix-level Explain button with no panel before it is clicked', () => {
      render(<CorrelationHeatmap />)

      expect(screen.getByTestId('explain-correlation-matrix')).toBeInTheDocument()
      expect(screen.queryByTestId('correlation-explain-panel')).not.toBeInTheDocument()
    })

    it('opens the streaming insight panel when the Explain button is clicked', async () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(<CorrelationHeatmap chatFn={chatFn} />)

      await userEvent.click(screen.getByTestId('explain-correlation-matrix'))

      expect(screen.getByTestId('correlation-explain-panel')).toBeInTheDocument()
      await waitFor(() =>
        expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
          'Equity and derivatives co-move.',
        ),
      )
    })

    it('attaches the derived correlation breaks to the explain page_context', async () => {
      const chatFn = vi.fn<(req: ChatRequest) => ReadableStream<ChatChunk>>(
        () => fakeChatStream(),
      )
      render(<CorrelationHeatmap chatFn={chatFn} />)

      await userEvent.click(screen.getByTestId('explain-correlation-matrix'))

      expect(chatFn).toHaveBeenCalledTimes(1)
      const request = chatFn.mock.calls[0][0]
      const ctx = request.page_context as Record<string, unknown>
      expect(ctx.page).toBe('correlation-matrix')
      expect(ctx.asset_classes).toEqual([
        'EQUITY',
        'FIXED_INCOME',
        'FX',
        'COMMODITY',
        'DERIVATIVE',
      ])

      // Breaks: off-diagonal pairs with |corr| >= 0.35, ranked most
      // extreme first. EQUITY-DERIVATIVE 0.70 is the strongest.
      const breaks = ctx.correlation_breaks as {
        a: string
        b: string
        correlation: number
      }[]
      expect(breaks.length).toBeGreaterThan(0)
      expect(breaks[0]).toEqual({
        a: 'EQUITY',
        b: 'DERIVATIVE',
        correlation: 0.7,
      })
      // All surfaced pairs clear the break threshold.
      for (const b of breaks) {
        expect(Math.abs(b.correlation)).toBeGreaterThanOrEqual(0.35)
      }
    })

    it('a rapid double-click does not fire a duplicate chat request', async () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(<CorrelationHeatmap chatFn={chatFn} />)

      const button = screen.getByTestId('explain-correlation-matrix')
      await userEvent.click(button)
      await userEvent.click(button)

      expect(chatFn).toHaveBeenCalledTimes(1)
      expect(screen.getAllByTestId('correlation-explain-panel')).toHaveLength(1)
    })

    it('the panel close button dismisses the explainer', async () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(<CorrelationHeatmap chatFn={chatFn} />)

      await userEvent.click(screen.getByTestId('explain-correlation-matrix'))
      expect(screen.getByTestId('correlation-explain-panel')).toBeInTheDocument()

      await userEvent.click(screen.getByTestId('ai-insight-close'))
      expect(screen.queryByTestId('correlation-explain-panel')).not.toBeInTheDocument()
    })
  })
})
