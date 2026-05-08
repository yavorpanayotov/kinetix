import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { CounterpartyRiskDashboard } from './CounterpartyRiskDashboard'

vi.mock('../hooks/useCounterpartyRisk', () => ({
  useCounterpartyRisk: vi.fn(),
}))

import { useCounterpartyRisk } from '../hooks/useCounterpartyRisk'

const mockUseCounterpartyRisk = vi.mocked(useCounterpartyRisk)

const SAMPLE_EXPOSURE = {
  counterpartyId: 'CP-GS',
  calculatedAt: '2026-03-24T10:00:00Z',
  currentNetExposure: 2_000_000,
  peakPfe: 1_800_000,
  cva: 12_500,
  cvaEstimated: false,
  currency: 'USD',
  pfeProfile: [
    { tenor: '1Y', tenorYears: 1, expectedExposure: 1_500_000, pfe95: 1_800_000, pfe99: 2_000_000 },
    { tenor: '2Y', tenorYears: 2, expectedExposure: 1_200_000, pfe95: 1_500_000, pfe99: 1_700_000 },
  ],
}

const HIGH_EXPOSURE = {
  ...SAMPLE_EXPOSURE,
  counterpartyId: 'CP-JPM',
  currentNetExposure: 6_000_000,
  peakPfe: 7_000_000,
  cva: null,
  cvaEstimated: false,
}

