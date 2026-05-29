import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ValuationJobSummaryDto, ValuationJobDetailDto } from '../types'
import { JobHistoryTable } from './JobHistoryTable'

const baseJob: ValuationJobSummaryDto = {
  jobId: 'job-1',
  bookId: 'book-1',
  triggerType: 'ON_DEMAND',
  status: 'COMPLETED',
  startedAt: '2025-01-15T10:38:20Z',
  completedAt: '2025-01-15T10:38:20.150Z',
  durationMs: 150,
  calculationType: 'PARAMETRIC',
  confidenceLevel: 'CL_95',
  varValue: 5000.0,
  expectedShortfall: 6250.0,
  pvValue: 1800000.0,
  delta: 0, gamma: 0, vega: 0, theta: 0, rho: 0,
  runLabel: null, promotedAt: null, promotedBy: null, manifestId: null,
}

const defaultProps = {
  expandedJobs: {} as Record<string, ValuationJobDetailDto>,
  loadingJobIds: new Set<string>(),
  onSelectJob: () => {},
  onCloseJob: () => {},
}

describe('ValuationJobsTable dedupe', () => {
  it('collapses jobs with identical ts/varValue/esValue/pvValue into one row with a (x3) badge', () => {
    const runs: ValuationJobSummaryDto[] = [
      { ...baseJob, jobId: 'job-a' },
      { ...baseJob, jobId: 'job-b' },
      { ...baseJob, jobId: 'job-c' },
    ]

    render(<JobHistoryTable runs={runs} {...defaultProps} />)

    // Only one of the three identical jobs is rendered as a row.
    const rows = screen.getAllByTestId(/^job-row-/)
    expect(rows).toHaveLength(1)

    // A count badge indicates how many identical jobs collapsed.
    expect(screen.getByTestId('dedupe-count-job-a')).toHaveTextContent('(x3)')
  })

  it('does not collapse jobs that differ in any of ts/varValue/esValue/pvValue', () => {
    const runs: ValuationJobSummaryDto[] = [
      { ...baseJob, jobId: 'job-a' },
      { ...baseJob, jobId: 'job-b', varValue: 9999.0 },
      { ...baseJob, jobId: 'job-c', startedAt: '2025-01-15T10:39:00Z' },
    ]

    render(<JobHistoryTable runs={runs} {...defaultProps} />)

    const rows = screen.getAllByTestId(/^job-row-/)
    expect(rows).toHaveLength(3)
    expect(screen.queryByTestId(/^dedupe-count-/)).not.toBeInTheDocument()
  })

  it('shows no count badge when a row represents a single job', () => {
    render(<JobHistoryTable runs={[{ ...baseJob, jobId: 'solo' }]} {...defaultProps} />)

    expect(screen.getByTestId('job-row-solo')).toBeInTheDocument()
    expect(screen.queryByTestId('dedupe-count-solo')).not.toBeInTheDocument()
  })
})
