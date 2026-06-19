import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { VaRResultDto } from '../../types'
import type { UseVaRResult } from '../../hooks/useVaR'
import type { UseVarLimitResult } from '../../hooks/useVarLimit'

vi.mock('../../hooks/useVaR')
vi.mock('../../hooks/useVarLimit')

import { MobileRiskView } from './MobileRiskView'
import { useVaR } from '../../hooks/useVaR'
import { useVarLimit } from '../../hooks/useVarLimit'

const mockUseVaR = vi.mocked(useVaR)
const mockUseVarLimit = vi.mocked(useVarLimit)

function varResult(overrides: Partial<VaRResultDto> = {}): VaRResultDto {
  return {
    bookId: 'book-1',
    calculationType: 'PARAMETRIC',
    confidenceLevel: 'CL_95',
    varValue: '500000',
    expectedShortfall: '600000',
    componentBreakdown: [],
    // Recent so the freshness banner renders.
    calculatedAt: new Date().toISOString(),
    ...overrides,
  }
}

function setVaR(overrides: Partial<UseVaRResult> = {}) {
  mockUseVaR.mockReturnValue({
    varResult: varResult(),
    greeksResult: null,
    history: [],
    filteredHistory: [],
    loading: false,
    historyLoading: false,
    refreshing: false,
    error: null,
    errorTransient: false,
    refresh: vi.fn(),
    timeRange: { from: '', to: '', label: '' },
    setTimeRange: vi.fn(),
    selectedConfidenceLevel: 'CL_95',
    setSelectedConfidenceLevel: vi.fn(),
    zoomIn: vi.fn(),
    resetZoom: vi.fn(),
    zoomDepth: 0,
    isLive: true,
    ...overrides,
  })
}

function setVarLimit(overrides: Partial<UseVarLimitResult> = {}) {
  mockUseVarLimit.mockReturnValue({
    varLimit: 1_000_000,
    loading: false,
    ...overrides,
  })
}

describe('MobileRiskView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setVaR()
    setVarLimit()
  })

  it('renders the VaR value, limit, and utilisation when data is present', () => {
    render(<MobileRiskView bookId="book-1" />)

    const view = screen.getByTestId('mobile-risk-view')
    expect(view).toBeInTheDocument()
    // 500k VaR against a 1m limit => 50% utilisation.
    expect(screen.getByTestId('mobile-risk-var-value')).toHaveTextContent('$500,000')
    expect(screen.getByTestId('mobile-risk-var-limit')).toHaveTextContent('$1,000,000')
    expect(screen.getByTestId('mobile-risk-utilisation')).toHaveTextContent('50.0%')
  })

  it('renders a utilisation bar reflecting the utilisation ratio', () => {
    render(<MobileRiskView bookId="book-1" />)

    const bar = screen.getByTestId('mobile-risk-utilisation-bar')
    expect(bar).toHaveStyle({ width: '50%' })
  })

  it('does not apply breach styling when utilisation is below the threshold', () => {
    setVaR({ varResult: varResult({ varValue: '500000' }) })
    setVarLimit({ varLimit: 1_000_000 })

    render(<MobileRiskView bookId="book-1" />)

    const view = screen.getByTestId('mobile-risk-view')
    expect(view).toHaveAttribute('data-breach', 'false')
    expect(screen.queryByTestId('mobile-risk-breach-badge')).not.toBeInTheDocument()
  })

  it('applies breach styling when utilisation exceeds the threshold', () => {
    // 850k against 1m => 85% utilisation, above the 0.8 threshold.
    setVaR({ varResult: varResult({ varValue: '850000' }) })
    setVarLimit({ varLimit: 1_000_000 })

    render(<MobileRiskView bookId="book-1" />)

    const view = screen.getByTestId('mobile-risk-view')
    expect(view).toHaveAttribute('data-breach', 'true')
    expect(screen.getByTestId('mobile-risk-breach-badge')).toBeInTheDocument()
    expect(screen.getByTestId('mobile-risk-utilisation-bar').className).toContain('red')
  })

  it('shows a loading state while VaR is loading', () => {
    setVaR({ varResult: null, loading: true })

    render(<MobileRiskView bookId="book-1" />)

    expect(screen.getByTestId('mobile-risk-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-risk-var-value')).not.toBeInTheDocument()
  })

  it('shows an empty state when there is no VaR data', () => {
    setVaR({ varResult: null, loading: false })

    render(<MobileRiskView bookId="book-1" />)

    expect(screen.getByTestId('mobile-risk-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-risk-var-value')).not.toBeInTheDocument()
  })

  it('renders the freshness banner fed by the VaR calculated-at timestamp', () => {
    render(<MobileRiskView bookId="book-1" />)

    expect(screen.getByTestId('mobile-freshness-banner')).toBeInTheDocument()
  })

  it('dims the data card when the VaR timestamp is red-stale', () => {
    // 20 minutes is past the 15-minute red threshold.
    setVaR({
      varResult: varResult({
        calculatedAt: new Date(Date.now() - 20 * 60_000).toISOString(),
      }),
    })

    render(<MobileRiskView bookId="book-1" />)

    expect(screen.getByTestId('mobile-risk-card')).toHaveClass('opacity-50')
  })

  it('does not dim the data card when the VaR timestamp is fresh', () => {
    // Default fixture timestamp is "now" — well within the neutral threshold.
    render(<MobileRiskView bookId="book-1" />)

    expect(screen.getByTestId('mobile-risk-card')).not.toHaveClass('opacity-50')
  })

  it('does not dim the data card at amber staleness', () => {
    // 8 minutes is amber (5–15 min) — heavier than fresh but not red.
    setVaR({
      varResult: varResult({
        calculatedAt: new Date(Date.now() - 8 * 60_000).toISOString(),
      }),
    })

    render(<MobileRiskView bookId="book-1" />)

    expect(screen.getByTestId('mobile-risk-card')).not.toHaveClass('opacity-50')
  })

  it('renders utilisation without a percentage when no VaR limit is configured', () => {
    setVarLimit({ varLimit: null })

    render(<MobileRiskView bookId="book-1" />)

    // VaR value still shows; utilisation has no denominator so no breach.
    expect(screen.getByTestId('mobile-risk-var-value')).toHaveTextContent('$500,000')
    expect(screen.getByTestId('mobile-risk-view')).toHaveAttribute('data-breach', 'false')
  })

  it('hides the utilisation bar and shows an amber "No limit configured" note when no limit is set', () => {
    setVarLimit({ varLimit: null })

    render(<MobileRiskView bookId="book-1" />)

    // An empty grey bar reads as "0% used" — the dangerous opposite of the
    // truth — so it must not render at all when there is no limit.
    expect(screen.queryByTestId('mobile-risk-utilisation-bar')).not.toBeInTheDocument()

    // The absence of a limit must be communicated as a state, not a zero.
    const note = screen.getByTestId('mobile-risk-no-limit')
    expect(note).toHaveTextContent('No limit configured')
    expect(note.className).toContain('amber')
  })

  it('keeps the utilisation bar and percentage when a limit is configured', () => {
    setVarLimit({ varLimit: 1_000_000 })

    render(<MobileRiskView bookId="book-1" />)

    expect(screen.getByTestId('mobile-risk-utilisation-bar')).toBeInTheDocument()
    expect(screen.getByTestId('mobile-risk-utilisation')).toHaveTextContent('50.0%')
    expect(screen.queryByTestId('mobile-risk-no-limit')).not.toBeInTheDocument()
  })
})
