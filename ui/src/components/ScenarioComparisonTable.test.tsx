import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ChatChunk, ChatRequest } from '../api/copilot'
import { ScenarioComparisonTable } from './ScenarioComparisonTable'
import {
  ALL_STRESS_RESULTS,
  makeStressResult,
  makeLimitBreach,
  makePositionImpact,
} from '../test-utils/stressMocks'

const defaultProps = {
  results: ALL_STRESS_RESULTS,
  selectedScenario: null,
  onSelectScenario: vi.fn(),
}

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

describe('ScenarioComparisonTable', () => {
  it('should render column headers: Scenario, Base VaR, Stressed VaR, VaR Multiplier, P&L Impact', () => {
    render(<ScenarioComparisonTable {...defaultProps} />)

    expect(screen.getByText('Scenario')).toBeInTheDocument()
    expect(screen.getByText('Base VaR')).toBeInTheDocument()
    expect(screen.getByText('Stressed VaR')).toBeInTheDocument()
    expect(screen.getByText('VaR Multiplier')).toBeInTheDocument()
  })

  it('collapses an identical Base VaR into one header stat instead of repeating it per row', () => {
    // UX review: Base VaR was the same $167,731.40 on all 16 rows — a column
    // of identical values is noise.
    render(<ScenarioComparisonTable {...defaultProps} />)

    const stat = screen.getByTestId('common-base-var')
    expect(stat).toHaveTextContent('Base VaR')
    expect(stat).toHaveTextContent('$100,000.00')
    expect(stat).toHaveTextContent(/all 4 scenarios/i)
    // The repeated value appears once, not once per row.
    expect(screen.getAllByText('$100,000.00')).toHaveLength(1)
  })

  it('keeps the per-row Base VaR column when scenarios have different base VaRs', () => {
    const results = [
      makeStressResult({ scenarioName: 'GFC_2008', baseVar: '100000.00', stressedVar: '300000.00' }),
      makeStressResult({ scenarioName: 'COVID_2020', baseVar: '120000.00', stressedVar: '250000.00' }),
    ]
    render(<ScenarioComparisonTable {...defaultProps} results={results} />)

    expect(screen.queryByTestId('common-base-var')).not.toBeInTheDocument()
    expect(screen.getByText('$100,000.00')).toBeInTheDocument()
    expect(screen.getByText('$120,000.00')).toBeInTheDocument()
  })

  it('omits the Category column entirely when no scenario carries a category', () => {
    render(<ScenarioComparisonTable {...defaultProps} />)
    expect(screen.queryByText('Category')).not.toBeInTheDocument()
    expect(screen.queryAllByTestId('scenario-category')).toHaveLength(0)
  })

  it('renders the Category column when scenario metadata provides categories', () => {
    render(
      <ScenarioComparisonTable
        {...defaultProps}
        scenarioMetadata={[
          {
            name: 'GFC_2008',
            description: 'Global financial crisis',
            shocks: [],
            status: 'APPROVED',
            approvedBy: 'risk-committee',
            scenarioCategory: 'REGULATORY_MANDATED',
          } as never,
        ]}
      />,
    )

    expect(screen.getByText('Category')).toBeInTheDocument()
    expect(screen.getByText('Regulatory')).toBeInTheDocument()
  })

  it('should render one row per scenario result', () => {
    render(<ScenarioComparisonTable {...defaultProps} />)

    const rows = screen.getAllByTestId('scenario-row')
    expect(rows).toHaveLength(4)
  })

  it('should display results with scenario names formatted with spaces', () => {
    render(<ScenarioComparisonTable {...defaultProps} />)

    expect(screen.getByText('GFC 2008')).toBeInTheDocument()
    expect(screen.getByText('COVID 2020')).toBeInTheDocument()
    expect(screen.getByText('TAPER TANTRUM 2013')).toBeInTheDocument()
    expect(screen.getByText('EURO CRISIS 2011')).toBeInTheDocument()
  })

  it('should format VaR Multiplier as Stressed/Base ratio', () => {
    render(<ScenarioComparisonTable {...defaultProps} />)

    const multipliers = screen.getAllByTestId('var-multiplier')
    // GFC: stressedVar=300000, baseVar=100000 => 3.0x
    expect(multipliers[0]).toHaveTextContent('3.0x')
  })

  it('should display P&L Impact with red text for losses', () => {
    render(<ScenarioComparisonTable {...defaultProps} />)

    const impacts = screen.getAllByTestId('pnl-impact')
    impacts.forEach((impact) => {
      expect(impact.className).toContain('text-red-600')
    })
  })

  it('should highlight selected scenario row on click', () => {
    const onSelect = vi.fn()
    render(<ScenarioComparisonTable {...defaultProps} onSelectScenario={onSelect} />)

    const rows = screen.getAllByTestId('scenario-row')
    fireEvent.click(rows[0])
    expect(onSelect).toHaveBeenCalledWith('GFC_2008')
  })

  it('should deselect when clicking an already selected scenario', () => {
    const onSelect = vi.fn()
    render(
      <ScenarioComparisonTable
        {...defaultProps}
        selectedScenario="GFC_2008"
        onSelectScenario={onSelect}
      />,
    )

    const rows = screen.getAllByTestId('scenario-row')
    fireEvent.click(rows[0])
    expect(onSelect).toHaveBeenCalledWith(null)
  })

  it('should handle zero Base VaR without division by zero', () => {
    const results = [makeStressResult({ baseVar: '0.00', stressedVar: '100000.00' })]
    render(<ScenarioComparisonTable results={results} selectedScenario={null} onSelectScenario={vi.fn()} />)

    const multipliers = screen.getAllByTestId('var-multiplier')
    expect(multipliers[0]).toHaveTextContent('-')
  })

  it('should show empty state message when no results exist', () => {
    render(<ScenarioComparisonTable results={[]} selectedScenario={null} onSelectScenario={vi.fn()} />)

    expect(screen.getByTestId('no-results')).toBeInTheDocument()
  })

  it('should render Limits column header when breaches exist', () => {
    const results = [
      makeStressResult({
        scenarioName: 'GFC_2008',
        limitBreaches: [makeLimitBreach({ breachSeverity: 'BREACHED' })],
      }),
    ]
    render(<ScenarioComparisonTable results={results} selectedScenario={null} onSelectScenario={vi.fn()} />)

    expect(screen.getByText('Limits')).toBeInTheDocument()
  })

  it('should show breach count per scenario row with color coding', () => {
    const results = [
      makeStressResult({
        scenarioName: 'GFC_2008',
        limitBreaches: [
          makeLimitBreach({ breachSeverity: 'BREACHED' }),
          makeLimitBreach({ limitType: 'VAR', breachSeverity: 'WARNING' }),
        ],
      }),
      makeStressResult({
        scenarioName: 'COVID_2020',
        limitBreaches: [makeLimitBreach({ breachSeverity: 'OK' })],
      }),
    ]
    render(<ScenarioComparisonTable results={results} selectedScenario={null} onSelectScenario={vi.fn()} />)

    const badges = screen.getAllByTestId('breach-badge')
    expect(badges[0]).toHaveTextContent('2 breach')
    expect(badges[0].firstChild!).toHaveClass('bg-red-100')
    expect(badges[1]).toHaveTextContent('OK')
    expect(badges[1].firstChild!).toHaveClass('bg-green-100')
  })

  it('should color-code row left border: red for breach, amber for warning', () => {
    const results = [
      makeStressResult({
        scenarioName: 'GFC_2008',
        limitBreaches: [makeLimitBreach({ breachSeverity: 'BREACHED' })],
      }),
      makeStressResult({
        scenarioName: 'COVID_2020',
        limitBreaches: [makeLimitBreach({ breachSeverity: 'WARNING' })],
      }),
    ]
    render(<ScenarioComparisonTable results={results} selectedScenario={null} onSelectScenario={vi.fn()} />)

    const rows = screen.getAllByTestId('scenario-row')
    expect(rows[0].className).toContain('border-l-red-500')
    expect(rows[1].className).toContain('border-l-amber-400')
  })

  it('should not render Limits column when no results have breaches', () => {
    render(<ScenarioComparisonTable {...defaultProps} />)

    expect(screen.queryByText('Limits')).not.toBeInTheDocument()
  })

  describe('inline explainer (plan §9.4)', () => {
    it('renders a per-row explain button for every scenario result', () => {
      render(<ScenarioComparisonTable {...defaultProps} />)

      expect(screen.getByTestId('explain-scenario-GFC_2008')).toBeInTheDocument()
      expect(
        screen.getByTestId('explain-scenario-COVID_2020'),
      ).toBeInTheDocument()
    })

    it('clicking a scenario explain button opens an inline insight panel', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(<ScenarioComparisonTable {...defaultProps} chatFn={chatFn} />)

      await user.click(screen.getByTestId('explain-scenario-GFC_2008'))

      expect(
        screen.getByTestId('scenario-explain-panel-GFC_2008'),
      ).toBeInTheDocument()
      expect(chatFn).toHaveBeenCalledTimes(1)
    })

    it('sends scenario name, stressed PnL and top stressed positions in page_context', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      const result = makeStressResult({
        scenarioName: 'GFC_2008',
        pnlImpact: '-500000.00',
        stressedVar: '300000.00',
        positionImpacts: [
          makePositionImpact({ instrumentId: 'AAPL', pnlImpact: '-50000.00' }),
          makePositionImpact({ instrumentId: 'BIG', pnlImpact: '-900000.00' }),
          makePositionImpact({ instrumentId: 'TINY', pnlImpact: '-100.00' }),
        ],
      })
      render(
        <ScenarioComparisonTable
          {...defaultProps}
          results={[result]}
          chatFn={chatFn}
        />,
      )

      await user.click(screen.getByTestId('explain-scenario-GFC_2008'))

      const request = chatFn.mock.calls[0][0]
      const ctx = request.page_context
      expect(ctx.page).toBe('scenarios')
      expect(ctx.scenario_name).toBe('GFC_2008')
      expect(ctx.stressed_pnl).toBe('-500000.00')

      const top = ctx.top_stressed_positions as { instrumentId: string }[]
      // Ranked by absolute P&L impact, worst first.
      expect(top.map((p) => p.instrumentId)).toEqual(['BIG', 'AAPL', 'TINY'])
    })

    it('a double-click does not open a duplicate panel or fire a duplicate request', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(<ScenarioComparisonTable {...defaultProps} chatFn={chatFn} />)

      const button = screen.getByTestId('explain-scenario-GFC_2008')
      await user.click(button)
      await user.click(button)

      expect(chatFn).toHaveBeenCalledTimes(1)
      expect(
        screen.getAllByTestId('scenario-explain-panel-GFC_2008'),
      ).toHaveLength(1)
    })

    it('opening a second scenario explainer replaces the first (only one panel open)', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(<ScenarioComparisonTable {...defaultProps} chatFn={chatFn} />)

      await user.click(screen.getByTestId('explain-scenario-GFC_2008'))
      await user.click(screen.getByTestId('explain-scenario-COVID_2020'))

      expect(
        screen.queryByTestId('scenario-explain-panel-GFC_2008'),
      ).not.toBeInTheDocument()
      expect(
        screen.getByTestId('scenario-explain-panel-COVID_2020'),
      ).toBeInTheDocument()
      expect(chatFn).toHaveBeenCalledTimes(2)
    })

    it('closes the explainer when the panel close button is clicked', async () => {
      const user = userEvent.setup()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(<ScenarioComparisonTable {...defaultProps} chatFn={chatFn} />)

      await user.click(screen.getByTestId('explain-scenario-GFC_2008'))
      expect(
        screen.getByTestId('scenario-explain-panel-GFC_2008'),
      ).toBeInTheDocument()

      await user.click(screen.getByTestId('ai-insight-close'))
      expect(
        screen.queryByTestId('scenario-explain-panel-GFC_2008'),
      ).not.toBeInTheDocument()
    })

    it('clicking the explain button does not toggle the row selection', async () => {
      const user = userEvent.setup()
      const onSelect = vi.fn()
      const chatFn = vi.fn<ChatFn>(() => streamOf(doneChunk))
      render(
        <ScenarioComparisonTable
          {...defaultProps}
          onSelectScenario={onSelect}
          chatFn={chatFn}
        />,
      )

      await user.click(screen.getByTestId('explain-scenario-GFC_2008'))

      expect(onSelect).not.toHaveBeenCalled()
    })
  })
})
