import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FrtbResultDto } from '../types'

vi.mock('../hooks/useRegulatory')

import { RegulatoryTab } from './RegulatoryTab'
import { useRegulatory } from '../hooks/useRegulatory'

const mockUseRegulatory = vi.mocked(useRegulatory)

const frtbResult: FrtbResultDto = {
  bookId: 'balanced-income',
  sbmCharges: [
    { riskClass: 'GIRR', deltaCharge: '19.86', vegaCharge: '180532.51', curvatureCharge: '2030.99', totalCharge: '182583.36' },
    { riskClass: 'CSR_NON_SEC', deltaCharge: '16247.93', vegaCharge: '7221.30', curvatureCharge: '243.72', totalCharge: '23712.95' },
    { riskClass: 'CSR_SEC_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'CSR_SEC_NON_CTP', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'EQUITY', deltaCharge: '227231.14', vegaCharge: '127817.52', curvatureCharge: '22723.11', totalCharge: '377771.78' },
    { riskClass: 'COMMODITY', deltaCharge: '0.00', vegaCharge: '0.00', curvatureCharge: '0.00', totalCharge: '0.00' },
    { riskClass: 'FX', deltaCharge: '41869.98', vegaCharge: '26796.79', curvatureCharge: '2093.50', totalCharge: '70760.27' },
  ],
  totalSbmCharge: '635674.38',
  grossJtd: '162486.91',
  hedgeBenefit: '3.83',
  netDrc: '162483.09',
  exoticNotional: '850.20',
  otherNotional: '27919327.83',
  totalRrao: '27927.83',
  totalCapitalCharge: '826085.29',
  calculatedAt: '2026-05-19T13:22:48Z',
}

describe('RegulatoryTab', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockUseRegulatory.mockReturnValue({
      result: null,
      loading: false,
      error: null,
      calculate: vi.fn(),
      downloadCsv: vi.fn(),
      downloadXbrl: vi.fn(),
    })
  })

  it('calls useRegulatory with the given bookId', () => {
    render(<RegulatoryTab bookId="book-1" />)

    expect(mockUseRegulatory).toHaveBeenCalledWith('book-1')
  })

  it('renders the regulatory dashboard', () => {
    render(<RegulatoryTab bookId="book-1" />)

    expect(screen.getByTestId('regulatory-dashboard')).toBeInTheDocument()
  })

  it('shows an empty-state prompt before a calculation runs', () => {
    render(<RegulatoryTab bookId="book-1" />)

    expect(screen.getByTestId('frtb-empty-state')).toHaveTextContent(/Calculate FRTB/i)
  })

  it('keeps the Calculate FRTB / Download CSV / Download XBRL buttons in place', () => {
    render(<RegulatoryTab bookId="book-1" />)

    expect(screen.getByTestId('frtb-calculate-btn')).toBeInTheDocument()
    expect(screen.getByTestId('download-csv-btn')).toBeInTheDocument()
    expect(screen.getByTestId('download-xbrl-btn')).toBeInTheDocument()
  })

  describe('with a successful FRTB result', () => {
    beforeEach(() => {
      mockUseRegulatory.mockReturnValue({
        result: frtbResult,
        loading: false,
        error: null,
        calculate: vi.fn(),
        downloadCsv: vi.fn(),
        downloadXbrl: vi.fn(),
      })
    })

    it('renders an SBM-charges-by-risk-class table with all 7 risk classes and the expected columns', () => {
      render(<RegulatoryTab bookId="balanced-income" />)

      const table = screen.getByTestId('frtb-sbm-table')
      expect(table).toBeInTheDocument()

      // Header columns
      const headerRow = within(table).getAllByRole('row')[0]
      const headers = within(headerRow).getAllByRole('columnheader').map((c) => c.textContent)
      expect(headers).toEqual(
        expect.arrayContaining(['Risk Class', 'Delta', 'Vega', 'Curvature', 'Total']),
      )

      // All seven risk classes rendered, even those with zero charges.
      const riskClasses = ['GIRR', 'CSR_NON_SEC', 'CSR_SEC_CTP', 'CSR_SEC_NON_CTP', 'EQUITY', 'COMMODITY', 'FX']
      for (const rc of riskClasses) {
        expect(screen.getByTestId(`frtb-row-${rc}`)).toBeInTheDocument()
      }
    })

    it('formats the EQUITY row delta and total with thousands separators', () => {
      render(<RegulatoryTab bookId="balanced-income" />)

      const row = screen.getByTestId('frtb-row-EQUITY')
      expect(row).toHaveTextContent('$227,231.14')
      expect(row).toHaveTextContent('$377,771.78')
    })

    it('renders a totals strip with Total SBM, Gross JTD, Net DRC, Total RRAO, and Total Capital Charge', () => {
      render(<RegulatoryTab bookId="balanced-income" />)

      const totals = screen.getByTestId('frtb-totals')
      expect(totals).toBeInTheDocument()
      expect(totals).toHaveTextContent(/Total SBM/i)
      expect(totals).toHaveTextContent(/Gross JTD/i)
      expect(totals).toHaveTextContent(/Net DRC/i)
      expect(totals).toHaveTextContent(/Total RRAO/i)
      expect(totals).toHaveTextContent(/Total Capital Charge/i)
      // Dollar amounts formatted with thousands separators
      expect(totals).toHaveTextContent('$635,674.38')
      expect(totals).toHaveTextContent('$162,486.91')
      expect(totals).toHaveTextContent('$162,483.09')
      expect(totals).toHaveTextContent('$27,927.83')
      expect(totals).toHaveTextContent('$826,085.29')
    })

    it('renders a "Calculated at <timestamp>" stamp', () => {
      render(<RegulatoryTab bookId="balanced-income" />)

      const stamp = screen.getByTestId('frtb-calculated-at')
      expect(stamp).toBeInTheDocument()
      expect(stamp.textContent).toMatch(/Calculated at/i)
      // The timestamp itself surfaces in some readable form — assert year/month appear.
      expect(stamp.textContent).toMatch(/2026/)
    })

    it('keeps the action buttons visible alongside the results', () => {
      render(<RegulatoryTab bookId="balanced-income" />)

      expect(screen.getByTestId('frtb-calculate-btn')).toBeInTheDocument()
      expect(screen.getByTestId('download-csv-btn')).toBeInTheDocument()
      expect(screen.getByTestId('download-xbrl-btn')).toBeInTheDocument()
    })
  })
})
