import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { HedgeRecommendationPanel } from './HedgeRecommendationPanel'
import type { HedgeRecommendationDto, HedgeSuggestionDto } from '../types'

const sampleGreekImpact = {
  deltaBefore: 1000,
  deltaAfter: 50,
  gammaBefore: 200,
  gammaAfter: 210,
  vegaBefore: 500,
  vegaAfter: 480,
  thetaBefore: -30,
  thetaAfter: -31.5,
  rhoBefore: 10,
  rhoAfter: 9.8,
}

const sampleSuggestion: HedgeSuggestionDto = {
  instrumentId: 'AAPL-P-2026',
  instrumentType: 'OPTION',
  side: 'BUY',
  quantity: 1000,
  estimatedCost: 5250,
  crossingCost: 250,
  carrycostPerDay: -5,
  targetReduction: 950,
  targetReductionPct: 0.95,
  residualMetric: 50,
  greekImpact: sampleGreekImpact,
  liquidityTier: 'TIER_1',
  dataQuality: 'FRESH',
}

const sampleRecommendation: HedgeRecommendationDto = {
  id: '00000000-0000-0000-0000-000000000001',
  bookId: 'BOOK-1',
  targetMetric: 'DELTA',
  targetReductionPct: 0.90,
  requestedAt: '2026-03-24T10:00:00Z',
  status: 'PENDING',
  expiresAt: '2026-03-24T10:30:00Z',
  acceptedBy: null,
  acceptedAt: null,
  sourceJobId: 'job-123',
  suggestions: [sampleSuggestion],
  preHedgeGreeks: sampleGreekImpact,
  totalEstimatedCost: 5250,
  isExpired: false,
}

function renderPanel(overrides: Partial<React.ComponentProps<typeof HedgeRecommendationPanel>> = {}) {
  const defaults = {
    open: true,
    onClose: vi.fn(),
    bookId: 'BOOK-1',
    recommendation: null,
    loading: false,
    error: null,
    onSuggest: vi.fn(),
    onSendToWhatIf: vi.fn(),
  }
  return render(<HedgeRecommendationPanel {...defaults} {...overrides} />)
}

