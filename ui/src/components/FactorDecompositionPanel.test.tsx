import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FactorDecompositionPanel } from './FactorDecompositionPanel'
import type { FactorRiskDto } from '../types'

const sampleResult: FactorRiskDto = {
  bookId: 'BOOK-1',
  calculatedAt: '2026-03-24T10:00:00Z',
  totalVar: 50_000.0,
  systematicVar: 38_000.0,
  idiosyncraticVar: 12_000.0,
  rSquared: 0.576,
  concentrationWarning: false,
  factors: [
    {
      factorType: 'EQUITY_BETA',
      varContribution: 20_000.0,
      pctOfTotal: 40.0,
      loading: 1.12,
      loadingMethod: 'OLS',
    },
    {
      factorType: 'RATES_DURATION',
      varContribution: 10_000.0,
      pctOfTotal: 20.0,
      loading: -0.35,
      loadingMethod: 'OLS',
    },
    {
      factorType: 'CREDIT_SPREAD',
      varContribution: 8_000.0,
      pctOfTotal: 16.0,
      loading: 0.22,
      loadingMethod: 'OLS',
    },
  ],
}

describe('FactorDecompositionPanel', () => {
  it('shows loading spinner when loading', () => {
    render(<FactorDecompositionPanel result={null} loading={true} error={null} />)

    expect(screen.getByTestId('factor-risk-loading')).toBeDefined()
  })

  it('shows empty state when no result and not loading', () => {
    render(<FactorDecompositionPanel result={null} loading={false} error={null} />)

    expect(screen.getByTestId('factor-risk-empty')).toBeDefined()
  })

  it('shows error message when error is provided', () => {
    render(
      <FactorDecompositionPanel result={null} loading={false} error="Service unavailable" />,
    )

    const errorEl = screen.getByTestId('factor-risk-error')
    expect(errorEl.textContent).toContain('Service unavailable')
  })

  it('renders total VaR when result is available', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByTestId('factor-total-var').textContent).toContain('50')
  })

  it('stamps total VaR with its book scope and as-of time', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByTestId('factor-var-scope-scope')).toHaveTextContent('BOOK-1')
    expect(screen.getByTestId('factor-var-scope-asof')).toBeInTheDocument()
  })

  it('renders the calculated timestamp in ISO format, not locale format', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByText(/Calculated: 2026-03-24 \d{2}:00:00/)).toBeInTheDocument()
  })

  it('renders systematic VaR', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByTestId('factor-systematic-var').textContent).toContain('38')
  })

  it('renders idiosyncratic VaR', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByTestId('factor-idiosyncratic-var').textContent).toContain('12')
  })

  it('renders R-squared as a percentage', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByTestId('factor-r-squared').textContent).toContain('57.6')
  })

  it('renders a table row for each factor', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByTestId('factor-row-EQUITY_BETA')).toBeDefined()
    expect(screen.getByTestId('factor-row-RATES_DURATION')).toBeDefined()
    expect(screen.getByTestId('factor-row-CREDIT_SPREAD')).toBeDefined()
  })

  it('renders a stacked bar segment for each factor', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.getByTestId('factor-bar-EQUITY_BETA')).toBeDefined()
    expect(screen.getByTestId('factor-bar-RATES_DURATION')).toBeDefined()
    expect(screen.getByTestId('factor-bar-CREDIT_SPREAD')).toBeDefined()
  })

  it('does not show concentration warning when flag is false', () => {
    render(<FactorDecompositionPanel result={sampleResult} loading={false} error={null} />)

    expect(screen.queryByTestId('concentration-warning')).toBeNull()
  })

  it('shows concentration warning badge when flag is true', () => {
    const withWarning: FactorRiskDto = { ...sampleResult, concentrationWarning: true }
    render(<FactorDecompositionPanel result={withWarning} loading={false} error={null} />)

    expect(screen.getByTestId('concentration-warning')).toBeDefined()
  })

  it('renders an empty factors section gracefully when factors list is empty', () => {
    const noFactors: FactorRiskDto = { ...sampleResult, factors: [] }
    render(<FactorDecompositionPanel result={noFactors} loading={false} error={null} />)

    expect(screen.queryByTestId('factor-stacked-bar')).toBeNull()
    expect(screen.getByTestId('factor-total-var')).toBeDefined()
  })
})
