import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { GreeksResultDto } from '../types'
import type { ChatChunk, ChatRequest } from '../api/copilot'
import { RiskSensitivities } from './RiskSensitivities'

/**
 * Build a `ReadableStream<ChatChunk>` that emits one delta chunk then a
 * terminal `done` — the streaming-explainer test double (plan §9.5).
 */
function fakeChatStream(): ReadableStream<ChatChunk> {
  return new ReadableStream<ChatChunk>({
    start(controller) {
      controller.enqueue({ type: 'delta', delta: 'Net delta is long.' })
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

const greeksResult: GreeksResultDto = {
  bookId: 'book-1',
  assetClassGreeks: [
    { assetClass: 'EQUITY', delta: '1234.560000', gamma: '78.900000', vega: '5678.120000' },
    { assetClass: 'COMMODITY', delta: '567.890000', gamma: '12.340000', vega: '2345.670000' },
  ],
  theta: '-123.450000',
  rho: '456.780000',
  calculatedAt: '2025-01-15T10:00:00Z',
}

describe('RiskSensitivities', () => {
  it('renders the heatmap with asset class rows', () => {
    render(<RiskSensitivities greeksResult={greeksResult} />)

    expect(screen.getByTestId('risk-sensitivities')).toBeInTheDocument()
    expect(screen.getByTestId('greeks-heatmap')).toBeInTheDocument()
    expect(screen.getByTestId('greeks-row-EQUITY')).toBeInTheDocument()
    expect(screen.getByTestId('greeks-row-COMMODITY')).toBeInTheDocument()
  })

  it('renders theta and rho as summary cards outside the table', () => {
    render(<RiskSensitivities greeksResult={greeksResult} />)

    const thetaCard = screen.getByTestId('greek-summary-theta')
    expect(thetaCard).toBeInTheDocument()
    expect(thetaCard).toHaveTextContent('-123.45')

    const rhoCard = screen.getByTestId('greek-summary-rho')
    expect(rhoCard).toBeInTheDocument()
    expect(rhoCard).toHaveTextContent('456.78')
  })

  it('does not render theta or rho columns in the asset class table', () => {
    render(<RiskSensitivities greeksResult={greeksResult} />)

    const table = screen.getByTestId('greeks-heatmap')
    const headers = Array.from(table.querySelectorAll('th')).map((h) => h.textContent ?? '')
    expect(headers.join(' ')).not.toMatch(/Theta/)
    expect(headers.join(' ')).not.toMatch(/Rho/)

    // Per-asset rows must have exactly 4 cells: asset class, delta, gamma, vega
    const equityRow = screen.getByTestId('greeks-row-EQUITY')
    expect(equityRow.querySelectorAll('td')).toHaveLength(4)
  })

  it('formats greek values with commas and decimals', () => {
    render(<RiskSensitivities greeksResult={greeksResult} />)

    const equityRow = screen.getByTestId('greeks-row-EQUITY')
    expect(equityRow).toHaveTextContent('1,234.56')
    expect(equityRow).toHaveTextContent('78.90')
    expect(equityRow).toHaveTextContent('5,678.12')
  })

  it('displays PV when pvValue is provided', () => {
    render(<RiskSensitivities greeksResult={greeksResult} pvValue="1800000.00" />)

    const pvDisplay = screen.getByTestId('pv-display')
    expect(pvDisplay).toBeInTheDocument()
    expect(pvDisplay).toHaveTextContent('PV')
    expect(pvDisplay).toHaveTextContent('$1.8M')
  })

  it('does not display PV when pvValue is null', () => {
    render(<RiskSensitivities greeksResult={greeksResult} pvValue={null} />)

    expect(screen.queryByTestId('pv-display')).not.toBeInTheDocument()
  })

  it('does not display PV when pvValue is omitted', () => {
    render(<RiskSensitivities greeksResult={greeksResult} />)

    expect(screen.queryByTestId('pv-display')).not.toBeInTheDocument()
  })

  it('renders a portfolio total row summing delta, gamma, and vega', () => {
    render(<RiskSensitivities greeksResult={greeksResult} />)

    const totalRow = screen.getByTestId('greeks-row-TOTAL')
    expect(totalRow).toBeInTheDocument()
    expect(totalRow).toHaveTextContent('Total')
    // delta: 1234.56 + 567.89 = 1802.45
    expect(totalRow).toHaveTextContent('1,802.45')
    // gamma: 78.90 + 12.34 = 91.24
    expect(totalRow).toHaveTextContent('91.24')
    // vega: 5678.12 + 2345.67 = 8023.79
    expect(totalRow).toHaveTextContent('8,023.79')
  })

  it('renders total row with bold styling', () => {
    render(<RiskSensitivities greeksResult={greeksResult} />)

    const totalRow = screen.getByTestId('greeks-row-TOTAL')
    expect(totalRow.className).toContain('font-semibold')
  })

  describe('Greek unit labels', () => {
    it('displays unit labels on Delta and Vega column headers in the table', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      const table = screen.getByTestId('greeks-heatmap')
      expect(table).toHaveTextContent('Delta ($/1%)')
      expect(table).toHaveTextContent('Vega ($/1pp)')
    })

    it('displays unit labels on Theta and Rho in the summary cards', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      expect(screen.getByTestId('greek-summary-theta')).toHaveTextContent('Theta ($/day)')
      expect(screen.getByTestId('greek-summary-rho')).toHaveTextContent('Rho ($/bp)')
    })

    it('displays Gamma header without inline unit label', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      const table = screen.getByTestId('greeks-heatmap')
      const headers = table.querySelectorAll('th')
      const gammaHeader = Array.from(headers).find((h) => h.textContent?.includes('Gamma'))
      expect(gammaHeader).toBeDefined()
      expect(gammaHeader!.textContent).not.toContain('$/')
    })

    it('renders a footnote below the table explaining sensitivities', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      const footnote = screen.getByTestId('greeks-footnote')
      expect(footnote).toHaveTextContent('Asset-class sensitivities show change in VaR per unit bump. Per-instrument Greeks for options use analytical Black-Scholes. Hover headers for details.')
    })
  })

  describe('Greek info popovers', () => {
    it('shows delta explanation when info icon is clicked', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-delta'))

      const popover = screen.getByTestId('greek-popover-delta')
      expect(popover).toBeInTheDocument()
      expect(popover).toHaveTextContent('Estimated change in portfolio VaR for a $1 move in the underlying')
    })

    it('shows gamma explanation when info icon is clicked', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-gamma'))

      const popover = screen.getByTestId('greek-popover-gamma')
      expect(popover).toBeInTheDocument()
      expect(popover).toHaveTextContent('Rate of change of Delta VaR sensitivity')
    })

    it('shows vega explanation when info icon is clicked', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-vega'))

      const popover = screen.getByTestId('greek-popover-vega')
      expect(popover).toBeInTheDocument()
      expect(popover).toHaveTextContent('Estimated change in portfolio VaR for a 1% move in implied volatility')
    })

    it('closes popover when the same info icon is clicked again', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-delta'))
      expect(screen.getByTestId('greek-popover-delta')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('greek-info-delta'))
      expect(screen.queryByTestId('greek-popover-delta')).not.toBeInTheDocument()
    })

    it('closes popover when Escape is pressed', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-delta'))
      expect(screen.getByTestId('greek-popover-delta')).toBeInTheDocument()

      fireEvent.keyDown(document, { key: 'Escape' })
      expect(screen.queryByTestId('greek-popover-delta')).not.toBeInTheDocument()
    })

    it('shows only one popover at a time', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-delta'))
      expect(screen.getByTestId('greek-popover-delta')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('greek-info-gamma'))
      expect(screen.queryByTestId('greek-popover-delta')).not.toBeInTheDocument()
      expect(screen.getByTestId('greek-popover-gamma')).toBeInTheDocument()
    })

    it('closes popover when clicking outside', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-delta'))
      expect(screen.getByTestId('greek-popover-delta')).toBeInTheDocument()

      fireEvent.mouseDown(document.body)
      expect(screen.queryByTestId('greek-popover-delta')).not.toBeInTheDocument()
    })

    it('closes popover when close button is clicked', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-delta'))
      expect(screen.getByTestId('greek-popover-delta')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('greek-popover-delta-close'))
      expect(screen.queryByTestId('greek-popover-delta')).not.toBeInTheDocument()
    })

    it('shows theta explanation when info icon is clicked', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-theta'))

      const popover = screen.getByTestId('greek-popover-theta')
      expect(popover).toBeInTheDocument()
      expect(popover).toHaveTextContent('Estimated change in portfolio VaR for each day that passes')
    })

    it('shows rho explanation when info icon is clicked', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-rho'))

      const popover = screen.getByTestId('greek-popover-rho')
      expect(popover).toBeInTheDocument()
      expect(popover).toHaveTextContent('Estimated change in portfolio VaR for a 1 basis point move in interest rates')
    })

    it('closes theta/rho popover when a header popover is opened', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      fireEvent.click(screen.getByTestId('greek-info-theta'))
      expect(screen.getByTestId('greek-popover-theta')).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('greek-info-delta'))
      expect(screen.queryByTestId('greek-popover-theta')).not.toBeInTheDocument()
      expect(screen.getByTestId('greek-popover-delta')).toBeInTheDocument()
    })
  })

  describe('aggregate-Greeks inline explainer (plan §9.5)', () => {
    it('renders a card-level Explain button with no panel before it is clicked', () => {
      render(<RiskSensitivities greeksResult={greeksResult} />)

      expect(screen.getByTestId('explain-greeks')).toBeInTheDocument()
      expect(screen.queryByTestId('greeks-explain-panel')).not.toBeInTheDocument()
    })

    it('opens the streaming insight panel when the Explain button is clicked', async () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(<RiskSensitivities greeksResult={greeksResult} chatFn={chatFn} />)

      await userEvent.click(screen.getByTestId('explain-greeks'))

      expect(screen.getByTestId('greeks-explain-panel')).toBeInTheDocument()
      await waitFor(() =>
        expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
          'Net delta is long.',
        ),
      )
    })

    it('attaches the aggregate Greeks figures to the explain page_context', async () => {
      const chatFn = vi.fn<(req: ChatRequest) => ReadableStream<ChatChunk>>(
        () => fakeChatStream(),
      )
      render(<RiskSensitivities greeksResult={greeksResult} chatFn={chatFn} />)

      await userEvent.click(screen.getByTestId('explain-greeks'))

      expect(chatFn).toHaveBeenCalledTimes(1)
      const request = chatFn.mock.calls[0][0]
      const ctx = request.page_context as Record<string, unknown>
      expect(ctx.page).toBe('greeks')
      expect(ctx.book_id).toBe('book-1')

      // delta = 1234.56 + 567.89 = 1802.45; theta/rho are book-level scalars.
      const agg = ctx.aggregate_greeks as Record<string, number>
      expect(agg.delta).toBeCloseTo(1802.45)
      expect(agg.gamma).toBeCloseTo(91.24)
      expect(agg.vega).toBeCloseTo(8023.79)
      expect(agg.theta).toBeCloseTo(-123.45)
      expect(agg.rho).toBeCloseTo(456.78)
    })

    it('a rapid double-click does not fire a duplicate chat request', async () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(<RiskSensitivities greeksResult={greeksResult} chatFn={chatFn} />)

      const button = screen.getByTestId('explain-greeks')
      await userEvent.click(button)
      await userEvent.click(button)

      expect(chatFn).toHaveBeenCalledTimes(1)
      expect(screen.getAllByTestId('greeks-explain-panel')).toHaveLength(1)
    })

    it('the panel close button dismisses the explainer', async () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(<RiskSensitivities greeksResult={greeksResult} chatFn={chatFn} />)

      await userEvent.click(screen.getByTestId('explain-greeks'))
      expect(screen.getByTestId('greeks-explain-panel')).toBeInTheDocument()

      await userEvent.click(screen.getByTestId('ai-insight-close'))
      expect(screen.queryByTestId('greeks-explain-panel')).not.toBeInTheDocument()
    })
  })
})