// 12-counterparty fleet: 9 small (each 100k * (i+1) → 100k..900k), 3 large
// (5M, 6M, 8M). Top decile threshold lands on the 3rd-from-top.
function buildFleet() {
  const tail = Array.from({ length: 9 }, (_, i) => ({
    ...SAMPLE_EXPOSURE,
    counterpartyId: `CP-SMALL-${i}`,
    currentNetExposure: 100_000 * (i + 1),
  }))
  const top = [
    { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-BIG-1', currentNetExposure: 5_000_000 },
    { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-BIG-2', currentNetExposure: 6_000_000 },
    { ...SAMPLE_EXPOSURE, counterpartyId: 'CP-BIG-3', currentNetExposure: 8_000_000 },
  ]
  return [...tail, ...top]
}

const defaultHook = {
  exposures: [],
  selected: null,
  history: [],
  loading: false,
  computing: false,
  error: null,
  selectCounterparty: vi.fn(),
  computePFE: vi.fn(),
  computeCVA: vi.fn(),
  refresh: vi.fn(),
}

describe('CounterpartyRiskDashboard', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockUseCounterpartyRisk.mockReturnValue(defaultHook)
  })

  it('renders the dashboard container', () => {
    render(<CounterpartyRiskDashboard />)
    expect(screen.getByTestId('counterparty-risk-dashboard')).toBeInTheDocument()
  })

  it('shows empty state when there are no exposures', () => {
    render(<CounterpartyRiskDashboard />)
    expect(screen.getByTestId('counterparty-empty-state')).toBeInTheDocument()
  })

  it('renders counterparty rows when exposures are loaded', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE, HIGH_EXPOSURE],
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('counterparty-row-CP-GS')).toBeInTheDocument()
    expect(screen.getByTestId('counterparty-row-CP-JPM')).toBeInTheDocument()
  })

  it('flags top-decile exposures (percentile threshold) when the universe is large enough', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    // 12 CPs, 90th percentile lands at index floor(12*0.9)=10, value = 6M.
    // CP-BIG-2 (6M) and CP-BIG-3 (8M) are flagged; CP-BIG-1 (5M) is not.
    expect(screen.getByTestId('wwf-badge-CP-BIG-2')).toBeInTheDocument()
    expect(screen.getByTestId('wwf-badge-CP-BIG-3')).toBeInTheDocument()
    expect(screen.queryByTestId('wwf-badge-CP-BIG-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('wwf-badge-CP-SMALL-0')).not.toBeInTheDocument()
  })

  it('does not flag any counterparty when the universe is too small for a percentile', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [HIGH_EXPOSURE],
    })

    render(<CounterpartyRiskDashboard />)

    // Below 10 counterparties the percentile threshold disengages.
    expect(screen.queryByTestId('wwf-flag-CP-JPM')).not.toBeInTheDocument()
    expect(screen.queryByTestId('wwf-badge-CP-JPM')).not.toBeInTheDocument()
  })

  it('filters rows when the user types a search query', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    const search = screen.getByTestId('counterparty-search')
    fireEvent.change(search, { target: { value: 'BIG' } })

    expect(screen.getByTestId('counterparty-row-CP-BIG-1')).toBeInTheDocument()
    expect(screen.getByTestId('counterparty-row-CP-BIG-2')).toBeInTheDocument()
    expect(screen.queryByTestId('counterparty-row-CP-SMALL-0')).not.toBeInTheDocument()
  })

  it('shows a no-results message when the search query matches nothing', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.change(screen.getByTestId('counterparty-search'), { target: { value: 'XYZ' } })

    expect(screen.getByTestId('counterparty-no-search-results')).toBeInTheDocument()
  })

  it('default sort is by net exposure descending', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    const rows = screen.getAllByTestId(/^counterparty-row-/)
    expect(rows[0]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-3')
    expect(rows[1]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-2')
    expect(rows[2]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-1')
  })

  it('clicking a column header toggles the sort direction', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    // Click net exposure header — already active desc — should flip to asc.
    fireEvent.click(screen.getByTestId('sort-header-currentNetExposure'))

    const rows = screen.getAllByTestId(/^counterparty-row-/)
    expect(rows[0]).toHaveAttribute('data-testid', 'counterparty-row-CP-SMALL-0')
  })

  it('clicking a different column switches sort to that column', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: buildFleet(),
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('sort-header-counterpartyId'))

    const rows = screen.getAllByTestId(/^counterparty-row-/)
    // CP-BIG-1 is the alphabetically smallest among 'CP-BIG-*' / 'CP-SMALL-*'.
    expect(rows[0]).toHaveAttribute('data-testid', 'counterparty-row-CP-BIG-1')
  })

  it('calls selectCounterparty when a row is clicked', async () => {
    const selectCounterparty = vi.fn()
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selectCounterparty,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('counterparty-row-CP-GS'))

    await waitFor(() => {
      expect(selectCounterparty).toHaveBeenCalledWith('CP-GS')
    })
  })

  it('shows detail panel with metrics when counterparty is selected', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('counterparty-detail-panel')).toBeInTheDocument()
    expect(screen.getByTestId('detail-net-exposure')).toBeInTheDocument()
    expect(screen.getByTestId('detail-peak-pfe')).toBeInTheDocument()
    expect(screen.getByTestId('detail-cva')).toBeInTheDocument()
  })

  it('shows PFE chart when profile is available', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('pfe-chart')).toBeInTheDocument()
  })

  it('shows empty PFE chart message when no profile', () => {
    const noProfile = { ...SAMPLE_EXPOSURE, pfeProfile: [] }
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [noProfile],
      selected: noProfile,
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('pfe-chart-empty')).toBeInTheDocument()
  })

  it('calls computePFE when Compute PFE button is clicked', async () => {
    const computePFE = vi.fn().mockResolvedValue(undefined)
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
      computePFE,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('compute-pfe-button'))

    await waitFor(() => {
      expect(computePFE).toHaveBeenCalledWith('CP-GS')
    })
  })

  it('calls computeCVA when Compute CVA button is clicked', async () => {
    const computeCVA = vi.fn().mockResolvedValue(undefined)
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      selected: SAMPLE_EXPOSURE,
      computeCVA,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('compute-cva-button'))

    await waitFor(() => {
      expect(computeCVA).toHaveBeenCalledWith('CP-GS')
    })
  })

  it('shows error message when error is set', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      error: 'Failed to load exposures',
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('counterparty-error')).toHaveTextContent('Failed to load exposures')
  })

  it('calls refresh when Refresh button is clicked', () => {
    const refresh = vi.fn()
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
      refresh,
    })

    render(<CounterpartyRiskDashboard />)

    fireEvent.click(screen.getByTestId('refresh-exposures-button'))

    expect(refresh).toHaveBeenCalled()
  })

  it('shows placeholder when no counterparty is selected', () => {
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [SAMPLE_EXPOSURE],
    })

    render(<CounterpartyRiskDashboard />)

    expect(screen.getByTestId('detail-panel-placeholder')).toBeInTheDocument()
  })

  it('disables CVA button when no PFE profile is available', () => {
    const noProfile = { ...SAMPLE_EXPOSURE, pfeProfile: [] }
    mockUseCounterpartyRisk.mockReturnValue({
      ...defaultHook,
      exposures: [noProfile],
      selected: noProfile,
    })

    render(<CounterpartyRiskDashboard />)

    const cvaButton = screen.getByTestId('compute-cva-button')
    expect(cvaButton).toBeDisabled()
  })
})
