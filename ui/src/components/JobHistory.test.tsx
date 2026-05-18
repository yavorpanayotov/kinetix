import { render, screen, fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ValuationJobDetailDto } from '../types'

vi.mock('../hooks/useJobHistory')

import { useJobHistory } from '../hooks/useJobHistory'
import { JobHistory } from './JobHistory'

const mockUseJobHistory = vi.mocked(useJobHistory)

const defaultHookResult = {
  runs: [],
  chartData: null,
  expandedJobs: {} as Record<string, ValuationJobDetailDto>,
  loadingJobIds: new Set<string>(),
  loading: false,
  error: null,
  timeRange: { from: '2025-01-14T10:00:00Z', to: '2025-01-15T10:00:00Z', label: 'Last 24h' },
  setTimeRange: vi.fn(),
  toggleJob: vi.fn(),
  closeJob: vi.fn(),
  clearSelection: vi.fn(),
  refresh: vi.fn(),
  zoomIn: vi.fn(),
  resetZoom: vi.fn(),
  zoomDepth: 0,
  page: 0,
  totalCount: 0,
  totalPages: 1,
  hasNextPage: false,
  nextPage: vi.fn(),
  prevPage: vi.fn(),
  firstPage: vi.fn(),
  lastPage: vi.fn(),
  goToPage: vi.fn(),
  pageSize: 10,
  setPageSize: vi.fn(),
}

