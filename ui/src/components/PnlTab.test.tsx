import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../hooks/usePnlAttribution')
vi.mock('../hooks/useSodBaseline')
vi.mock('../hooks/useIntradayPnlStream', () => ({
  useIntradayPnlStream: () => ({ snapshots: [], latest: null, connected: false }),
}))
vi.mock('../hooks/useIntradayPnlSeriesWithFallback', () => ({
  useIntradayPnlSeriesWithFallback: () => ({ snapshots: [], loading: false, error: null, sessionDate: null }),
}))
vi.mock('../hooks/useIntradayVaRTimelineWithFallback', () => ({
  useIntradayVaRTimelineWithFallback: () => ({ varPoints: [], tradeAnnotations: [], loading: false, error: null, sessionDate: null }),
}))

import { PnlTab } from './PnlTab'
import { usePnlAttribution } from '../hooks/usePnlAttribution'
import { useSodBaseline } from '../hooks/useSodBaseline'

const mockUsePnlAttribution = vi.mocked(usePnlAttribution)
const mockUseSodBaseline = vi.mocked(useSodBaseline)

const pnlAttributionData = {
  bookId: 'book-1',
  date: '2025-01-15',
  totalPnl: '15000.00',
  deltaPnl: '8000.00',
  gammaPnl: '2500.00',
  vegaPnl: '3000.00',
  thetaPnl: '-1500.00',
  rhoPnl: '500.00',
  unexplainedPnl: '2500.00',
  positionAttributions: [
    {
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      totalPnl: '8000.00',
      deltaPnl: '5000.00',
      gammaPnl: '1200.00',
      vegaPnl: '1500.00',
      thetaPnl: '-800.00',
      rhoPnl: '300.00',
      unexplainedPnl: '800.00',
    },
  ],
  calculatedAt: '2025-01-15T10:30:00Z',
}

const defaultSodBaseline = {
  status: null,
  loading: false,
  error: null,
  creating: false,
  resetting: false,
  computing: false,
  createSnapshot: vi.fn(),
  resetBaseline: vi.fn(),
  computeAttribution: vi.fn().mockResolvedValue(null),
  refresh: vi.fn(),
}

describe('PnlTab', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockUseSodBaseline.mockReturnValue(defaultSodBaseline)
  })

  it('calls usePnlAttribution with the given bookId', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: null,
      loading: false,
      error: null,
    })

    render(<PnlTab bookId="book-1" />)

    expect(mockUsePnlAttribution).toHaveBeenCalledWith('book-1')
  })

  it('renders waterfall chart and attribution table when data is available', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: pnlAttributionData,
      loading: false,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      status: {
        exists: true,
        baselineDate: '2025-01-15',
        snapshotType: 'MANUAL',
        createdAt: '2025-01-15T08:00:00Z',
        sourceJobId: null,
        calculationType: null,
      },
    })

    render(<PnlTab bookId="book-1" />)

    expect(screen.getByTestId('waterfall-chart')).toBeInTheDocument()
    expect(screen.getByTestId('attribution-table')).toBeInTheDocument()
  })

  it('shows loading state', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: null,
      loading: true,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      loading: true,
    })

    render(<PnlTab bookId="book-1" />)

    expect(screen.getByTestId('pnl-loading')).toBeInTheDocument()
  })

  it('shows SOD baseline warning even when P&L fetch fails', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: null,
      loading: false,
      error: 'Failed to load',
    })

    render(<PnlTab bookId="book-1" />)

    expect(screen.getByTestId('sod-baseline-warning')).toBeInTheDocument()
  })

  it('shows SOD baseline warning when no baseline exists', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: null,
      loading: false,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      status: {
        exists: false,
        baselineDate: null,
        snapshotType: null,
        createdAt: null,
        sourceJobId: null,
        calculationType: null,
      },
    })

    render(<PnlTab bookId="book-1" />)

    expect(screen.getByTestId('sod-baseline-warning')).toBeInTheDocument()
    expect(screen.getByTestId('pnl-empty')).toBeInTheDocument()
  })

  it('shows compute prompt when baseline exists but no P&L data', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: null,
      loading: false,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      status: {
        exists: true,
        baselineDate: '2025-01-15',
        snapshotType: 'MANUAL',
        createdAt: '2025-01-15T08:00:00Z',
        sourceJobId: null,
        calculationType: null,
      },
    })

    render(<PnlTab bookId="book-1" />)

    expect(screen.getByTestId('sod-baseline-active')).toBeInTheDocument()
    expect(screen.getByTestId('pnl-compute-button')).toBeInTheDocument()
  })

  it('shows confirmation dialog when reset button is clicked', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: pnlAttributionData,
      loading: false,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      status: {
        exists: true,
        baselineDate: '2025-01-15',
        snapshotType: 'MANUAL',
        createdAt: '2025-01-15T08:00:00Z',
        sourceJobId: null,
        calculationType: null,
      },
    })

    render(<PnlTab bookId="book-1" />)

    fireEvent.click(screen.getByTestId('sod-reset-button'))

    expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
    expect(screen.getByTestId('confirm-dialog-confirm')).toBeInTheDocument()
  })

  it('shows job metadata in reset dialog when available', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: pnlAttributionData,
      loading: false,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      status: {
        exists: true,
        baselineDate: '2025-01-15',
        snapshotType: 'MANUAL',
        createdAt: '2025-01-15T08:00:00Z',
        sourceJobId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        calculationType: 'PARAMETRIC',
      },
    })

    render(<PnlTab bookId="book-1" />)

    fireEvent.click(screen.getByTestId('sod-reset-button'))

    expect(screen.getByTestId('reset-dialog-metadata')).toBeInTheDocument()
    expect(screen.getByText('Type: PARAMETRIC')).toBeInTheDocument()
    expect(screen.getByText('aaaaaaaa')).toBeInTheDocument()
  })

  it('shows CSV export button when P&L data is available', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: pnlAttributionData,
      loading: false,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      status: {
        exists: true,
        baselineDate: '2025-01-15',
        snapshotType: 'MANUAL',
        createdAt: '2025-01-15T08:00:00Z',
        sourceJobId: null,
        calculationType: null,
      },
    })

    render(<PnlTab bookId="book-1" />)

    expect(screen.getByTestId('pnl-csv-export')).toBeInTheDocument()
  })

  it('shows SOD error when sod hook returns an error', () => {
    mockUsePnlAttribution.mockReturnValue({
      data: null,
      loading: false,
      error: null,
    })
    mockUseSodBaseline.mockReturnValue({
      ...defaultSodBaseline,
      status: {
        exists: false,
        baselineDate: null,
        snapshotType: null,
        createdAt: null,
        sourceJobId: null,
        calculationType: null,
      },
      error: 'SOD service unavailable',
    })

    render(<PnlTab bookId="book-1" />)

    expect(screen.getByTestId('sod-error')).toBeInTheDocument()
    expect(screen.getByText('SOD service unavailable')).toBeInTheDocument()
  })
})
