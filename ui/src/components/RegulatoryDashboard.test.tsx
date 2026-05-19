import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { FrtbResultDto } from '../types'
import { RegulatoryDashboard } from './RegulatoryDashboard'

const frtbResult: FrtbResultDto = {
  bookId: 'book-1',
  sbmCharges: [
    { riskClass: 'GIRR', deltaCharge: '1500.00', vegaCharge: '1000.00', curvatureCharge: '112.50', totalCharge: '2612.50' },
    { riskClass: 'CSR_NON_SEC', deltaCharge: '3000.00', vegaCharge: '2000.00', curvatureCharge: '450.00', totalCharge: '5450.00' },
    { riskClass: 'CSR_SEC_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'CSR_SEC_NON_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'EQUITY', deltaCharge: '40000.00', vegaCharge: '30000.00', curvatureCharge: '4000.00', totalCharge: '74000.00' },
    { riskClass: 'COMMODITY', deltaCharge: '6000.00', vegaCharge: '4000.00', curvatureCharge: '450.00', totalCharge: '10450.00' },
    { riskClass: 'FX', deltaCharge: '6000.00', vegaCharge: '4800.00', curvatureCharge: '300.00', totalCharge: '11100.00' },
  ],
  totalSbmCharge: '76612.50',
  grossJtd: '5400.00',
  hedgeBenefit: '0.00',
  netDrc: '5400.00',
  exoticNotional: '400000.00',
  otherNotional: '1700000.00',
  totalRrao: '5700.00',
  totalCapitalCharge: '87712.50',
  calculatedAt: '2025-01-15T10:00:00Z',
}

describe('RegulatoryDashboard', () => {
  it('renders capital charge summary stats', () => {
    render(
      <RegulatoryDashboard
        result={frtbResult}
        loading={false}
        error={null}
        onCalculate={() => {}}
        onDownloadCsv={() => {}}
        onDownloadXbrl={() => {}}
      />,
    )

    expect(screen.getByTestId('capital-summary')).toBeInTheDocument()
    expect(screen.getByTestId('regulatory-results')).toBeInTheDocument()
  })

  it('renders the SbM breakdown table with all seven FRTB risk classes', () => {
    render(
      <RegulatoryDashboard
        result={frtbResult}
        loading={false}
        error={null}
        onCalculate={() => {}}
        onDownloadCsv={() => {}}
        onDownloadXbrl={() => {}}
      />,
    )

    const table = screen.getByTestId('frtb-sbm-table')
    expect(table).toBeInTheDocument()
    // The table reads as a fixed regulatory schedule — all seven SbM risk
    // classes appear, even those that net to zero.
    const rows = table.querySelectorAll('tbody tr')
    expect(rows.length).toBe(7)
  })

  it('renders report download buttons', () => {
    render(
      <RegulatoryDashboard
        result={frtbResult}
        loading={false}
        error={null}
        onCalculate={() => {}}
        onDownloadCsv={() => {}}
        onDownloadXbrl={() => {}}
      />,
    )

    expect(screen.getByTestId('download-csv-btn')).toBeInTheDocument()
    expect(screen.getByTestId('download-xbrl-btn')).toBeInTheDocument()
  })

  it('shows loading state', () => {
    render(
      <RegulatoryDashboard
        result={null}
        loading={true}
        error={null}
        onCalculate={() => {}}
        onDownloadCsv={() => {}}
        onDownloadXbrl={() => {}}
      />,
    )

    expect(screen.getByTestId('regulatory-loading')).toBeInTheDocument()
  })

  it('shows error state', () => {
    render(
      <RegulatoryDashboard
        result={null}
        loading={false}
        error="Calculation failed"
        onCalculate={() => {}}
        onDownloadCsv={() => {}}
        onDownloadXbrl={() => {}}
      />,
    )

    expect(screen.getByTestId('regulatory-error')).toBeInTheDocument()
    expect(screen.getByTestId('regulatory-error')).toHaveTextContent('Calculation failed')
  })
})
