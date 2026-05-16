import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { WhatIfResponseDto, WhatIfImpactDto, RebalancingResponseDto } from '../types'
import type { TradeFormEntry } from '../hooks/useWhatIf'
import { WhatIfPanel } from './WhatIfPanel'

const emptyTrade: TradeFormEntry = {
  instrumentId: '',
  instrumentType: 'CASH_EQUITY',
  assetClass: 'EQUITY',
  side: 'BUY',
  quantity: '',
  priceAmount: '',
  priceCurrency: 'USD',
}

const whatIfResponse: WhatIfResponseDto = {
  baseVaR: '100000.00',
  baseExpectedShortfall: '130000.00',
  baseGreeks: {
    bookId: 'book-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '50000.00', gamma: '1200.00', vega: '8000.00' },
    ],
    theta: '-500.00',
    rho: '200.00',
    calculatedAt: '2025-01-15T10:00:00Z',
  },
  basePositionRisk: [],
  hypotheticalVaR: '85000.00',
  hypotheticalExpectedShortfall: '110000.00',
  hypotheticalGreeks: {
    bookId: 'book-1',
    assetClassGreeks: [
      { assetClass: 'EQUITY', delta: '45000.00', gamma: '1100.00', vega: '7500.00' },
    ],
    theta: '-450.00',
    rho: '180.00',
    calculatedAt: '2025-01-15T10:00:00Z',
  },
  hypotheticalPositionRisk: [],
  varChange: '-15000.00',
  esChange: '-20000.00',
  calculatedAt: '2025-01-15T10:00:00Z',
}

const impact: WhatIfImpactDto = {
  varChange: '-15000.00',
  esChange: '-20000.00',
  deltaChange: -5000,
  gammaChange: -100,
  vegaChange: -500,
  thetaChange: 50,
  rhoChange: -20,
}

const defaultProps = {
  open: true,
  onClose: vi.fn(),
  trades: [emptyTrade],
  onAddTrade: vi.fn(),
  onRemoveTrade: vi.fn(),
  onUpdateTrade: vi.fn(),
  onSubmit: vi.fn(),
  onReset: vi.fn(),
  result: null as WhatIfResponseDto | null,
  impact: null as WhatIfImpactDto | null,
  loading: false,
  error: null as string | null,
}

