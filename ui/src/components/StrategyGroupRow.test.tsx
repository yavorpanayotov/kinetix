import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { PositionDto, StrategyGroupDto } from '../types'
import { StrategyGroupRow } from './StrategyGroupRow'

const makePosition = (overrides: Partial<PositionDto> = {}): PositionDto => ({
  bookId: 'book-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  quantity: '100',
  averageCost: { amount: '150.00', currency: 'USD' },
  marketPrice: { amount: '155.00', currency: 'USD' },
  marketValue: { amount: '15500.00', currency: 'USD' },
  unrealizedPnl: { amount: '500.00', currency: 'USD' },
  strategyId: 'strat-1',
  ...overrides,
})

const makeStrategy = (overrides: Partial<StrategyGroupDto> = {}): StrategyGroupDto => ({
  strategyId: 'strat-1',
  strategyType: 'STRADDLE',
  strategyName: 'Sep Straddle',
  legs: [
    makePosition({ instrumentId: 'AAPL-CALL', unrealizedPnl: { amount: '300.00', currency: 'USD' } }),
    makePosition({ instrumentId: 'AAPL-PUT', unrealizedPnl: { amount: '200.00', currency: 'USD' } }),
  ],
  netDelta: '0.05',
  netGamma: '0.10',
  netVega: '20.0',
  netPnl: '500.00',
  ...overrides,
})

describe('StrategyGroupRow', () => {
  it('renders strategy name and type badge in the parent row', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy()} colSpan={9} />
        </tbody>
      </table>,
    )

    expect(screen.getByTestId('strategy-row-strat-1')).toBeInTheDocument()
    expect(screen.getByText('Sep Straddle')).toBeInTheDocument()
    expect(screen.getByTestId('strategy-type-badge-strat-1')).toHaveTextContent('STRADDLE')
  })

  it('shows net delta, net gamma, net vega in the parent row', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy()} colSpan={9} />
        </tbody>
      </table>,
    )

    expect(screen.getByTestId('strategy-net-delta-strat-1')).toHaveTextContent('0.05')
    expect(screen.getByTestId('strategy-net-gamma-strat-1')).toHaveTextContent('0.10')
    expect(screen.getByTestId('strategy-net-vega-strat-1')).toHaveTextContent('20')
  })

  it('shows net P&L in the parent row', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy()} colSpan={9} />
        </tbody>
      </table>,
    )

    expect(screen.getByTestId('strategy-net-pnl-strat-1')).toBeInTheDocument()
  })

  it('prefixes a + on positive net P&L', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy({ netPnl: '500.00' })} colSpan={9} />
        </tbody>
      </table>,
    )

    expect(screen.getByTestId('strategy-net-pnl-strat-1').textContent).toBe('+500.00')
  })

  it('does not prefix a + on negative net P&L', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy({ netPnl: '-250.00' })} colSpan={9} />
        </tbody>
      </table>,
    )

    expect(screen.getByTestId('strategy-net-pnl-strat-1').textContent).toBe('-250.00')
  })

  it('does not prefix net Greeks (delta/gamma/vega) with a sign', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow
            strategy={makeStrategy({ netDelta: '0.05', netGamma: '0.10', netVega: '20.0' })}
            colSpan={9}
          />
        </tbody>
      </table>,
    )

    // Greeks are not P&L; they keep formatNum semantics with no + prefix.
    expect(screen.getByTestId('strategy-net-delta-strat-1').textContent).toBe('0.05')
    expect(screen.getByTestId('strategy-net-gamma-strat-1').textContent).toBe('0.10')
    expect(screen.getByTestId('strategy-net-vega-strat-1').textContent).toBe('20.00')
  })

  it('leg rows are hidden initially (collapsed by default)', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy()} colSpan={9} />
        </tbody>
      </table>,
    )

    expect(screen.queryByTestId('strategy-leg-AAPL-CALL')).not.toBeInTheDocument()
    expect(screen.queryByTestId('strategy-leg-AAPL-PUT')).not.toBeInTheDocument()
  })

  it('clicking the strategy row expands to show leg rows', async () => {
    const user = userEvent.setup()
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy()} colSpan={9} />
        </tbody>
      </table>,
    )

    await user.click(screen.getByTestId('strategy-row-strat-1'))

    expect(screen.getByTestId('strategy-leg-AAPL-CALL')).toBeInTheDocument()
    expect(screen.getByTestId('strategy-leg-AAPL-PUT')).toBeInTheDocument()
  })

  it('clicking the strategy row a second time collapses the legs', async () => {
    const user = userEvent.setup()
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy()} colSpan={9} />
        </tbody>
      </table>,
    )

    await user.click(screen.getByTestId('strategy-row-strat-1'))
    expect(screen.getByTestId('strategy-leg-AAPL-CALL')).toBeInTheDocument()

    await user.click(screen.getByTestId('strategy-row-strat-1'))
    expect(screen.queryByTestId('strategy-leg-AAPL-CALL')).not.toBeInTheDocument()
  })

  it('each leg row shows the instrument id', async () => {
    const user = userEvent.setup()
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy()} colSpan={9} />
        </tbody>
      </table>,
    )

    await user.click(screen.getByTestId('strategy-row-strat-1'))

    const callLeg = screen.getByTestId('strategy-leg-AAPL-CALL')
    expect(within(callLeg).getByText('AAPL-CALL')).toBeInTheDocument()
  })

  it('renders strategy with no name using strategy type as fallback', () => {
    const strategy = makeStrategy({ strategyName: undefined })
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={strategy} colSpan={9} />
        </tbody>
      </table>,
    )

    // Should show the type badge but no crash
    expect(screen.getByTestId('strategy-type-badge-strat-1')).toHaveTextContent('STRADDLE')
  })

  it('shows dash when netDelta is null', () => {
    render(
      <table>
        <tbody>
          <StrategyGroupRow strategy={makeStrategy({ netDelta: null })} colSpan={9} />
        </tbody>
      </table>,
    )

    expect(screen.getByTestId('strategy-net-delta-strat-1')).toHaveTextContent('\u2014')
  })
})
