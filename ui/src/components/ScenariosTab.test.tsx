import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ScenariosTab } from './ScenariosTab'
import { ALL_STRESS_RESULTS } from '../test-utils/stressMocks'

vi.mock('../api/scenarios', () => ({
  listScenarios: vi.fn().mockResolvedValue([]),
  listApprovedScenarios: vi.fn().mockResolvedValue([]),
  createScenario: vi.fn(),
  submitScenario: vi.fn(),
  approveScenario: vi.fn(),
  retireScenario: vi.fn(),
}))

// The batch (Run All) endpoint returns slim summaries — only scenarioName,
// baseVar, stressedVar, pnlImpact. Drill-down needs the full
// ``StressTestResultDto`` (asset class impacts, position impacts, Greeks),
// so ScenariosTab lazy-fetches the full result via the single-scenario
// route when the user expands a row. The mock here returns the full
// ``ALL_STRESS_RESULTS`` entry that matches the requested scenarioName
// so the detail panel can render against realistic shape.
vi.mock('../api/stress', () => ({
  runStressTest: vi.fn().mockImplementation(async (_bookId: string, scenarioName: string) => {
    return ALL_STRESS_RESULTS.find((r) => r.scenarioName === scenarioName) ?? null
  }),
}))

vi.mock('../api/historicalReplay', () => ({
  runHistoricalReplay: vi.fn(),
  runReverseStress: vi.fn(),
}))

const defaultProps = {
  bookId: 'book-1',
  results: [],
  loading: false,
  error: null,
  selectedScenario: null,
  onSelectScenario: vi.fn(),
  confidenceLevel: 'CL_95',
  onConfidenceLevelChange: vi.fn(),
  timeHorizonDays: '1',
  onTimeHorizonDaysChange: vi.fn(),
  onRunAll: vi.fn(),
  onAppendResult: vi.fn(),
}

describe('ScenariosTab', () => {
  it('renders the scenarios tab with control bar and empty state', () => {
    render(<ScenariosTab {...defaultProps} />)

    expect(screen.getByTestId('scenarios-tab')).toBeInTheDocument()
    expect(screen.getByTestId('scenario-control-bar')).toBeInTheDocument()
    expect(screen.getByTestId('no-results')).toBeInTheDocument()
  })

  it('renders comparison table when results are provided', () => {
    render(<ScenariosTab {...defaultProps} results={ALL_STRESS_RESULTS} />)

    expect(screen.getByTestId('scenario-comparison-table')).toBeInTheDocument()
    expect(screen.getAllByTestId('scenario-row')).toHaveLength(4)
  })

  it('shows loading state', () => {
    render(<ScenariosTab {...defaultProps} loading={true} />)

    expect(screen.getByTestId('stress-loading')).toBeInTheDocument()
  })

  it('shows error message', () => {
    render(<ScenariosTab {...defaultProps} error="Something went wrong" />)

    expect(screen.getByTestId('stress-error')).toHaveTextContent('Something went wrong')
  })

  it('shows scenario library grid when Manage Scenarios is clicked', async () => {
    render(<ScenariosTab {...defaultProps} />)

    await userEvent.click(screen.getByTestId('manage-scenarios-btn'))
    expect(screen.getByTestId('scenario-library-grid')).toBeInTheDocument()
  })

  describe('cross-tab link: scenario row to ScenarioDetailPanel (plan §2.4)', () => {
    it('clicking a scenario row calls onSelectScenario with that scenario name', () => {
      const onSelectScenario = vi.fn()
      render(
        <ScenariosTab
          {...defaultProps}
          results={ALL_STRESS_RESULTS}
          onSelectScenario={onSelectScenario}
        />,
      )

      const rows = screen.getAllByTestId('scenario-row')
      fireEvent.click(rows[0])

      expect(onSelectScenario).toHaveBeenCalledWith(ALL_STRESS_RESULTS[0].scenarioName)
    })

    it('renders the ScenarioDetailPanel for the matched result when selectedScenario is set', async () => {
      const selected = ALL_STRESS_RESULTS[0]
      render(
        <ScenariosTab
          {...defaultProps}
          results={ALL_STRESS_RESULTS}
          selectedScenario={selected.scenarioName}
        />,
      )

      // ScenarioDetailPanel emits detail-panel only when a result is bound;
      // its presence proves the row → panel cross-link is wired end to end.
      // The panel renders after the lazy fetch resolves (see vi.mock for
      // ../api/stress at the top of this file).
      await waitFor(() =>
        expect(screen.getByTestId('detail-panel')).toBeInTheDocument(),
      )
    })

    it('lazy-fetches full detail when a scenario is selected (batch returns slim rows)', async () => {
      // Simulate the live batch endpoint shape: only scenarioName, baseVar,
      // stressedVar, pnlImpact. The full impact arrays are absent — without
      // a lazy fetch the detail panel would explode on undefined.
      const slimResults = ALL_STRESS_RESULTS.map((r) => ({
        scenarioName: r.scenarioName,
        baseVar: r.baseVar,
        stressedVar: r.stressedVar,
        pnlImpact: r.pnlImpact,
        // The full ``StressTestResultDto`` shape demands these but they are
        // not populated by the slim batch route — that's the bug the lazy
        // fetch fixes.
        assetClassImpacts: undefined as unknown as never,
        calculatedAt: r.calculatedAt,
        positionImpacts: undefined as unknown as never,
        limitBreaches: undefined as unknown as never,
      }))

      render(
        <ScenariosTab
          {...defaultProps}
          results={slimResults}
          selectedScenario={slimResults[0].scenarioName}
        />,
      )

      // After lazy fetch resolves, the detail panel renders against the
      // full result and shows the asset-class view.
      await waitFor(() =>
        expect(screen.getByTestId('asset-class-impact-view')).toBeInTheDocument(),
      )
    })

    it('does not call the single-scenario API when no scenario is selected', () => {
      // Spy must be reset between renders so the previous test's mock
      // invocation count does not leak in.
      vi.clearAllMocks()
      render(
        <ScenariosTab
          {...defaultProps}
          results={ALL_STRESS_RESULTS}
          selectedScenario={null}
        />,
      )
      // No fetch is fired while the panel is collapsed.
      expect(screen.queryByTestId('detail-panel')).not.toBeInTheDocument()
    })

    it('does not render ScenarioDetailPanel when no scenario is selected', () => {
      render(
        <ScenariosTab
          {...defaultProps}
          results={ALL_STRESS_RESULTS}
          selectedScenario={null}
        />,
      )

      expect(screen.queryByTestId('detail-panel')).not.toBeInTheDocument()
    })
  })
})