describe('WhatIfPanel', () => {
  it('renders the trade form with all inputs', () => {
    render(<WhatIfPanel {...defaultProps} />)

    expect(screen.getByTestId('whatif-panel')).toBeInTheDocument()
    expect(screen.getByTestId('whatif-instrument-0')).toBeInTheDocument()
    expect(screen.getByTestId('whatif-side-0')).toBeInTheDocument()
    expect(screen.getByTestId('whatif-quantity-0')).toBeInTheDocument()
    expect(screen.getByTestId('whatif-price-0')).toBeInTheDocument()
  })

  it('is hidden when closed', () => {
    render(<WhatIfPanel {...defaultProps} open={false} />)

    expect(screen.getByTestId('whatif-panel')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByTestId('whatif-panel')).toHaveClass('translate-x-full')
  })

  it('renders before/after comparison when results are available', () => {
    render(
      <WhatIfPanel
        {...defaultProps}
        result={whatIfResponse}
        impact={impact}
      />,
    )

    expect(screen.getByTestId('whatif-comparison')).toBeInTheDocument()
    expect(screen.getByTestId('whatif-var-base')).toHaveTextContent('100,000.00')
    expect(screen.getByTestId('whatif-var-after')).toHaveTextContent('85,000.00')
    expect(screen.getByTestId('whatif-var-change')).toBeInTheDocument()
  })

  it('shows VaR reduction in green', () => {
    render(
      <WhatIfPanel
        {...defaultProps}
        result={whatIfResponse}
        impact={impact}
      />,
    )

    const varChange = screen.getByTestId('whatif-var-change')
    expect(varChange.className).toContain('text-green-600')
  })

  it('shows VaR increase in red', () => {
    const increaseResponse: WhatIfResponseDto = {
      ...whatIfResponse,
      hypotheticalVaR: '120000.00',
      varChange: '20000.00',
    }
    const increaseImpact: WhatIfImpactDto = {
      ...impact,
      varChange: '20000.00',
    }

    render(
      <WhatIfPanel
        {...defaultProps}
        result={increaseResponse}
        impact={increaseImpact}
      />,
    )

    const varChange = screen.getByTestId('whatif-var-change')
    expect(varChange.className).toContain('text-red-600')
  })

  it('calls onAddTrade when add button is clicked', () => {
    const onAddTrade = vi.fn()
    render(<WhatIfPanel {...defaultProps} onAddTrade={onAddTrade} />)

    fireEvent.click(screen.getByTestId('whatif-add-trade'))

    expect(onAddTrade).toHaveBeenCalled()
  })

  it('calls onRemoveTrade when remove button is clicked for a trade', () => {
    const onRemoveTrade = vi.fn()
    const trades = [emptyTrade, { ...emptyTrade, instrumentId: 'MSFT' }]

    render(
      <WhatIfPanel
        {...defaultProps}
        trades={trades}
        onRemoveTrade={onRemoveTrade}
      />,
    )

    fireEvent.click(screen.getByTestId('whatif-remove-trade-1'))

    expect(onRemoveTrade).toHaveBeenCalledWith(1)
  })

  it('renders multiple trade forms when multiple trades exist', () => {
    const trades = [emptyTrade, { ...emptyTrade, instrumentId: 'MSFT' }]

    render(<WhatIfPanel {...defaultProps} trades={trades} />)

    expect(screen.getByTestId('whatif-instrument-0')).toBeInTheDocument()
    expect(screen.getByTestId('whatif-instrument-1')).toBeInTheDocument()
  })

  it('calls onUpdateTrade when input changes', () => {
    const onUpdateTrade = vi.fn()
    render(<WhatIfPanel {...defaultProps} onUpdateTrade={onUpdateTrade} />)

    fireEvent.change(screen.getByTestId('whatif-instrument-0'), {
      target: { value: 'SPY' },
    })

    expect(onUpdateTrade).toHaveBeenCalledWith(0, 'instrumentId', 'SPY')
  })

  it('calls onSubmit when run button is clicked', () => {
    const onSubmit = vi.fn()
    render(<WhatIfPanel {...defaultProps} onSubmit={onSubmit} />)

    fireEvent.click(screen.getByTestId('whatif-run'))

    expect(onSubmit).toHaveBeenCalled()
  })

  it('shows loading state during submission', () => {
    render(<WhatIfPanel {...defaultProps} loading={true} />)

    expect(screen.getByTestId('whatif-run')).toBeDisabled()
  })

  it('shows error message when error occurs', () => {
    render(<WhatIfPanel {...defaultProps} error="Something went wrong" />)

    expect(screen.getByTestId('whatif-error')).toHaveTextContent('Something went wrong')
  })

  it('calls onClose when close button is clicked', () => {
    const onClose = vi.fn()
    render(<WhatIfPanel {...defaultProps} onClose={onClose} />)

    fireEvent.click(screen.getByTestId('whatif-close'))

    expect(onClose).toHaveBeenCalled()
  })

  it('calls onClose when Escape key is pressed', () => {
    const onClose = vi.fn()
    render(<WhatIfPanel {...defaultProps} onClose={onClose} />)

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(onClose).toHaveBeenCalled()
  })

  it('shows ES in the comparison table', () => {
    render(
      <WhatIfPanel
        {...defaultProps}
        result={whatIfResponse}
        impact={impact}
      />,
    )

    expect(screen.getByTestId('whatif-es-base')).toHaveTextContent('130,000.00')
    expect(screen.getByTestId('whatif-es-after')).toHaveTextContent('110,000.00')
  })

  it('renders Theta row in comparison table', () => {
    render(
      <WhatIfPanel
        {...defaultProps}
        result={whatIfResponse}
        impact={impact}
      />,
    )

    expect(screen.getByTestId('whatif-theta-base')).toHaveTextContent('-500.00')
    expect(screen.getByTestId('whatif-theta-after')).toHaveTextContent('-450.00')
    expect(screen.getByTestId('whatif-theta-change')).toBeInTheDocument()
  })

  it('renders Rho row in comparison table', () => {
    render(
      <WhatIfPanel
        {...defaultProps}
        result={whatIfResponse}
        impact={impact}
      />,
    )

    expect(screen.getByTestId('whatif-rho-base')).toHaveTextContent('200.00')
    expect(screen.getByTestId('whatif-rho-after')).toHaveTextContent('180.00')
    expect(screen.getByTestId('whatif-rho-change')).toBeInTheDocument()
  })

  it('shows notional value per trade leg', () => {
    const trades = [{
      ...emptyTrade,
      instrumentId: 'SPY',
      quantity: '100',
      priceAmount: '450.00',
    }]

    render(<WhatIfPanel {...defaultProps} trades={trades} />)

    expect(screen.getByTestId('whatif-notional-0')).toHaveTextContent('45,000.00')
  })

  it('renders position risk breakdown when results have position risk data', () => {
    const responseWithPositionRisk: WhatIfResponseDto = {
      ...whatIfResponse,
      basePositionRisk: [
        {
          instrumentId: 'AAPL',
          assetClass: 'EQUITY',
          marketValue: '17000.00',
          delta: '0.850000',
          gamma: '0.020000',
          vega: '1500.000000',
          theta: null,
          rho: null,
          varContribution: '5000.00',
          esContribution: '6250.00',
          percentageOfTotal: '100.00',
        },
      ],
      hypotheticalPositionRisk: [
        {
          instrumentId: 'AAPL',
          assetClass: 'EQUITY',
          marketValue: '20000.00',
          delta: '1.000000',
          gamma: '0.025000',
          vega: '1800.000000',
          theta: null,
          rho: null,
          varContribution: '7000.00',
          esContribution: '8750.00',
          percentageOfTotal: '100.00',
        },
      ],
    }

    render(
      <WhatIfPanel
        {...defaultProps}
        result={responseWithPositionRisk}
        impact={impact}
      />,
    )

    expect(screen.getByTestId('whatif-position-breakdown')).toBeInTheDocument()
    expect(screen.getByText('AAPL')).toBeInTheDocument()
  })

  it('calls onReset when reset button is clicked', () => {
    const onReset = vi.fn()
    render(
      <WhatIfPanel
        {...defaultProps}
        result={whatIfResponse}
        impact={impact}
        onReset={onReset}
      />,
    )

    fireEvent.click(screen.getByTestId('whatif-reset'))

    expect(onReset).toHaveBeenCalled()
  })

  it('has role="dialog" and aria-modal on root panel', () => {
    render(<WhatIfPanel {...defaultProps} />)

    const panel = screen.getByTestId('whatif-panel')
    expect(panel).toHaveAttribute('role', 'dialog')
    expect(panel).toHaveAttribute('aria-modal', 'true')
  })

  it('has aria-labelledby pointing to the title', () => {
    render(<WhatIfPanel {...defaultProps} />)

    const panel = screen.getByTestId('whatif-panel')
    expect(panel).toHaveAttribute('aria-labelledby', 'whatif-title')
    expect(screen.getByText('What-If Analysis')).toHaveAttribute('id', 'whatif-title')
  })

  it('has aria-label on close button', () => {
    render(<WhatIfPanel {...defaultProps} />)

    expect(screen.getByTestId('whatif-close')).toHaveAttribute('aria-label', 'Close what-if panel')
  })

  it('has aria-pressed on Buy/Sell buttons', () => {
    render(<WhatIfPanel {...defaultProps} />)

    expect(screen.getByTestId('whatif-side-buy-0')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('whatif-side-sell-0')).toHaveAttribute('aria-pressed', 'false')
  })

  it('wraps results in aria-live polite region', () => {
    render(
      <WhatIfPanel
        {...defaultProps}
        result={whatIfResponse}
        impact={impact}
      />,
    )

    const liveRegion = screen.getByTestId('whatif-results-live')
    expect(liveRegion).toHaveAttribute('aria-live', 'polite')
  })

  it('renders a backdrop overlay that calls onClose', () => {
    const onClose = vi.fn()
    render(<WhatIfPanel {...defaultProps} onClose={onClose} />)

    const backdrop = screen.getByTestId('whatif-backdrop')
    fireEvent.click(backdrop)
    expect(onClose).toHaveBeenCalled()
  })

  it('applies dark mode classes to panel', () => {
    render(<WhatIfPanel {...defaultProps} />)

    const panel = screen.getByTestId('whatif-panel')
    expect(panel.className).toContain('dark:bg-surface-800')
  })

  it('has aria-label on remove trade button', () => {
    const trades = [emptyTrade, { ...emptyTrade, instrumentId: 'MSFT' }]
    render(<WhatIfPanel {...defaultProps} trades={trades} />)

    expect(screen.getByTestId('whatif-remove-trade-0')).toHaveAttribute('aria-label', 'Remove trade 1')
  })

  it('has aria-label on add trade button', () => {
    render(<WhatIfPanel {...defaultProps} />)

    expect(screen.getByTestId('whatif-add-trade')).toHaveAttribute('aria-label', 'Add another hypothetical trade')
  })

  describe('rebalancing mode', () => {
    const rebalancingResult: RebalancingResponseDto = {
      baseVar: '100000.00',
      rebalancedVar: '80000.00',
      varChange: '-20000.00',
      varChangePct: '-20.00',
      baseExpectedShortfall: '130000.00',
      rebalancedExpectedShortfall: '104000.00',
      esChange: '-26000.00',
      baseGreeks: null,
      rebalancedGreeks: null,
      greeksChange: {
        deltaChange: '-5000.000000',
        gammaChange: '-100.000000',
        vegaChange: '-500.000000',
        thetaChange: '50.000000',
        rhoChange: '-20.000000',
      },
      tradeContributions: [
        {
          instrumentId: 'AAPL',
          side: 'SELL',
          quantity: '50',
          marginalVarImpact: '-12000.00',
          executionCost: '42.50',
        },
        {
          instrumentId: 'GOOGL',
          side: 'SELL',
          quantity: '10',
          marginalVarImpact: '-8000.00',
          executionCost: '28.00',
        },
      ],
      estimatedExecutionCost: '70.50',
      calculatedAt: '2026-03-25T10:00:00Z',
    }

    const rebalancingProps = {
      ...defaultProps,
      rebalancingResult,
    }

    function renderInRebalancingMode(extraProps = {}) {
      render(<WhatIfPanel {...rebalancingProps} {...extraProps} />)
      fireEvent.click(screen.getByTestId('whatif-mode-rebalancing'))
    }

    it('renders bid-ask spread input per trade when in rebalancing mode', () => {
      renderInRebalancingMode()

      expect(screen.getByTestId('whatif-bid-ask-0')).toBeInTheDocument()
    })

    it('renders rebalancing comparison table when rebalancing result is available', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      expect(screen.getByTestId('whatif-rebalancing-comparison')).toBeInTheDocument()
    })

    it('shows base VaR and rebalanced VaR in rebalancing comparison', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      expect(screen.getByTestId('whatif-rebal-var-base')).toHaveTextContent('100,000.00')
      expect(screen.getByTestId('whatif-rebal-var-after')).toHaveTextContent('80,000.00')
    })

    it('shows VaR change percentage in rebalancing comparison', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      expect(screen.getByTestId('whatif-rebal-var-change-pct')).toHaveTextContent('-20.00%')
    })

    it('shows estimated execution cost summary', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      expect(screen.getByTestId('whatif-execution-cost')).toHaveTextContent('70.50')
    })

    it('renders per-trade marginal contribution table', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      expect(screen.getByTestId('whatif-trade-contributions')).toBeInTheDocument()
    })

    it('shows each trade contribution row with instrument and marginal VaR impact', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      expect(screen.getByTestId('whatif-contribution-instrument-0')).toHaveTextContent('AAPL')
      expect(screen.getByTestId('whatif-contribution-var-impact-0')).toHaveTextContent('-12,000.00')
      expect(screen.getByTestId('whatif-contribution-instrument-1')).toHaveTextContent('GOOGL')
      expect(screen.getByTestId('whatif-contribution-var-impact-1')).toHaveTextContent('-8,000.00')
    })

    it('shows per-trade execution cost in contribution table', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      expect(screen.getByTestId('whatif-contribution-cost-0')).toHaveTextContent('42.50')
      expect(screen.getByTestId('whatif-contribution-cost-1')).toHaveTextContent('28.00')
    })

    it('renders preset rebalancing template buttons', () => {
      renderInRebalancingMode()

      expect(screen.getByTestId('whatif-preset-reduce-largest')).toBeInTheDocument()
      expect(screen.getByTestId('whatif-preset-flatten-delta')).toBeInTheDocument()
      expect(screen.getByTestId('whatif-preset-roll-expiring')).toBeInTheDocument()
    })

    it('calls onApplyPreset with preset name when preset button is clicked', () => {
      const onApplyPreset = vi.fn()
      renderInRebalancingMode({ onApplyPreset })

      fireEvent.click(screen.getByTestId('whatif-preset-reduce-largest'))

      expect(onApplyPreset).toHaveBeenCalledWith('REDUCE_LARGEST')
    })

    it('calls onApplyPreset with FLATTEN_DELTA when that preset is clicked', () => {
      const onApplyPreset = vi.fn()
      renderInRebalancingMode({ onApplyPreset })

      fireEvent.click(screen.getByTestId('whatif-preset-flatten-delta'))

      expect(onApplyPreset).toHaveBeenCalledWith('FLATTEN_DELTA')
    })

    it('calls onApplyPreset with ROLL_EXPIRING when that preset is clicked', () => {
      const onApplyPreset = vi.fn()
      renderInRebalancingMode({ onApplyPreset })

      fireEvent.click(screen.getByTestId('whatif-preset-roll-expiring'))

      expect(onApplyPreset).toHaveBeenCalledWith('ROLL_EXPIRING')
    })

    it('shows VaR reduction in green in rebalancing comparison', () => {
      render(<WhatIfPanel {...rebalancingProps} />)

      const change = screen.getByTestId('whatif-rebal-var-change')
      expect(change.className).toContain('text-green-600')
    })
  })

  describe('instrument type selector', () => {
    it('renders instrument type dropdown instead of asset class dropdown', () => {
      render(<WhatIfPanel {...defaultProps} />)

      expect(screen.getByTestId('whatif-instrument-type-0')).toBeInTheDocument()
      expect(screen.queryByTestId('whatif-asset-class-0')).not.toBeInTheDocument()
    })

    it('has exactly 13 instrument type options', () => {
      render(<WhatIfPanel {...defaultProps} />)

      const select = screen.getByTestId('whatif-instrument-type-0') as HTMLSelectElement
      expect(select.options.length).toBe(13)
    })

    it('defaults to CASH_EQUITY selection', () => {
      render(<WhatIfPanel {...defaultProps} />)

      const select = screen.getByTestId('whatif-instrument-type-0') as HTMLSelectElement
      expect(select.value).toBe('CASH_EQUITY')
    })

    it('calls onUpdateTrade with mapped assetClass when instrument type changes', () => {
      const onUpdateTrade = vi.fn()
      render(<WhatIfPanel {...defaultProps} onUpdateTrade={onUpdateTrade} />)

      fireEvent.change(screen.getByTestId('whatif-instrument-type-0'), {
        target: { value: 'GOVERNMENT_BOND' },
      })

      // Should call with assetClass = FIXED_INCOME (the mapped value)
      expect(onUpdateTrade).toHaveBeenCalledWith(0, 'assetClass', 'FIXED_INCOME')
    })

    it('maps CASH_EQUITY to EQUITY asset class', () => {
      const onUpdateTrade = vi.fn()
      render(<WhatIfPanel {...defaultProps} onUpdateTrade={onUpdateTrade} />)

      fireEvent.change(screen.getByTestId('whatif-instrument-type-0'), {
        target: { value: 'CASH_EQUITY' },
      })

      expect(onUpdateTrade).toHaveBeenCalledWith(0, 'assetClass', 'EQUITY')
    })

    it('maps COMMODITY_FUTURE to COMMODITY asset class', () => {
      const onUpdateTrade = vi.fn()
      render(<WhatIfPanel {...defaultProps} onUpdateTrade={onUpdateTrade} />)

      fireEvent.change(screen.getByTestId('whatif-instrument-type-0'), {
        target: { value: 'COMMODITY_FUTURE' },
      })

      expect(onUpdateTrade).toHaveBeenCalledWith(0, 'assetClass', 'COMMODITY')
    })

    it('maps FX_SPOT to FX asset class', () => {
      const onUpdateTrade = vi.fn()
      render(<WhatIfPanel {...defaultProps} onUpdateTrade={onUpdateTrade} />)

      fireEvent.change(screen.getByTestId('whatif-instrument-type-0'), {
        target: { value: 'FX_SPOT' },
      })

      expect(onUpdateTrade).toHaveBeenCalledWith(0, 'assetClass', 'FX')
    })
  })

  describe('focus trap (WCAG 2.1.2)', () => {
    function getFocusable(panel: HTMLElement): HTMLElement[] {
      return Array.from(
        panel.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => !el.hasAttribute('aria-hidden') || el.getAttribute('aria-hidden') === 'false')
    }

    it('moves focus into the panel when it opens', async () => {
      // Render with open=false then re-render with open=true to exercise the open transition.
      const { rerender } = render(<WhatIfPanel {...defaultProps} open={false} />)
      rerender(<WhatIfPanel {...defaultProps} open={true} />)

      await waitFor(() => {
        expect(screen.getByTestId('whatif-instrument-0')).toHaveFocus()
      })
    })

    it('cycles focus from the last focusable to the first on Tab', async () => {
      render(<WhatIfPanel {...defaultProps} />)
      const panel = screen.getByTestId('whatif-panel')

      const focusables = getFocusable(panel)
      expect(focusables.length).toBeGreaterThan(1)

      const first = focusables[0]
      const last = focusables[focusables.length - 1]

      last.focus()
      expect(last).toHaveFocus()

      fireEvent.keyDown(panel, { key: 'Tab' })

      expect(first).toHaveFocus()
    })

    it('cycles focus from the first focusable back to the last on Shift+Tab', async () => {
      render(<WhatIfPanel {...defaultProps} />)
      const panel = screen.getByTestId('whatif-panel')

      const focusables = getFocusable(panel)
      expect(focusables.length).toBeGreaterThan(1)

      const first = focusables[0]
      const last = focusables[focusables.length - 1]

      first.focus()
      expect(first).toHaveFocus()

      fireEvent.keyDown(panel, { key: 'Tab', shiftKey: true })

      expect(last).toHaveFocus()
    })

    it('does not interfere with Tab when focus is in the middle of the panel', async () => {
      render(<WhatIfPanel {...defaultProps} />)
      const panel = screen.getByTestId('whatif-panel')

      const focusables = getFocusable(panel)
      expect(focusables.length).toBeGreaterThan(2)

      // Focus a middle element.
      const middle = focusables[Math.floor(focusables.length / 2)]
      middle.focus()
      expect(middle).toHaveFocus()

      // Plain Tab in the middle should not move focus (the browser handles it);
      // critically, it must not jump to first or last via our trap.
      const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
      const prevented = !panel.dispatchEvent(event)
      // We expect default NOT to have been prevented for mid-panel tabs.
      expect(prevented).toBe(false)
      // Focus should still be on middle (jsdom doesn't auto-advance focus on Tab).
      expect(middle).toHaveFocus()
    })

    it('restores focus to the previously-focused element when the panel closes', async () => {
      // Mount an opener button outside the panel, focus it, then open the panel.
      const Wrapper = ({ open }: { open: boolean }) => (
        <>
          <button data-testid="opener">Open</button>
          <WhatIfPanel {...defaultProps} open={open} />
        </>
      )

      const { rerender } = render(<Wrapper open={false} />)
      const opener = screen.getByTestId('opener')
      opener.focus()
      expect(opener).toHaveFocus()

      // Open the panel — focus moves to first input.
      rerender(<Wrapper open={true} />)
      await waitFor(() => {
        expect(screen.getByTestId('whatif-instrument-0')).toHaveFocus()
      })

      // Close the panel — focus should return to the opener.
      rerender(<Wrapper open={false} />)
      await waitFor(() => {
        expect(opener).toHaveFocus()
      })
    })
  })
})
