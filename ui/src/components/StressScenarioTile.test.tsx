import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { CannedStressResultDto } from '../types'
import { StressScenarioTile } from './StressScenarioTile'

const sampleResult: CannedStressResultDto = {
  bookId: 'BOOK-RATES-01',
  scenario: '+100BPS_PARALLEL',
  deltaPv: '-12345.67',
  asOf: '2026-05-26T09:00:00Z',
}

describe('StressScenarioTile', () => {
  it('renders the scenario name, delta-PV, and as-of timestamp', () => {
    render(<StressScenarioTile result={sampleResult} loading={false} />)

    expect(screen.getByTestId('stress-scenario-tile')).toBeInTheDocument()
    expect(screen.getByTestId('stress-scenario-name')).toHaveTextContent('+100BPS_PARALLEL')
    expect(screen.getByTestId('stress-scenario-delta-pv')).toHaveTextContent(/-\$?12,?345/)
    expect(screen.getByTestId('stress-scenario-as-of')).toBeInTheDocument()
  })

  it('flags a negative delta-PV in red to signal a loss', () => {
    render(<StressScenarioTile result={sampleResult} loading={false} />)

    const deltaPv = screen.getByTestId('stress-scenario-delta-pv')
    expect(deltaPv.className).toMatch(/text-red/)
  })

  it('renders a positive delta-PV without the loss colour', () => {
    const positiveResult: CannedStressResultDto = { ...sampleResult, deltaPv: '4321.00' }
    render(<StressScenarioTile result={positiveResult} loading={false} />)

    const deltaPv = screen.getByTestId('stress-scenario-delta-pv')
    expect(deltaPv.className).not.toMatch(/text-red/)
  })

  it('renders a loading placeholder when no result is available yet', () => {
    render(<StressScenarioTile result={null} loading={true} />)

    expect(screen.getByTestId('stress-scenario-tile')).toBeInTheDocument()
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('renders an empty-state message when no result has been seeded', () => {
    render(<StressScenarioTile result={null} loading={false} />)

    expect(screen.getByTestId('stress-scenario-tile')).toBeInTheDocument()
    expect(screen.getByText(/no canned stress scenario/i)).toBeInTheDocument()
  })

  it('shows a human-readable label for +100BPS_PARALLEL', () => {
    render(<StressScenarioTile result={sampleResult} loading={false} />)
    // Tile should make the canned scenario meaning obvious to a CRO who
    // doesn't recognise the raw scenario name.
    expect(screen.getByText(/parallel rates shock/i)).toBeInTheDocument()
  })
})