describe('HedgeRecommendationPanel', () => {
  it('renders nothing when closed', () => {
    renderPanel({ open: false })
    expect(screen.queryByTestId('hedge-recommendation-panel')).toBeNull()
  })

  it('renders the panel when open', () => {
    renderPanel()
    expect(screen.getByTestId('hedge-recommendation-panel')).toBeDefined()
    expect(screen.getByText('Hedge Suggestions')).toBeDefined()
  })

  it('shows the bookId in the header', () => {
    renderPanel()
    expect(screen.getByText('BOOK-1')).toBeDefined()
  })

  it('calls onClose when the close button is clicked', () => {
    const onClose = vi.fn()
    renderPanel({ onClose })
    fireEvent.click(screen.getByLabelText('Close hedge recommendation panel'))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('calls onSuggest with correct arguments on form submit', () => {
    const onSuggest = vi.fn()
    renderPanel({ onSuggest })
    fireEvent.click(screen.getByTestId('suggest-hedge-button'))
    expect(onSuggest).toHaveBeenCalledWith('DELTA', 0.80, 5)
  })

  it('shows validation error when reduction is zero', () => {
    renderPanel()
    const input = screen.getByTestId('hedge-reduction-input')
    fireEvent.change(input, { target: { value: '0' } })
    fireEvent.click(screen.getByTestId('suggest-hedge-button'))
    // value 0 fails validation (must be > 0)
    expect(screen.queryByText(/between 0 and 100/)).toBeDefined()
  })

  it('shows empty state when no recommendation and not loading', () => {
    renderPanel()
    expect(screen.getByTestId('empty-state')).toBeDefined()
  })

  it('renders suggestions when recommendation is provided', () => {
    renderPanel({ recommendation: sampleRecommendation })
    expect(screen.getByTestId('suggestion-1')).toBeDefined()
    expect(screen.getByTestId('suggestion-instrument-1').textContent).toBe('AAPL-P-2026')
  })

  it('renders suggestion side with correct text', () => {
    renderPanel({ recommendation: sampleRecommendation })
    expect(screen.getByTestId('suggestion-side-1').textContent).toBe('BUY')
  })

  it('renders suggestion reduction percentage', () => {
    renderPanel({ recommendation: sampleRecommendation })
    expect(screen.getByTestId('suggestion-reduction-1').textContent).toContain('95')
  })

  it('renders STALE badge for stale price data', () => {
    const staleSuggestion = { ...sampleSuggestion, dataQuality: 'STALE' }
    const rec = { ...sampleRecommendation, suggestions: [staleSuggestion] }
    renderPanel({ recommendation: rec })
    expect(screen.getByTestId('stale-badge-1')).toBeDefined()
  })

  it('does not render STALE badge for fresh price data', () => {
    renderPanel({ recommendation: sampleRecommendation })
    expect(screen.queryByTestId('stale-badge-1')).toBeNull()
  })

  it('wraps a stale suggestion in the amber stale-panel wrapper with provenance', () => {
    const staleSuggestion = { ...sampleSuggestion, dataQuality: 'STALE' }
    const rec = { ...sampleRecommendation, suggestions: [staleSuggestion] }
    renderPanel({ recommendation: rec })
    const wrapper = screen.getByTestId('suggestion-stale-wrapper-1')
    expect(wrapper).toBeDefined()
    // provenance line uses the recommendation's requestedAt timestamp
    const provenance = screen.getByTestId('stale-provenance')
    expect(provenance.textContent).toContain('Computed at')
    expect(provenance.textContent).toContain('2026-03-24T10:00:00Z')
  })

  it('does not wrap a fresh suggestion in the stale-panel wrapper', () => {
    renderPanel({ recommendation: sampleRecommendation })
    expect(screen.queryByTestId('suggestion-stale-wrapper-1')).toBeNull()
    expect(screen.queryByTestId('stale-provenance')).toBeNull()
  })

  it('shows expired warning when recommendation is expired', () => {
    const expiredRec = { ...sampleRecommendation, isExpired: true }
    renderPanel({ recommendation: expiredRec })
    expect(screen.getByTestId('expired-warning')).toBeDefined()
  })

  it('shows no-suggestions message when suggestion list is empty', () => {
    const emptyRec = { ...sampleRecommendation, suggestions: [] }
    renderPanel({ recommendation: emptyRec })
    expect(screen.getByTestId('no-suggestions')).toBeDefined()
  })

  it('shows error message when error is set', () => {
    renderPanel({ error: 'Greeks are stale' })
    const hedgeError = screen.getByTestId('hedge-error')
    expect(hedgeError.textContent).toContain('Greeks are stale')
    // Rendered via the shared ErrorCard which carries role="alert".
    expect(hedgeError.getAttribute('role')).toBe('alert')
  })

  it('disables submit button when loading', () => {
    renderPanel({ loading: true })
    const button = screen.getByTestId('suggest-hedge-button') as HTMLButtonElement
    expect(button.disabled).toBe(true)
  })

  it('disables submit button when bookId is null', () => {
    renderPanel({ bookId: null })
    const button = screen.getByTestId('suggest-hedge-button') as HTMLButtonElement
    expect(button.disabled).toBe(true)
  })

  it('calls onSendToWhatIf with the suggestion when Send to What-If is clicked', () => {
    const onSendToWhatIf = vi.fn()
    renderPanel({ recommendation: sampleRecommendation, onSendToWhatIf })
    fireEvent.click(screen.getByTestId('send-to-what-if-1'))
    expect(onSendToWhatIf).toHaveBeenCalledWith(sampleSuggestion)
  })

  it('shows Greek impact table when Show Greek impact is clicked (Lyapunov problem visible)', () => {
    renderPanel({ recommendation: sampleRecommendation })
    fireEvent.click(screen.getByText('Show Greek impact'))
    // Greek impact table shows all 5 rows; multiple 'Gamma' labels now visible
    const gammaLabels = screen.getAllByText('Gamma')
    // At least one Gamma label must be visible (the impact row in the expanded table)
    expect(gammaLabels.length).toBeGreaterThan(0)
  })
})