describe('JobHistory', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.setItem('kinetix:job-history-expanded', 'true')
    mockUseJobHistory.mockReturnValue(defaultHookResult)
  })

  it('renders collapsed by default with summary', () => {
    localStorage.setItem('kinetix:job-history-expanded', 'false')
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('job-history')).toBeInTheDocument()
    expect(screen.getByText('Valuation Jobs')).toBeInTheDocument()
    expect(screen.getByTestId('job-history-summary')).toBeInTheDocument()
    expect(screen.queryByTestId('job-history-table')).not.toBeInTheDocument()
  })

  it('expands table when header is clicked', () => {
    localStorage.setItem('kinetix:job-history-expanded', 'false')
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.click(screen.getByTestId('job-history-header'))

    expect(screen.getByTestId('job-history-table')).toBeInTheDocument()
    expect(screen.queryByTestId('job-history-summary')).not.toBeInTheDocument()
  })

  it('shows empty summary when no jobs exist', () => {
    localStorage.setItem('kinetix:job-history-expanded', 'false')
    render(<JobHistory bookId="book-1" />)

    const summary = screen.getByTestId('job-history-summary')
    expect(summary).toHaveTextContent('No calculations')
  })

  it('passes bookId to hook on initial render', () => {
    render(<JobHistory bookId="book-1" />)

    expect(mockUseJobHistory).toHaveBeenCalledWith('book-1')
  })

  it('shows loading state', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      loading: true,
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('job-history-loading')).toBeInTheDocument()
  })

  it('shows error message in an ErrorCard', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      error: 'Failed to load',
    })

    render(<JobHistory bookId="book-1" />)

    const errorCard = screen.getByTestId('job-history-error')
    expect(errorCard).toBeInTheDocument()
    expect(errorCard).toHaveAttribute('role', 'alert')
    expect(errorCard).toHaveTextContent('Failed to load')
  })

  it('shows job count badge', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    const toggle = screen.getByTestId('job-history-header')
    const badge = toggle.querySelector('.inline-flex.items-center')!
    expect(badge).toHaveTextContent('1')
  })

  it('shows inline job detail when a job is expanded', () => {
    const detail = {
      jobId: 'job-1',
      bookId: 'book-1',
      triggerType: 'ON_DEMAND',
      status: 'COMPLETED',
      startedAt: '2025-01-15T10:00:00Z',
      completedAt: '2025-01-15T10:00:00.150Z',
      durationMs: 150,
      calculationType: 'PARAMETRIC',
      confidenceLevel: 'CL_95',
      varValue: 5000.0,
      expectedShortfall: 6250.0,
      pvValue: 1800000.0,
      phases: [
        {
          name: 'FETCH_POSITIONS',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.020Z',
          durationMs: 20,
          details: { positionCount: '5' },
          error: null,
        },
      ],
      error: null,
      runLabel: null,
      promotedAt: null,
      promotedBy: null, manifestId: null,
    }

    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      expandedJobs: { 'job-1': detail },
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('job-detail-panel')).toBeInTheDocument()
    expect(screen.getByTestId('job-timeline')).toBeInTheDocument()
    expect(screen.getByTestId('job-detail-row-job-1')).toBeInTheDocument()
  })

  it('shows a search input when jobs are present', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('job-history-search')).toBeInTheDocument()
  })

  it('filters jobs by status', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
        {
          jobId: 'job-2',
          bookId: 'book-1',
          triggerType: 'TRADE_EVENT',
          status: 'FAILED',
          startedAt: '2025-01-15T09:00:00Z',
          completedAt: '2025-01-15T09:00:00.200Z',
          durationMs: 200,
          calculationType: 'PARAMETRIC',
          confidenceLevel: null,
          varValue: null,
          expectedShortfall: null,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.change(screen.getByTestId('job-history-search'), { target: { value: 'FAILED' } })

    expect(screen.getByTestId('job-row-job-2')).toBeInTheDocument()
    expect(screen.queryByTestId('job-row-job-1')).not.toBeInTheDocument()
  })

  it('filters jobs by trigger type', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
        {
          jobId: 'job-2',
          bookId: 'book-1',
          triggerType: 'TRADE_EVENT',
          status: 'FAILED',
          startedAt: '2025-01-15T09:00:00Z',
          completedAt: '2025-01-15T09:00:00.200Z',
          durationMs: 200,
          calculationType: 'PARAMETRIC',
          confidenceLevel: null,
          varValue: null,
          expectedShortfall: null,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.change(screen.getByTestId('job-history-search'), { target: { value: 'trade' } })

    expect(screen.getByTestId('job-row-job-2')).toBeInTheDocument()
    expect(screen.queryByTestId('job-row-job-1')).not.toBeInTheDocument()
  })

  it('updates badge count to reflect filtered results', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
        {
          jobId: 'job-2',
          bookId: 'book-1',
          triggerType: 'TRADE_EVENT',
          status: 'FAILED',
          startedAt: '2025-01-15T09:00:00Z',
          completedAt: '2025-01-15T09:00:00.200Z',
          durationMs: 200,
          calculationType: 'PARAMETRIC',
          confidenceLevel: null,
          varValue: null,
          expectedShortfall: null,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    const toggle = screen.getByTestId('job-history-header')
    const badge = () => toggle.querySelector('.inline-flex.items-center')!

    expect(badge()).toHaveTextContent('2')

    fireEvent.change(screen.getByTestId('job-history-search'), { target: { value: 'COMPLETED' } })

    expect(badge()).toHaveTextContent('1')
  })

  it('treats spaces as AND — all terms must match', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'SCHEDULED',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
        {
          jobId: 'job-2',
          bookId: 'book-1',
          triggerType: 'SCHEDULED',
          status: 'FAILED',
          startedAt: '2025-01-15T09:00:00Z',
          completedAt: '2025-01-15T09:00:00.200Z',
          durationMs: 200,
          calculationType: 'PARAMETRIC',
          confidenceLevel: null,
          varValue: null,
          expectedShortfall: null,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
        {
          jobId: 'job-3',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T08:00:00Z',
          completedAt: '2025-01-15T08:00:00.100Z',
          durationMs: 100,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 2000.0,
          expectedShortfall: 3000.0,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.change(screen.getByTestId('job-history-search'), { target: { value: 'completed scheduled' } })

    expect(screen.getByTestId('job-row-job-1')).toBeInTheDocument()
    expect(screen.queryByTestId('job-row-job-2')).not.toBeInTheDocument()
    expect(screen.queryByTestId('job-row-job-3')).not.toBeInTheDocument()
  })

  it('filters jobs by content in expanded job details', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
        {
          jobId: 'job-2',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T09:00:00Z',
          completedAt: '2025-01-15T09:00:00.200Z',
          durationMs: 200,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 3000.0,
          expectedShortfall: 4000.0,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      expandedJobs: {
        'job-1': {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          phases: [
            {
              name: 'FETCH_POSITIONS',
              status: 'COMPLETED',
              startedAt: '2025-01-15T10:00:00Z',
              completedAt: '2025-01-15T10:00:00.020Z',
              durationMs: 20,
              details: {
                positions: JSON.stringify([
                  { instrumentId: 'USD_SOFR', assetClass: 'RATES' },
                ]),
              },
              error: null,
            },
          ],
          error: null,
          runLabel: null,
          promotedAt: null,
          promotedBy: null, manifestId: null,
        },
        'job-2': {
          jobId: 'job-2',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T09:00:00Z',
          completedAt: '2025-01-15T09:00:00.200Z',
          durationMs: 200,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 3000.0,
          expectedShortfall: 4000.0,
          pvValue: null,
          phases: [
            {
              name: 'FETCH_POSITIONS',
              status: 'COMPLETED',
              startedAt: '2025-01-15T09:00:00Z',
              completedAt: '2025-01-15T09:00:00.020Z',
              durationMs: 20,
              details: {
                positions: JSON.stringify([
                  { instrumentId: 'AAPL', assetClass: 'EQUITY' },
                ]),
              },
              error: null,
            },
          ],
          error: null,
          runLabel: null,
          promotedAt: null,
          promotedBy: null, manifestId: null,
        },
      },
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.change(screen.getByTestId('job-history-search'), { target: { value: 'USD' } })

    expect(screen.getByTestId('job-row-job-1')).toBeInTheDocument()
    expect(screen.queryByTestId('job-row-job-2')).not.toBeInTheDocument()
  })

  it('renders time range selector when expanded', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('time-range-selector')).toBeInTheDocument()
  })

  it('renders timechart when jobs are present', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('job-timechart')).toBeInTheDocument()
  })

  it('does not render timechart when no jobs', () => {
    render(<JobHistory bookId="book-1" />)

    expect(screen.queryByTestId('job-timechart')).not.toBeInTheDocument()
  })

  it('filters jobs by jobId', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
        {
          jobId: 'deadbeef-cafe-babe-face-123456789abc',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T09:00:00Z',
          completedAt: '2025-01-15T09:00:00.200Z',
          durationMs: 200,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 3000.0,
          expectedShortfall: 4000.0,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.change(screen.getByTestId('job-history-search'), { target: { value: 'a1b2c3d4' } })

    expect(screen.getByTestId('job-row-a1b2c3d4-e5f6-7890-abcd-ef1234567890')).toBeInTheDocument()
    expect(screen.queryByTestId('job-row-deadbeef-cafe-babe-face-123456789abc')).not.toBeInTheDocument()
  })

  it('shows pagination controls when jobs are present', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      totalPages: 3,
      hasNextPage: true,
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('pagination-bar')).toBeInTheDocument()
    const input = screen.getByTestId('pagination-page-input') as HTMLInputElement
    expect(input.value).toBe('1')
    expect(screen.getByTestId('pagination-info')).toHaveTextContent('of 3')
  })

  it('displays total job count next to per page selector', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      totalCount: 47,
      totalPages: 5,
      hasNextPage: true,
    })

    render(<JobHistory bookId="book-1" />)

    const totalLabel = screen.getByTestId('total-count')
    expect(totalLabel).toHaveTextContent('Total: 47')
  })

  it('disables previous and first buttons on first page', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 0,
      totalPages: 3,
      hasNextPage: true,
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('pagination-first')).toBeDisabled()
    expect(screen.getByTestId('pagination-prev')).toBeDisabled()
  })

  it('disables next and last buttons when no more pages', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 1,
      totalPages: 2,
      hasNextPage: false,
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.getByTestId('pagination-next')).toBeDisabled()
    expect(screen.getByTestId('pagination-last')).toBeDisabled()
    expect(screen.getByTestId('pagination-prev')).not.toBeDisabled()
    expect(screen.getByTestId('pagination-first')).not.toBeDisabled()
  })

  it('calls all pagination handlers when buttons are clicked', () => {
    const nextPage = vi.fn()
    const prevPage = vi.fn()
    const firstPageFn = vi.fn()
    const lastPageFn = vi.fn()

    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 1,
      totalPages: 3,
      hasNextPage: true,
      nextPage,
      prevPage,
      firstPage: firstPageFn,
      lastPage: lastPageFn,
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.click(screen.getByTestId('pagination-next'))
    expect(nextPage).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByTestId('pagination-prev'))
    expect(prevPage).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByTestId('pagination-first'))
    expect(firstPageFn).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByTestId('pagination-last'))
    expect(lastPageFn).toHaveBeenCalledTimes(1)
  })

  it('does not show pagination controls when no jobs', () => {
    render(<JobHistory bookId="book-1" />)

    expect(screen.queryByTestId('pagination-bar')).not.toBeInTheDocument()
  })

  it('shows page info in badge when totalPages > 1', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 2,
      totalPages: 5,
      hasNextPage: true,
    })

    render(<JobHistory bookId="book-1" />)

    const toggle = screen.getByTestId('job-history-header')
    const badge = toggle.querySelector('.inline-flex.items-center')!
    expect(badge).toHaveTextContent('Page 3 of 5')
  })

  it('renders page input with current page number', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 0,
      totalPages: 3,
      hasNextPage: true,
    })

    render(<JobHistory bookId="book-1" />)

    const input = screen.getByTestId('pagination-page-input') as HTMLInputElement
    expect(input.value).toBe('1')
  })

  it('calls goToPage when user types a page and presses Enter', () => {
    const goToPage = vi.fn()
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 0,
      totalPages: 5,
      hasNextPage: true,
      goToPage,
    })

    render(<JobHistory bookId="book-1" />)

    const input = screen.getByTestId('pagination-page-input')
    fireEvent.change(input, { target: { value: '3' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(goToPage).toHaveBeenCalledWith(2)
  })

  it('calls goToPage on blur', () => {
    const goToPage = vi.fn()
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 0,
      totalPages: 5,
      hasNextPage: true,
      goToPage,
    })

    render(<JobHistory bookId="book-1" />)

    const input = screen.getByTestId('pagination-page-input')
    fireEvent.change(input, { target: { value: '2' } })
    fireEvent.blur(input)

    expect(goToPage).toHaveBeenCalledWith(1)
  })

  it('clamps input to valid range on submit', () => {
    const goToPage = vi.fn()
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      page: 0,
      totalPages: 3,
      hasNextPage: true,
      goToPage,
    })

    render(<JobHistory bookId="book-1" />)

    const input = screen.getByTestId('pagination-page-input')

    fireEvent.change(input, { target: { value: '0' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(goToPage).toHaveBeenCalledWith(0)

    goToPage.mockClear()

    fireEvent.change(input, { target: { value: '999' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(goToPage).toHaveBeenCalledWith(2)
  })

  it('renders page size input showing current value with dropdown trigger', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      pageSize: 10,
    })

    render(<JobHistory bookId="book-1" />)

    const input = screen.getByTestId('page-size-input') as HTMLInputElement
    expect(input).toBeInTheDocument()
    expect(input.value).toBe('10')
    expect(screen.getByTestId('page-size-toggle')).toBeInTheDocument()
  })

  it('opens preset dropdown and calls setPageSize when an option is clicked', () => {
    const setPageSize = vi.fn()
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      setPageSize,
    })

    render(<JobHistory bookId="book-1" />)

    expect(screen.queryByTestId('page-size-option-50')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('page-size-toggle'))

    expect(screen.getByTestId('page-size-option-10')).toBeInTheDocument()
    expect(screen.getByTestId('page-size-option-20')).toBeInTheDocument()
    expect(screen.getByTestId('page-size-option-50')).toBeInTheDocument()
    expect(screen.queryByTestId('page-size-option-100')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('page-size-option-50'))
    expect(setPageSize).toHaveBeenCalledWith(50)

    expect(screen.queryByTestId('page-size-option-50')).not.toBeInTheDocument()
  })

  it('calls setPageSize when user types a custom value and presses Enter', () => {
    const setPageSize = vi.fn()
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      setPageSize,
    })

    render(<JobHistory bookId="book-1" />)

    const input = screen.getByTestId('page-size-input')
    fireEvent.change(input, { target: { value: '35' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(setPageSize).toHaveBeenCalledWith(35)
  })

  it('reflects current pageSize in the input value', () => {
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: '2025-01-15T10:00:00.150Z',
          durationMs: 150,
          calculationType: 'PARAMETRIC',
          confidenceLevel: 'CL_95',
          varValue: 5000.0,
          expectedShortfall: 6250.0,
          pvValue: 1800000.0,
          delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      pageSize: 50,
    })

    render(<JobHistory bookId="book-1" />)

    const input = screen.getByTestId('page-size-input') as HTMLInputElement
    expect(input.value).toBe('50')
  })

  it('calls closeJob when close detail button is clicked', () => {
    const closeJob = vi.fn()
    mockUseJobHistory.mockReturnValue({
      ...defaultHookResult,
      runs: [
        {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: null,
          durationMs: null,
          calculationType: null,
          confidenceLevel: null,
          varValue: null,
          expectedShortfall: null,
          pvValue: null,
          delta: null, gamma: null, vega: null, theta: null, rho: null, runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
        },
      ],
      expandedJobs: {
        'job-1': {
          jobId: 'job-1',
          bookId: 'book-1',
          triggerType: 'ON_DEMAND',
          status: 'COMPLETED',
          startedAt: '2025-01-15T10:00:00Z',
          completedAt: null,
          durationMs: null,
          calculationType: null,
          confidenceLevel: null,
          varValue: null,
          expectedShortfall: null,
          pvValue: null,
          phases: [],
          error: null,
          runLabel: null,
          promotedAt: null,
          promotedBy: null, manifestId: null,
        },
      },
      closeJob,
    })

    render(<JobHistory bookId="book-1" />)

    fireEvent.click(screen.getByTestId('close-detail-job-1'))

    expect(closeJob).toHaveBeenCalledWith('job-1')
  })
})
