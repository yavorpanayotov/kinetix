import { render, screen, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PositionRiskDto } from '../types'
import type { ChatChunk, ChatRequest } from '../api/copilot'
import { PositionRiskTable } from './PositionRiskTable'

/** Signature of the injectable `chatFn` prop — mirrors `chat` in `api/copilot`. */
type ChatFn = (
  request: ChatRequest,
  options?: { signal?: AbortSignal },
) => ReadableStream<ChatChunk>

/** Build a `ReadableStream<ChatChunk>` that emits the supplied chunks then closes. */
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
  session_id: 's',
  conversation_id: 'c',
  model: 'canned-chat',
  mode: 'canned',
}

const makeRisk = (overrides: Partial<PositionRiskDto> = {}): PositionRiskDto => ({
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  marketValue: '15500.00',
  delta: '1234.56',
  gamma: '45.67',
  vega: '89.01',
  theta: '-12.34',
  rho: '5.67',
  varContribution: '800.00',
  esContribution: '1000.00',
  percentageOfTotal: '64.85',
  ...overrides,
})

describe('PositionRiskTable', () => {
  describe('rendering', () => {
    it('renders all column headers', () => {
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      const expectedHeaders = [
        'Instrument',
        'Asset Class',
        'Mkt Value',
        'Delta',
        'Gamma',
        'Vega',
        'Theta',
        'Rho',
        'DV01',
        'VaR Contrib',
        'ES Contrib',
        '% Total',
      ]
      const allHeaders = screen.getAllByRole('columnheader')
      const headerTexts = allHeaders.map((h) => h.textContent?.trim() ?? '')
      expectedHeaders.forEach((header) => {
        expect(headerTexts.some((t) => t.includes(header))).toBe(true)
      })
    })

    it('renders row data with formatted numbers', () => {
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      const row = screen.getByTestId('position-risk-row-AAPL')
      expect(within(row).getByText('AAPL')).toBeInTheDocument()
      expect(within(row).getByText('Equity')).toBeInTheDocument()
      expect(within(row).getByText('15,500.00')).toBeInTheDocument()
      expect(within(row).getByText('1,234.56')).toBeInTheDocument()
      expect(within(row).getByText('45.67')).toBeInTheDocument()
      expect(within(row).getByText('89.01')).toBeInTheDocument()
      expect(within(row).getByText('800.00')).toBeInTheDocument()
      expect(within(row).getByText('1,000.00')).toBeInTheDocument()
      expect(within(row).getByText('64.85%')).toBeInTheDocument()
    })

    it('renders theta and rho values', () => {
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      const row = screen.getByTestId('position-risk-row-AAPL')
      expect(within(row).getByText('-12.34')).toBeInTheDocument()
      expect(within(row).getByText('5.67')).toBeInTheDocument()
    })

    it('shows dash for null greek values including theta and rho', () => {
      render(
        <PositionRiskTable
          data={[makeRisk({ delta: null, gamma: null, vega: null, theta: null, rho: null })]}
          loading={false}
        />,
      )

      const row = screen.getByTestId('position-risk-row-AAPL')
      const cells = within(row).getAllByText('\u2014')
      // 5 null Greeks (delta, gamma, vega, theta, rho) + DV01 (non-FIXED_INCOME
      // row always renders em-dash for DV01).
      expect(cells).toHaveLength(6)
    })
  })

  describe('DV01 column (rates-only)', () => {
    it('renders the DV01 column header with rates-only tooltip', () => {
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      const header = screen.getByTestId('sort-dv01')
      expect(header).toHaveTextContent('DV01')
      expect(header).toHaveAttribute(
        'title',
        expect.stringContaining('FIXED_INCOME'),
      )
    })

    it('shows the DV01 value formatted as USD for a FIXED_INCOME row', () => {
      render(
        <PositionRiskTable
          data={[
            makeRisk({
              instrumentId: 'UST_10Y',
              assetClass: 'FIXED_INCOME',
              dv01: '4250.75',
            }),
          ]}
          loading={false}
        />,
      )

      const cell = screen.getByTestId('dv01-UST_10Y')
      // formatMoney(..., 'USD') rounds to cents and uses en-US locale.
      expect(cell).toHaveTextContent('$4,250.75')
    })

    it('renders an em-dash in the DV01 cell for non-FIXED_INCOME rows even when dv01 is supplied', () => {
      render(
        <PositionRiskTable
          data={[
            makeRisk({
              instrumentId: 'AAPL',
              assetClass: 'EQUITY',
              dv01: '999.99',
            }),
          ]}
          loading={false}
        />,
      )

      const cell = screen.getByTestId('dv01-AAPL')
      expect(cell).toHaveTextContent('\u2014')
      expect(cell).not.toHaveTextContent('999')
    })

    it('renders an em-dash in the DV01 cell for a FIXED_INCOME row when dv01 is null', () => {
      render(
        <PositionRiskTable
          data={[
            makeRisk({
              instrumentId: 'UST_2Y',
              assetClass: 'FIXED_INCOME',
              dv01: null,
            }),
          ]}
          loading={false}
        />,
      )

      const cell = screen.getByTestId('dv01-UST_2Y')
      expect(cell).toHaveTextContent('\u2014')
    })

    it('shows DV01 in the expanded detail panel for a FIXED_INCOME row', async () => {
      const user = userEvent.setup()
      render(
        <PositionRiskTable
          data={[
            makeRisk({
              instrumentId: 'UST_10Y',
              assetClass: 'FIXED_INCOME',
              dv01: '4250.75',
            }),
          ]}
          loading={false}
        />,
      )

      await user.click(screen.getByTestId('position-risk-row-UST_10Y'))

      const detail = screen.getByTestId('dv01-detail-UST_10Y')
      expect(detail).toHaveTextContent('$4,250.75')
    })
  })

  describe('default sorting', () => {
    it('sorts by absolute varContribution descending by default', () => {
      const data = [
        makeRisk({ instrumentId: 'SMALL', varContribution: '100.00', percentageOfTotal: '10.00' }),
        makeRisk({ instrumentId: 'LARGE', varContribution: '-900.00', percentageOfTotal: '60.00' }),
        makeRisk({ instrumentId: 'MED', varContribution: '500.00', percentageOfTotal: '30.00' }),
      ]
      render(<PositionRiskTable data={data} loading={false} />)

      const rows = screen.getAllByTestId(/^position-risk-row-/)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-risk-row-LARGE')
      expect(rows[1]).toHaveAttribute('data-testid', 'position-risk-row-MED')
      expect(rows[2]).toHaveAttribute('data-testid', 'position-risk-row-SMALL')
    })
  })

  describe('sortable columns', () => {
    it('sorts by clicked column header descending on first click', async () => {
      const user = userEvent.setup()
      const data = [
        makeRisk({ instrumentId: 'A', marketValue: '100.00', varContribution: '50.00', percentageOfTotal: '10.00' }),
        makeRisk({ instrumentId: 'B', marketValue: '300.00', varContribution: '30.00', percentageOfTotal: '20.00' }),
        makeRisk({ instrumentId: 'C', marketValue: '200.00', varContribution: '20.00', percentageOfTotal: '70.00' }),
      ]
      render(<PositionRiskTable data={data} loading={false} />)

      await user.click(screen.getByTestId('sort-marketValue'))

      const rows = screen.getAllByTestId(/^position-risk-row-/)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-risk-row-B')
      expect(rows[1]).toHaveAttribute('data-testid', 'position-risk-row-C')
      expect(rows[2]).toHaveAttribute('data-testid', 'position-risk-row-A')
    })

    it('toggles to ascending on second click of the same column', async () => {
      const user = userEvent.setup()
      const data = [
        makeRisk({ instrumentId: 'A', marketValue: '100.00', varContribution: '50.00', percentageOfTotal: '10.00' }),
        makeRisk({ instrumentId: 'B', marketValue: '300.00', varContribution: '30.00', percentageOfTotal: '20.00' }),
      ]
      render(<PositionRiskTable data={data} loading={false} />)

      const header = screen.getByTestId('sort-marketValue')
      await user.click(header)
      await user.click(header)

      const rows = screen.getAllByTestId(/^position-risk-row-/)
      expect(rows[0]).toHaveAttribute('data-testid', 'position-risk-row-A')
      expect(rows[1]).toHaveAttribute('data-testid', 'position-risk-row-B')
    })
  })

  describe('colour-coding % of Total', () => {
    it('applies red text when percentage is above 30', () => {
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'HIGH', percentageOfTotal: '35.00', varContribution: '900.00' })]}
          loading={false}
        />,
      )

      const cell = screen.getByTestId('pct-total-HIGH')
      expect(cell).toHaveClass('text-red-600')
    })

    it('applies amber text when percentage is above 15 but at or below 30', () => {
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'MED', percentageOfTotal: '20.00', varContribution: '500.00' })]}
          loading={false}
        />,
      )

      const cell = screen.getByTestId('pct-total-MED')
      expect(cell).toHaveClass('text-amber-600')
    })

    it('applies neutral text when percentage is at or below 15', () => {
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'LOW', percentageOfTotal: '10.00', varContribution: '100.00' })]}
          loading={false}
        />,
      )

      const cell = screen.getByTestId('pct-total-LOW')
      expect(cell).not.toHaveClass('text-red-600')
      expect(cell).not.toHaveClass('text-amber-600')
    })
  })

  describe('collapsible', () => {
    it('starts expanded with table visible', () => {
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      expect(screen.getByTestId('position-risk-table')).toBeInTheDocument()
    })

    it('collapses the table when the header toggle is clicked', async () => {
      const user = userEvent.setup()
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      await user.click(screen.getByTestId('position-risk-toggle'))

      expect(screen.queryByTestId('position-risk-table')).not.toBeInTheDocument()
    })

    it('expands the table again when toggled a second time', async () => {
      const user = userEvent.setup()
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      const toggle = screen.getByTestId('position-risk-toggle')
      await user.click(toggle)
      await user.click(toggle)

      expect(screen.getByTestId('position-risk-table')).toBeInTheDocument()
    })
  })

  describe('expandable rows', () => {
    it('expands a row when clicked', async () => {
      const user = userEvent.setup()
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      await user.click(screen.getByTestId('position-risk-row-AAPL'))

      expect(screen.getByTestId('position-risk-detail-AAPL')).toBeInTheDocument()
    })

    it('collapses an expanded row when clicked again', async () => {
      const user = userEvent.setup()
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      await user.click(screen.getByTestId('position-risk-row-AAPL'))
      expect(screen.getByTestId('position-risk-detail-AAPL')).toBeInTheDocument()

      await user.click(screen.getByTestId('position-risk-row-AAPL'))
      expect(screen.queryByTestId('position-risk-detail-AAPL')).not.toBeInTheDocument()
    })

    it('shows position detail information in expanded row', async () => {
      const user = userEvent.setup()
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      await user.click(screen.getByTestId('position-risk-row-AAPL'))

      const detail = screen.getByTestId('position-risk-detail-AAPL')
      expect(detail).toHaveTextContent('Market Value')
      expect(detail).toHaveTextContent('VaR Contribution')
      expect(detail).toHaveTextContent('ES Contribution')
    })

    it('only expands one row at a time', async () => {
      const user = userEvent.setup()
      const data = [
        makeRisk({ instrumentId: 'AAPL', varContribution: '800.00', percentageOfTotal: '60.00' }),
        makeRisk({ instrumentId: 'MSFT', varContribution: '500.00', percentageOfTotal: '40.00' }),
      ]
      render(<PositionRiskTable data={data} loading={false} />)

      await user.click(screen.getByTestId('position-risk-row-AAPL'))
      expect(screen.getByTestId('position-risk-detail-AAPL')).toBeInTheDocument()

      await user.click(screen.getByTestId('position-risk-row-MSFT'))
      expect(screen.queryByTestId('position-risk-detail-AAPL')).not.toBeInTheDocument()
      expect(screen.getByTestId('position-risk-detail-MSFT')).toBeInTheDocument()
    })
  })

  describe('loading state', () => {
    it('shows a loading spinner when loading is true', () => {
      render(<PositionRiskTable data={[]} loading={true} />)

      expect(screen.getByTestId('position-risk-loading')).toBeInTheDocument()
    })

    it('does not show the table when loading', () => {
      render(<PositionRiskTable data={[]} loading={true} />)

      expect(screen.queryByTestId('position-risk-table')).not.toBeInTheDocument()
    })
  })

  describe('error state', () => {
    it('shows the error message when error is provided', () => {
      render(<PositionRiskTable data={[]} loading={false} error="Failed to fetch position risk" />)

      expect(screen.getByTestId('position-risk-error')).toHaveTextContent('Failed to fetch position risk')
      expect(screen.queryByTestId('position-risk-empty')).not.toBeInTheDocument()
      expect(screen.queryByTestId('position-risk-table')).not.toBeInTheDocument()
    })

    it('has role="alert" on the error container', () => {
      render(<PositionRiskTable data={[]} loading={false} error="Connection timeout" />)

      expect(screen.getByTestId('position-risk-error')).toHaveAttribute('role', 'alert')
    })

    it('shows a Retry button when error is present and onRetry is provided', () => {
      const onRetry = vi.fn()
      render(<PositionRiskTable data={[]} loading={false} error="Network error" onRetry={onRetry} />)

      expect(screen.getByTestId('position-risk-retry')).toBeInTheDocument()
    })

    it('calls onRetry when the Retry button is clicked', () => {
      const onRetry = vi.fn()
      render(<PositionRiskTable data={[]} loading={false} error="Network error" onRetry={onRetry} />)

      fireEvent.click(screen.getByTestId('position-risk-retry'))
      expect(onRetry).toHaveBeenCalledTimes(1)
    })

    it('does not show a Retry button when error is present but onRetry is not provided', () => {
      render(<PositionRiskTable data={[]} loading={false} error="Network error" />)

      expect(screen.queryByTestId('position-risk-retry')).not.toBeInTheDocument()
    })
  })

  describe('empty state', () => {
    it('shows contextual empty message when data is empty and no error', () => {
      render(<PositionRiskTable data={[]} loading={false} />)

      const empty = screen.getByTestId('position-risk-empty')
      expect(empty).toBeInTheDocument()
      expect(empty).toHaveTextContent('Positions will appear after the next VaR calculation')
    })

    it('shows error detail and retry message when data is empty with error', () => {
      render(<PositionRiskTable data={[]} loading={false} error="Connection timeout" />)

      const errorEl = screen.getByTestId('position-risk-error')
      expect(errorEl).toHaveTextContent('Unable to load position risk')
      expect(errorEl).toHaveTextContent('Connection timeout')
    })
  })

  describe('CSV export', () => {
    it('shows CSV export button when data is present', () => {
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      expect(screen.getByTestId('risk-csv-export')).toBeInTheDocument()
    })

    it('does not show CSV export button when data is empty', () => {
      render(<PositionRiskTable data={[]} loading={false} />)

      expect(screen.queryByTestId('risk-csv-export')).not.toBeInTheDocument()
    })
  })

  describe('inline explainer', () => {
    it('renders a per-row explain button for every row', () => {
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'AAPL' }), makeRisk({ instrumentId: 'MSFT' })]}
          loading={false}
        />,
      )

      expect(screen.getByTestId('explain-position-AAPL')).toBeInTheDocument()
      expect(screen.getByTestId('explain-position-MSFT')).toBeInTheDocument()
    })

    it('renders a portfolio-level explain button in the header', () => {
      render(<PositionRiskTable data={[makeRisk()]} loading={false} />)

      expect(screen.getByTestId('explain-positions-portfolio')).toBeInTheDocument()
    })

    it('opens the insight panel and fires chat() with the row position payload', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'AAPL' })]}
          loading={false}
          bookId="port-1"
          chatFn={chatFn}
        />,
      )

      await user.click(screen.getByTestId('explain-position-AAPL'))

      expect(screen.getByTestId('position-explain-panel')).toBeInTheDocument()
      expect(chatFn).toHaveBeenCalledTimes(1)
      const request = chatFn.mock.calls[0][0]
      expect(request.page_context).toMatchObject({
        page: 'positions',
        book_id: 'port-1',
        instrument_id: 'AAPL',
      })
      expect(request.page_context.position).toMatchObject({ instrumentId: 'AAPL' })
    })

    it('does not fire a duplicate chat() on a second click of the same row button', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'AAPL' })]}
          loading={false}
          chatFn={chatFn}
        />,
      )

      const button = screen.getByTestId('explain-position-AAPL')
      await user.click(button)
      await user.click(button)
      await user.click(button)

      expect(chatFn).toHaveBeenCalledTimes(1)
      expect(screen.getAllByTestId('position-explain-panel')).toHaveLength(1)
    })

    it('opening a second row explainer replaces the first (only one panel open)', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'AAPL' }), makeRisk({ instrumentId: 'MSFT' })]}
          loading={false}
          chatFn={chatFn}
        />,
      )

      await user.click(screen.getByTestId('explain-position-AAPL'))
      await user.click(screen.getByTestId('explain-position-MSFT'))

      const panels = screen.getAllByTestId('position-explain-panel')
      expect(panels).toHaveLength(1)
      expect(panels[0]).toHaveTextContent('MSFT')
      expect(chatFn).toHaveBeenCalledTimes(2)
    })

    it('the header explain button sends portfolio-scope page_context', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'AAPL' }), makeRisk({ instrumentId: 'MSFT' })]}
          loading={false}
          bookId="port-1"
          chatFn={chatFn}
        />,
      )

      await user.click(screen.getByTestId('explain-positions-portfolio'))

      expect(screen.getByTestId('position-explain-panel')).toBeInTheDocument()
      const request = chatFn.mock.calls[0][0]
      expect(request.page_context).toMatchObject({
        page: 'positions',
        book_id: 'port-1',
        scope: 'portfolio',
        position_count: 2,
      })
      expect(request.page_context.instrument_id).toBeUndefined()
    })

    it('closes the explainer when the panel close button is clicked', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'AAPL' })]}
          loading={false}
          chatFn={chatFn}
        />,
      )

      await user.click(screen.getByTestId('explain-position-AAPL'))
      expect(screen.getByTestId('position-explain-panel')).toBeInTheDocument()

      await user.click(screen.getByTestId('ai-insight-close'))
      expect(screen.queryByTestId('position-explain-panel')).not.toBeInTheDocument()
    })

    it('clicking a row explain button does not toggle the row detail', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(
        <PositionRiskTable
          data={[makeRisk({ instrumentId: 'AAPL' })]}
          loading={false}
          chatFn={chatFn}
        />,
      )

      await user.click(screen.getByTestId('explain-position-AAPL'))

      expect(screen.queryByTestId('position-risk-detail-AAPL')).not.toBeInTheDocument()
    })
  })
})
