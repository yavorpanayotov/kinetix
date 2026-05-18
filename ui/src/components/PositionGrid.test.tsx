import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { PositionDto, PositionRiskDto } from '../types'
import { PositionGrid } from './PositionGrid'

const makePosition = (overrides: Partial<PositionDto> = {}): PositionDto => ({
  bookId: 'book-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  quantity: '100',
  averageCost: { amount: '150.00', currency: 'USD' },
  marketPrice: { amount: '155.00', currency: 'USD' },
  marketValue: { amount: '15500.00', currency: 'USD' },
  unrealizedPnl: { amount: '500.00', currency: 'USD' },
  ...overrides,
})

describe('PositionGrid', () => {
  it('renders risk-first default headers (no quantity/avg cost/market price by default)', () => {
    render(<PositionGrid positions={[makePosition()]} />)

    // Default risk-first columns
    const visibleHeaders = [
      'Instrument',
      'Asset Class',
      'Market Value',
      'Unrealized P&L',
    ]
    visibleHeaders.forEach((header) => {
      expect(
        screen.getByRole('columnheader', { name: header }),
      ).toBeInTheDocument()
    })

    // Detail columns are hidden by default
    const hiddenHeaders = ['Quantity', 'Avg Cost', 'Market Price']
    hiddenHeaders.forEach((header) => {
      expect(
        screen.queryByRole('columnheader', { name: header }),
      ).not.toBeInTheDocument()
    })
  })

  it('renders position row data', () => {
    render(<PositionGrid positions={[makePosition()]} />)

    const row = screen.getByTestId('position-row-AAPL')
    expect(within(row).getByText('AAPL')).toBeInTheDocument()
    expect(within(row).getByText('EQUITY')).toBeInTheDocument()
  })

  it('formats money values correctly', () => {
    render(<PositionGrid positions={[makePosition()]} />)

    const row = screen.getByTestId('position-row-AAPL')
    // Market Value and Unrealized P&L are visible by default
    expect(within(row).getByText('$15,500.00')).toBeInTheDocument()
    expect(within(row).getByText('+$500.00')).toBeInTheDocument()
  })

  it('applies green color to positive P&L', () => {
    render(
      <PositionGrid
        positions={[
          makePosition({
            unrealizedPnl: { amount: '500.00', currency: 'USD' },
          }),
        ]}
      />,
    )

    const pnlCell = screen.getByTestId('pnl-AAPL')
    expect(pnlCell).toHaveClass('text-green-600')
  })

  it('applies red color to negative P&L', () => {
    render(
      <PositionGrid
        positions={[
          makePosition({
            instrumentId: 'TSLA',
            unrealizedPnl: { amount: '-200.00', currency: 'USD' },
          }),
        ]}
      />,
    )

    const pnlCell = screen.getByTestId('pnl-TSLA')
    expect(pnlCell).toHaveClass('text-red-600')
  })

  it('renders empty state when no positions', () => {
    render(<PositionGrid positions={[]} />)

    expect(screen.getByText('No positions to display.')).toBeInTheDocument()
  })

  it('shows Live status when connected', () => {
    render(<PositionGrid positions={[makePosition()]} connected={true} />)

    const status = screen.getByTestId('connection-status')
    expect(status).toHaveTextContent('Live')
  })

  it('shows Disconnected status when not connected', () => {
    render(<PositionGrid positions={[makePosition()]} connected={false} />)

    const status = screen.getByTestId('connection-status')
    expect(status).toHaveTextContent('Disconnected')
  })

  it('renders multiple positions', () => {
    const positions = [
      makePosition({ instrumentId: 'AAPL' }),
      makePosition({ instrumentId: 'GOOGL', assetClass: 'EQUITY' }),
    ]
    render(<PositionGrid positions={positions} />)

    expect(screen.getByTestId('position-row-AAPL')).toBeInTheDocument()
    expect(screen.getByTestId('position-row-GOOGL')).toBeInTheDocument()
  })

  it('renders portfolio summary bar with totals', () => {
    const positions = [
      makePosition({
        instrumentId: 'AAPL',
        marketValue: { amount: '15500.00', currency: 'USD' },
        unrealizedPnl: { amount: '500.00', currency: 'USD' },
      }),
      makePosition({
        instrumentId: 'GOOGL',
        marketValue: { amount: '10000.00', currency: 'USD' },
        unrealizedPnl: { amount: '-200.00', currency: 'USD' },
      }),
    ]
    render(<PositionGrid positions={positions} />)

    const summary = screen.getByTestId('book-summary')
    expect(summary).toBeInTheDocument()
    expect(within(summary).getByText('2')).toBeInTheDocument()
    expect(within(summary).getByText('$25.5K')).toBeInTheDocument()
    expect(within(summary).getByText('$300')).toBeInTheDocument()
  })

  it('formats quantity values cleanly when Details toggle is on', async () => {
    const user = userEvent.setup()
    render(
      <PositionGrid
        positions={[makePosition({ quantity: '150.000000000000' })]}
      />,
    )

    // Reveal Details columns first
    await user.click(screen.getByTestId('position-details-toggle'))

    const row = screen.getByTestId('position-row-AAPL')
    expect(within(row).getByText('150')).toBeInTheDocument()
  })

  describe('with position risk data', () => {
    const makeRisk = (overrides: Partial<PositionRiskDto> = {}): PositionRiskDto => ({
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      marketValue: '15500.00',
      delta: '1234.56',
      gamma: '45.67',
      vega: '89.01',
      theta: null,
      rho: null,
      varContribution: '800.00',
      esContribution: '1000.00',
      percentageOfTotal: '64.85',
      ...overrides,
    })

    it('renders risk metric column headers when positionRisk is provided', () => {
      render(
        <PositionGrid
          positions={[makePosition()]}
          positionRisk={[makeRisk()]}
        />,
      )

      expect(screen.getByRole('columnheader', { name: 'Delta' })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: 'Gamma' })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: 'Vega' })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: 'VaR Contrib %' })).toBeInTheDocument()
    })

    it('does not render risk columns when positionRisk is absent', () => {
      render(<PositionGrid positions={[makePosition()]} />)

      expect(screen.queryByRole('columnheader', { name: 'Delta' })).not.toBeInTheDocument()
      expect(screen.queryByRole('columnheader', { name: 'Gamma' })).not.toBeInTheDocument()
      expect(screen.queryByRole('columnheader', { name: 'Vega' })).not.toBeInTheDocument()
      expect(screen.queryByRole('columnheader', { name: 'VaR Contrib %' })).not.toBeInTheDocument()
    })

    it('renders risk values in the row matched by instrumentId', () => {
      render(
        <PositionGrid
          positions={[makePosition()]}
          positionRisk={[makeRisk()]}
        />,
      )

      const row = screen.getByTestId('position-row-AAPL')
      expect(within(row).getByTestId('delta-AAPL')).toHaveTextContent('1,234.56')
      expect(within(row).getByTestId('gamma-AAPL')).toHaveTextContent('45.67')
      expect(within(row).getByTestId('vega-AAPL')).toHaveTextContent('89.01')
      expect(within(row).getByTestId('var-pct-AAPL')).toHaveTextContent('64.85%')
    })

    it('shows N/A when risk data is missing for a position', () => {
      render(
        <PositionGrid
          positions={[
            makePosition({ instrumentId: 'AAPL' }),
            makePosition({ instrumentId: 'GOOGL' }),
          ]}
          positionRisk={[makeRisk({ instrumentId: 'AAPL' })]}
        />,
      )

      const googlRow = screen.getByTestId('position-row-GOOGL')
      expect(within(googlRow).getByTestId('delta-GOOGL')).toHaveTextContent('N/A')
      expect(within(googlRow).getByTestId('gamma-GOOGL')).toHaveTextContent('N/A')
      expect(within(googlRow).getByTestId('vega-GOOGL')).toHaveTextContent('N/A')
      expect(within(googlRow).getByTestId('var-pct-GOOGL')).toHaveTextContent('N/A')
    })

    it('shows N/A when an option fails to converge and greek values are null', () => {
      // Anomaly contract (docs/plans/demo-follow-up.md Gap 8):
      // non-convergence renders literal "N/A", not an em-dash.
      render(
        <PositionGrid
          positions={[makePosition()]}
          positionRisk={[makeRisk({ delta: null, gamma: null, vega: null })]}
        />,
      )

      const row = screen.getByTestId('position-row-AAPL')
      const deltaCell = within(row).getByTestId('delta-AAPL')
      const gammaCell = within(row).getByTestId('gamma-AAPL')
      const vegaCell = within(row).getByTestId('vega-AAPL')

      expect(deltaCell).toHaveTextContent('N/A')
      expect(gammaCell).toHaveTextContent('N/A')
      expect(vegaCell).toHaveTextContent('N/A')
      expect(deltaCell.textContent).not.toContain('\u2014')
      expect(gammaCell.textContent).not.toContain('\u2014')
      expect(vegaCell.textContent).not.toContain('\u2014')
      // VaR contribution should still show as a percentage
      expect(within(row).getByTestId('var-pct-AAPL')).toHaveTextContent('64.85%')
    })

    it('renders grouped header rows for Position Details and Risk Metrics', () => {
      render(
        <PositionGrid
          positions={[makePosition()]}
          positionRisk={[makeRisk()]}
        />,
      )

      expect(screen.getByTestId('header-group-position')).toHaveTextContent('Position Details')
      expect(screen.getByTestId('header-group-risk')).toHaveTextContent('Risk Metrics')
    })

    it('applies indigo tint to risk metrics header group', () => {
      render(
        <PositionGrid
          positions={[makePosition()]}
          positionRisk={[makeRisk()]}
        />,
      )

      expect(screen.getByTestId('header-group-risk')).toHaveClass('bg-indigo-50')
    })

    it('sorts by VaR contribution when header is clicked', async () => {
      const user = userEvent.setup()
      const positions = [
        makePosition({ instrumentId: 'AAPL' }),
        makePosition({ instrumentId: 'MSFT' }),
        makePosition({ instrumentId: 'GOOGL' }),
      ]
      const risk = [
        makeRisk({ instrumentId: 'AAPL', percentageOfTotal: '20.00' }),
        makeRisk({ instrumentId: 'MSFT', percentageOfTotal: '50.00' }),
        makeRisk({ instrumentId: 'GOOGL', percentageOfTotal: '30.00' }),
      ]

      render(<PositionGrid positions={positions} positionRisk={risk} />)

      const varHeader = screen.getByTestId('sort-var-pct')
      await user.click(varHeader)

      // After clicking, should sort descending: MSFT(50) > GOOGL(30) > AAPL(20)
      const rows = screen.getAllByTestId(/^position-row-/)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-row-MSFT')
      expect(rows[1]).toHaveAttribute('data-testid', 'position-row-GOOGL')
      expect(rows[2]).toHaveAttribute('data-testid', 'position-row-AAPL')
    })

    it('toggles sort direction on second click', async () => {
      const user = userEvent.setup()
      const positions = [
        makePosition({ instrumentId: 'AAPL' }),
        makePosition({ instrumentId: 'MSFT' }),
      ]
      const risk = [
        makeRisk({ instrumentId: 'AAPL', percentageOfTotal: '20.00' }),
        makeRisk({ instrumentId: 'MSFT', percentageOfTotal: '50.00' }),
      ]

      render(<PositionGrid positions={positions} positionRisk={risk} />)

      const varHeader = screen.getByTestId('sort-var-pct')

      // First click: descending
      await user.click(varHeader)
      let rows = screen.getAllByTestId(/^position-row-/)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-row-MSFT')

      // Second click: ascending
      await user.click(varHeader)
      rows = screen.getAllByTestId(/^position-row-/)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-row-AAPL')
    })

    it('renders Portfolio Delta and Portfolio VaR summary cards when risk data is provided', () => {
      const positions = [
        makePosition({ instrumentId: 'AAPL' }),
        makePosition({ instrumentId: 'MSFT' }),
      ]
      const risk = [
        makeRisk({ instrumentId: 'AAPL', delta: '1000.00', varContribution: '800.00', percentageOfTotal: '60.00' }),
        makeRisk({ instrumentId: 'MSFT', delta: '500.00', varContribution: '400.00', percentageOfTotal: '40.00' }),
      ]

      render(<PositionGrid positions={positions} positionRisk={risk} />)

      const summary = screen.getByTestId('book-summary')
      expect(within(summary).getByTestId('summary-book-delta')).toBeInTheDocument()
      expect(within(summary).getByTestId('summary-book-var')).toBeInTheDocument()
    })
  })

  describe('pagination', () => {
    const makeManyPositions = (count: number): PositionDto[] =>
      Array.from({ length: count }, (_, i) =>
        makePosition({ instrumentId: `INST-${String(i + 1).padStart(3, '0')}` }),
      )

    it('should display only 50 rows per page by default', () => {
      render(<PositionGrid positions={makeManyPositions(75)} />)

      const rows = screen.getAllByTestId(/^position-row-/)
      expect(rows).toHaveLength(50)
    })

    it('should show page navigation controls', () => {
      render(<PositionGrid positions={makeManyPositions(75)} />)

      expect(screen.getByTestId('pagination-controls')).toBeInTheDocument()
      expect(screen.getByTestId('pagination-prev')).toBeInTheDocument()
      expect(screen.getByTestId('pagination-next')).toBeInTheDocument()
    })

    it('should navigate to next page', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={makeManyPositions(75)} />)

      await user.click(screen.getByTestId('pagination-next'))

      const rows = screen.getAllByTestId(/^position-row-/)
      expect(rows).toHaveLength(25)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-row-INST-051')
    })

    it('should navigate to previous page', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={makeManyPositions(75)} />)

      // Go to page 2
      await user.click(screen.getByTestId('pagination-next'))
      // Go back to page 1
      await user.click(screen.getByTestId('pagination-prev'))

      const rows = screen.getAllByTestId(/^position-row-/)
      expect(rows).toHaveLength(50)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-row-INST-001')
    })

    it('should show current page and total pages', () => {
      render(<PositionGrid positions={makeManyPositions(75)} />)

      expect(screen.getByTestId('pagination-info')).toHaveTextContent('Page 1 of 2')
    })

    it('should disable previous on first page', () => {
      render(<PositionGrid positions={makeManyPositions(75)} />)

      expect(screen.getByTestId('pagination-prev')).toBeDisabled()
    })

    it('should disable next on last page', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={makeManyPositions(75)} />)

      await user.click(screen.getByTestId('pagination-next'))

      expect(screen.getByTestId('pagination-next')).toBeDisabled()
    })

    it('should not show pagination when positions fit on one page', () => {
      render(<PositionGrid positions={makeManyPositions(30)} />)

      expect(screen.queryByTestId('pagination-controls')).not.toBeInTheDocument()
    })
  })

  describe('column visibility toggles', () => {
    beforeEach(() => {
      localStorage.clear()
    })

    afterEach(() => {
      localStorage.clear()
    })

    it('should show settings dropdown button', () => {
      render(<PositionGrid positions={[makePosition()]} />)

      expect(screen.getByTestId('column-settings-button')).toBeInTheDocument()
    })

    it('should toggle column visibility', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={[makePosition()]} />)

      await user.click(screen.getByTestId('column-settings-button'))

      const assetClassToggle = screen.getByTestId('column-toggle-assetClass')
      expect(assetClassToggle).toBeInTheDocument()

      // Uncheck Asset Class column
      await user.click(assetClassToggle)

      // Column header should be hidden
      expect(screen.queryByRole('columnheader', { name: 'Asset Class' })).not.toBeInTheDocument()
    })

    it('should not expose per-column toggles for Details-only columns', async () => {
      // Quantity, Avg Cost, and Market Price are controlled by the Details toggle —
      // they should NOT appear in the per-column settings dropdown.
      const user = userEvent.setup()
      render(<PositionGrid positions={[makePosition()]} />)

      await user.click(screen.getByTestId('column-settings-button'))

      expect(screen.queryByTestId('column-toggle-quantity')).not.toBeInTheDocument()
      expect(screen.queryByTestId('column-toggle-avgCost')).not.toBeInTheDocument()
      expect(screen.queryByTestId('column-toggle-marketPrice')).not.toBeInTheDocument()
    })

    it('should persist preferences in localStorage', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={[makePosition()]} />)

      await user.click(screen.getByTestId('column-settings-button'))
      await user.click(screen.getByTestId('column-toggle-assetClass'))

      const stored = JSON.parse(localStorage.getItem('kinetix:column-visibility') ?? '{}')
      expect(stored.assetClass).toBe(false)
    })

    it('should load preferences from localStorage on mount', () => {
      localStorage.setItem('kinetix:column-visibility', JSON.stringify({ assetClass: false }))

      render(<PositionGrid positions={[makePosition()]} />)

      expect(screen.queryByRole('columnheader', { name: 'Asset Class' })).not.toBeInTheDocument()
    })
  })

  describe('risk-first default columns with Details toggle', () => {
    beforeEach(() => {
      localStorage.clear()
    })

    afterEach(() => {
      localStorage.clear()
    })

    it('default view shows risk-first columns in order: Instrument, MV, UPnL, Δ, Γ, Vega, VaR%', () => {
      const risk: PositionRiskDto = {
        instrumentId: 'AAPL',
        assetClass: 'EQUITY',
        marketValue: '15500.00',
        delta: '1234.56',
        gamma: '45.67',
        vega: '89.01',
        theta: null,
        rho: null,
        varContribution: '800.00',
        esContribution: '1000.00',
        percentageOfTotal: '64.85',
      }
      render(
        <PositionGrid positions={[makePosition()]} positionRisk={[risk]} />,
      )

      // The data-row header (second thead row) should expose these in this order.
      const headerCells = screen.getAllByRole('columnheader')
      const headerNames = headerCells
        .map((h) => h.textContent?.trim() ?? '')
        // Filter out the grouped 'Position Details' / 'Risk Metrics' banner row
        .filter((name) => name !== 'Position Details' && name !== 'Risk Metrics')

      // Locate each expected column relative to the others
      const idx = (label: string) => headerNames.findIndex((h) => h.startsWith(label))
      expect(idx('Instrument')).toBeGreaterThanOrEqual(0)
      expect(idx('Market Value')).toBeGreaterThan(idx('Instrument'))
      expect(idx('Unrealized P&L')).toBeGreaterThan(idx('Market Value'))
      expect(idx('Delta')).toBeGreaterThan(idx('Unrealized P&L'))
      expect(idx('Gamma')).toBeGreaterThan(idx('Delta'))
      expect(idx('Vega')).toBeGreaterThan(idx('Gamma'))
      expect(idx('VaR Contrib')).toBeGreaterThan(idx('Vega'))
    })

    it('default view hides Quantity, Avg Cost, and Market Price', () => {
      render(<PositionGrid positions={[makePosition()]} />)

      expect(screen.queryByRole('columnheader', { name: 'Quantity' })).not.toBeInTheDocument()
      expect(screen.queryByRole('columnheader', { name: 'Avg Cost' })).not.toBeInTheDocument()
      expect(screen.queryByRole('columnheader', { name: 'Market Price' })).not.toBeInTheDocument()
    })

    it('renders a Details toggle button', () => {
      render(<PositionGrid positions={[makePosition()]} />)

      expect(screen.getByTestId('position-details-toggle')).toBeInTheDocument()
    })

    it('clicking Details reveals Quantity, Avg Cost, and Market Price', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={[makePosition()]} />)

      await user.click(screen.getByTestId('position-details-toggle'))

      expect(screen.getByRole('columnheader', { name: 'Quantity' })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: 'Avg Cost' })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: 'Market Price' })).toBeInTheDocument()

      const row = screen.getByTestId('position-row-AAPL')
      expect(within(row).getByText('100')).toBeInTheDocument()
      expect(within(row).getByText('$150.00')).toBeInTheDocument()
      expect(within(row).getByText('$155.00')).toBeInTheDocument()
    })

    it('clicking Details twice toggles columns back off', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={[makePosition()]} />)

      const toggle = screen.getByTestId('position-details-toggle')
      await user.click(toggle)
      expect(screen.getByRole('columnheader', { name: 'Quantity' })).toBeInTheDocument()

      await user.click(toggle)
      expect(screen.queryByRole('columnheader', { name: 'Quantity' })).not.toBeInTheDocument()
      expect(screen.queryByRole('columnheader', { name: 'Avg Cost' })).not.toBeInTheDocument()
      expect(screen.queryByRole('columnheader', { name: 'Market Price' })).not.toBeInTheDocument()
    })

    it('persists Details toggle state via the workspace preference', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={[makePosition()]} />)

      await user.click(screen.getByTestId('position-details-toggle'))

      const saved = JSON.parse(localStorage.getItem('kinetix:workspace') ?? '{}')
      // Plan §2.3: workspace is stored as a saved-views envelope; reach into
      // the active view's prefs.
      const activeView = saved.views?.find((v: { id: string }) => v.id === saved.activeViewId)
      expect(activeView?.prefs.showPositionDetails).toBe(true)
    })

    it('hydrates Details toggle from workspace preference on mount', () => {
      localStorage.setItem(
        'kinetix:workspace',
        JSON.stringify({ showPositionDetails: true }),
      )

      render(<PositionGrid positions={[makePosition()]} />)

      expect(screen.getByRole('columnheader', { name: 'Quantity' })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: 'Avg Cost' })).toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: 'Market Price' })).toBeInTheDocument()
    })
  })

  describe('CSV export', () => {
    it('should have a CSV export button', () => {
      render(<PositionGrid positions={[makePosition()]} />)

      expect(screen.getByTestId('csv-export-button')).toBeInTheDocument()
    })

    it('should trigger download when export button is clicked', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={[makePosition()]} />)

      const exportButton = screen.getByTestId('csv-export-button')
      // Verify the button exists and can be clicked without error
      await user.click(exportButton)
    })
  })

  describe('strategy grouping', () => {
    it('renders a strategy group row for positions with strategyId', () => {
      const positions = [
        makePosition({ instrumentId: 'AAPL-CALL', strategyId: 'strat-1', strategyType: 'STRADDLE', strategyName: 'Sep Straddle' }),
        makePosition({ instrumentId: 'AAPL-PUT', strategyId: 'strat-1', strategyType: 'STRADDLE', strategyName: 'Sep Straddle' }),
      ]

      render(<PositionGrid positions={positions} />)

      expect(screen.getByTestId('strategy-row-strat-1')).toBeInTheDocument()
    })

    it('does not render individual position rows for positions that belong to a strategy', () => {
      const positions = [
        makePosition({ instrumentId: 'AAPL-CALL', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
        makePosition({ instrumentId: 'AAPL-PUT', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
      ]

      render(<PositionGrid positions={positions} />)

      expect(screen.queryByTestId('position-row-AAPL-CALL')).not.toBeInTheDocument()
      expect(screen.queryByTestId('position-row-AAPL-PUT')).not.toBeInTheDocument()
    })

    it('renders ungrouped positions as normal rows alongside strategy group rows', () => {
      const positions = [
        makePosition({ instrumentId: 'AAPL-CALL', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
        makePosition({ instrumentId: 'AAPL-PUT', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
        makePosition({ instrumentId: 'MSFT' }),
      ]

      render(<PositionGrid positions={positions} />)

      expect(screen.getByTestId('strategy-row-strat-1')).toBeInTheDocument()
      expect(screen.getByTestId('position-row-MSFT')).toBeInTheDocument()
    })

    it('shows leg rows when strategy row is expanded', async () => {
      const user = userEvent.setup()
      const positions = [
        makePosition({ instrumentId: 'AAPL-CALL', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
        makePosition({ instrumentId: 'AAPL-PUT', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
      ]

      render(<PositionGrid positions={positions} />)

      await user.click(screen.getByTestId('strategy-row-strat-1'))

      expect(screen.getByTestId('strategy-leg-AAPL-CALL')).toBeInTheDocument()
      expect(screen.getByTestId('strategy-leg-AAPL-PUT')).toBeInTheDocument()
    })

    it('renders strategy net P&L in the strategy row', () => {
      const positions = [
        makePosition({ instrumentId: 'AAPL-CALL', strategyId: 'strat-1', strategyType: 'STRADDLE', unrealizedPnl: { amount: '300.00', currency: 'USD' } }),
        makePosition({ instrumentId: 'AAPL-PUT', strategyId: 'strat-1', strategyType: 'STRADDLE', unrealizedPnl: { amount: '200.00', currency: 'USD' } }),
      ]

      render(<PositionGrid positions={positions} />)

      expect(screen.getByTestId('strategy-net-pnl-strat-1')).toBeInTheDocument()
    })

    it('renders strategy net delta when risk data is provided', () => {
      const positions = [
        makePosition({ instrumentId: 'AAPL-CALL', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
        makePosition({ instrumentId: 'AAPL-PUT', strategyId: 'strat-1', strategyType: 'STRADDLE' }),
      ]
      const risk = [
        { instrumentId: 'AAPL-CALL', assetClass: 'EQUITY', marketValue: '10000.00', delta: '0.60', gamma: '0.02', vega: '15.00', theta: null, rho: null, varContribution: '400.00', esContribution: '500.00', percentageOfTotal: '40.00' } as PositionRiskDto,
        { instrumentId: 'AAPL-PUT', assetClass: 'EQUITY', marketValue: '10000.00', delta: '-0.40', gamma: '0.02', vega: '12.00', theta: null, rho: null, varContribution: '400.00', esContribution: '500.00', percentageOfTotal: '40.00' } as PositionRiskDto,
      ]

      render(<PositionGrid positions={positions} positionRisk={risk} />)

      expect(screen.getByTestId('strategy-net-delta-strat-1')).toBeInTheDocument()
    })
  })

  describe('instrument text search', () => {
    const positions = [
      makePosition({ instrumentId: 'AAPL', displayName: 'Apple Inc' }),
      makePosition({ instrumentId: 'GOOGL', displayName: 'Alphabet Inc' }),
      makePosition({ instrumentId: 'EUR_USD', displayName: 'Euro FX' }),
    ]

    it('renders a text search input', () => {
      render(<PositionGrid positions={positions} />)

      expect(screen.getByTestId('instrument-search')).toBeInTheDocument()
    })

    it('filters positions by instrumentId substring (case-insensitive)', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={positions} />)

      await user.type(screen.getByTestId('instrument-search'), 'eur')

      expect(screen.getByTestId('position-row-EUR_USD')).toBeInTheDocument()
      expect(screen.queryByTestId('position-row-AAPL')).not.toBeInTheDocument()
      expect(screen.queryByTestId('position-row-GOOGL')).not.toBeInTheDocument()
    })

    it('filters positions by displayName substring (case-insensitive)', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={positions} />)

      await user.type(screen.getByTestId('instrument-search'), 'alpha')

      expect(screen.getByTestId('position-row-GOOGL')).toBeInTheDocument()
      expect(screen.queryByTestId('position-row-AAPL')).not.toBeInTheDocument()
      expect(screen.queryByTestId('position-row-EUR_USD')).not.toBeInTheDocument()
    })

    it('resets to page 1 when search text changes', async () => {
      const user = userEvent.setup()
      const manyPositions = [
        ...Array.from({ length: 55 }, (_, i) =>
          makePosition({ instrumentId: `STOCK-${i}`, displayName: `Stock ${i}` }),
        ),
        makePosition({ instrumentId: 'EUR_USD', displayName: 'Euro FX' }),
      ]
      render(<PositionGrid positions={manyPositions} />)

      // Navigate to page 2
      await user.click(screen.getByTestId('pagination-next'))
      expect(screen.getByTestId('pagination-info')).toHaveTextContent('Page 2')

      // Type search text — should reset to page 1
      await user.type(screen.getByTestId('instrument-search'), 'EUR')
      expect(screen.queryByTestId('pagination-controls')).not.toBeInTheDocument()
      expect(screen.getByTestId('position-row-EUR_USD')).toBeInTheDocument()
    })

    it('shows all positions when search text is cleared', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={positions} />)

      await user.type(screen.getByTestId('instrument-search'), 'AAPL')
      expect(screen.getAllByTestId(/^position-row-/)).toHaveLength(1)

      await user.clear(screen.getByTestId('instrument-search'))
      expect(screen.getAllByTestId(/^position-row-/)).toHaveLength(3)
    })
  })

  describe('instrument type filter', () => {
    const positions = [
      makePosition({ instrumentId: 'AAPL', instrumentType: 'CASH_EQUITY' }),
      makePosition({ instrumentId: 'AAPL-OPT', instrumentType: 'EQUITY_OPTION' }),
      makePosition({ instrumentId: 'US10Y', instrumentType: 'GOVERNMENT_BOND' }),
    ]

    it('renders an instrument type filter dropdown', () => {
      render(<PositionGrid positions={positions} />)

      expect(screen.getByTestId('filter-instrument-type')).toBeInTheDocument()
    })

    it('defaults to showing all instrument types', () => {
      render(<PositionGrid positions={positions} />)

      const rows = screen.getAllByTestId(/^position-row-/)
      expect(rows).toHaveLength(3)
    })

    it('filters positions to the selected instrument type', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={positions} />)

      await user.selectOptions(screen.getByTestId('filter-instrument-type'), 'EQUITY_OPTION')

      expect(screen.getByTestId('position-row-AAPL-OPT')).toBeInTheDocument()
      expect(screen.queryByTestId('position-row-AAPL')).not.toBeInTheDocument()
      expect(screen.queryByTestId('position-row-US10Y')).not.toBeInTheDocument()
    })

    it('restores all positions when filter is reset to All', async () => {
      const user = userEvent.setup()
      render(<PositionGrid positions={positions} />)

      await user.selectOptions(screen.getByTestId('filter-instrument-type'), 'EQUITY_OPTION')
      await user.selectOptions(screen.getByTestId('filter-instrument-type'), '')

      const rows = screen.getAllByTestId(/^position-row-/)
      expect(rows).toHaveLength(3)
    })

    it('shows only instrument types present in the data', () => {
      render(<PositionGrid positions={positions} />)

      const select = screen.getByTestId('filter-instrument-type')
      const options = within(select).getAllByRole('option')
      const optionValues = options.map((o) => o.getAttribute('value'))
      expect(optionValues).toEqual(['', 'CASH_EQUITY', 'EQUITY_OPTION', 'GOVERNMENT_BOND'])
    })

    it('does not show instrument types absent from the data', () => {
      render(<PositionGrid positions={positions} />)

      const select = screen.getByTestId('filter-instrument-type')
      const optionTexts = within(select).getAllByRole('option').map((o) => o.textContent)
      expect(optionTexts).not.toContain(expect.stringContaining('FX'))
      expect(optionTexts).not.toContain(expect.stringContaining('Commodity'))
    })

    it('displays counts next to each filter option', () => {
      const positionsWithDuplicates = [
        ...positions,
        makePosition({ instrumentId: 'GOOGL', instrumentType: 'CASH_EQUITY' }),
      ]
      render(<PositionGrid positions={positionsWithDuplicates} />)

      const select = screen.getByTestId('filter-instrument-type')
      const options = within(select).getAllByRole('option')
      expect(options[1].textContent).toBe('Cash Equity (2)')
      expect(options[2].textContent).toBe('Equity Option (1)')
      expect(options[3].textContent).toBe('Government Bond (1)')
    })

    it('deduplicates instrument types in the dropdown', () => {
      const manyPositions = [
        ...Array.from({ length: 50 }, (_, i) =>
          makePosition({ instrumentId: `STOCK-${i}`, instrumentType: 'CASH_EQUITY' }),
        ),
        makePosition({ instrumentId: 'US10Y', instrumentType: 'GOVERNMENT_BOND' }),
      ]
      render(<PositionGrid positions={manyPositions} />)

      const select = screen.getByTestId('filter-instrument-type')
      const options = within(select).getAllByRole('option')
      // "All Types" + 2 unique types (not 51)
      expect(options).toHaveLength(3)
      expect(options[1].textContent).toBe('Cash Equity (50)')
      expect(options[2].textContent).toBe('Government Bond (1)')
    })

    it('excludes positions with undefined instrumentType from filter options', () => {
      const mixedPositions = [
        makePosition({ instrumentId: 'AAPL', instrumentType: 'CASH_EQUITY' }),
        makePosition({ instrumentId: 'UNKNOWN-1' }),
        makePosition({ instrumentId: 'US10Y', instrumentType: 'GOVERNMENT_BOND' }),
      ]
      render(<PositionGrid positions={mixedPositions} />)

      const select = screen.getByTestId('filter-instrument-type')
      const optionValues = within(select).getAllByRole('option').map((o) => o.getAttribute('value'))
      expect(optionValues).toEqual(['', 'CASH_EQUITY', 'GOVERNMENT_BOND'])
    })

    it('hides the filter dropdown when only one instrument type exists', () => {
      const singleType = [
        makePosition({ instrumentId: 'AAPL', instrumentType: 'CASH_EQUITY' }),
        makePosition({ instrumentId: 'GOOGL', instrumentType: 'CASH_EQUITY' }),
      ]
      render(<PositionGrid positions={singleType} />)

      expect(screen.queryByTestId('filter-instrument-type')).not.toBeInTheDocument()
    })

    it('resets stale filter when positions change to a dataset without the selected type', async () => {
      const user = userEvent.setup()
      const { rerender } = render(<PositionGrid positions={positions} />)

      await user.selectOptions(screen.getByTestId('filter-instrument-type'), 'EQUITY_OPTION')
      expect(screen.getByTestId('position-row-AAPL-OPT')).toBeInTheDocument()

      // Simulate book switch — new dataset has no EQUITY_OPTION
      const newPositions = [
        makePosition({ instrumentId: 'US10Y', instrumentType: 'GOVERNMENT_BOND' }),
        makePosition({ instrumentId: 'DE10Y', instrumentType: 'GOVERNMENT_BOND' }),
        makePosition({ instrumentId: 'AAPL', instrumentType: 'CASH_EQUITY' }),
      ]
      rerender(<PositionGrid positions={newPositions} />)

      // Filter should auto-reset — all new positions visible
      const rows = screen.getAllByTestId(/^position-row-/)
      expect(rows).toHaveLength(3)
      expect(screen.getByTestId('filter-reset-notice')).toBeInTheDocument()
    })

    it('resets to page 1 when filter changes', async () => {
      const user = userEvent.setup()
      const manyPositions = [
        ...Array.from({ length: 55 }, (_, i) =>
          makePosition({ instrumentId: `STOCK-${i}`, instrumentType: 'CASH_EQUITY' }),
        ),
        makePosition({ instrumentId: 'BOND-1', instrumentType: 'GOVERNMENT_BOND' }),
      ]
      render(<PositionGrid positions={manyPositions} />)

      // Navigate to page 2
      await user.click(screen.getByTestId('pagination-next'))
      expect(screen.getByTestId('pagination-info')).toHaveTextContent('Page 2')

      // Apply filter — should reset to page 1
      await user.selectOptions(screen.getByTestId('filter-instrument-type'), 'GOVERNMENT_BOND')
      expect(screen.queryByTestId('pagination-controls')).not.toBeInTheDocument()
    })

    it('renders instrument type badges in the type column', () => {
      render(<PositionGrid positions={[makePosition({ instrumentId: 'AAPL', instrumentType: 'CASH_EQUITY' })]} />)

      const row = screen.getByTestId('position-row-AAPL')
      expect(within(row).getByText('Cash Equity')).toBeInTheDocument()
    })
  })
})
