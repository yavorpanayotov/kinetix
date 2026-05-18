import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { InsightResponse } from '../api/insights'
import { AIInsightPanel } from './AIInsightPanel'

function makeInsight(overrides: Partial<InsightResponse> = {}): InsightResponse {
  return {
    narrative: 'Portfolio VaR sits at 1.2% of NAV — driven mostly by tech exposure.',
    bullets: [
      'AAPL contributes 42% of total VaR',
      'Risk regime is stable',
      'Greeks within tolerances',
    ],
    model: 'claude-opus-4-7',
    mode: 'live',
    ...overrides,
  }
}

describe('AIInsightPanel', () => {
  it('renders a loading skeleton when loading is true', () => {
    render(<AIInsightPanel loading />)

    expect(screen.getByTestId('ai-insight-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-content')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-error')).not.toBeInTheDocument()
  })

  it('renders an error banner with role="alert" when error prop is set', () => {
    render(<AIInsightPanel error="Failed to generate insight" />)

    const errorBanner = screen.getByTestId('ai-insight-error')
    expect(errorBanner).toBeInTheDocument()
    expect(errorBanner).toHaveAttribute('role', 'alert')
    expect(errorBanner).toHaveTextContent('Failed to generate insight')
  })

  it('prefers the loading skeleton over the error banner when both are set', () => {
    render(<AIInsightPanel loading error="Something broke" />)

    expect(screen.getByTestId('ai-insight-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-error')).not.toBeInTheDocument()
  })

  it('renders narrative and bullets when insight is provided', () => {
    const insight = makeInsight()
    render(<AIInsightPanel insight={insight} />)

    expect(screen.getByTestId('ai-insight-content')).toBeInTheDocument()
    expect(screen.getByText(insight.narrative)).toBeInTheDocument()
    for (const bullet of insight.bullets) {
      expect(screen.getByText(bullet)).toBeInTheDocument()
    }
  })

  it('renders the model name in the footer', () => {
    render(<AIInsightPanel insight={makeInsight({ model: 'claude-3-5-sonnet' })} />)

    const modelEl = screen.getByTestId('ai-insight-model')
    expect(modelEl).toBeInTheDocument()
    expect(modelEl).toHaveTextContent('claude-3-5-sonnet')
  })

  it('shows the "Demo mode" badge when mode is "canned"', () => {
    render(<AIInsightPanel insight={makeInsight({ mode: 'canned' })} />)

    const badge = screen.getByTestId('ai-insight-demo-badge')
    expect(badge).toBeInTheDocument()
    expect(badge).toHaveTextContent(/demo mode/i)
  })

  it('does NOT show the "Demo mode" badge when mode is "live"', () => {
    render(<AIInsightPanel insight={makeInsight({ mode: 'live' })} />)

    expect(screen.queryByTestId('ai-insight-demo-badge')).not.toBeInTheDocument()
  })

  it('omits the bullet list when bullets array is empty', () => {
    render(<AIInsightPanel insight={makeInsight({ bullets: [] })} />)

    expect(screen.getByTestId('ai-insight-content')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('renders empty state when no loading/error/insight is provided', () => {
    render(<AIInsightPanel />)

    expect(screen.getByTestId('ai-insight-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-content')).not.toBeInTheDocument()
  })

  it('uses the default title "AI Insight" when no title prop is supplied', () => {
    render(<AIInsightPanel />)

    expect(screen.getByRole('heading', { name: 'AI Insight' })).toBeInTheDocument()
  })

  it('renders a custom title when provided', () => {
    render(<AIInsightPanel title="Explain my VaR" />)

    expect(screen.getByRole('heading', { name: 'Explain my VaR' })).toBeInTheDocument()
  })

  it('calls onClose when the close button is clicked', () => {
    const onClose = vi.fn()
    render(<AIInsightPanel onClose={onClose} />)

    fireEvent.click(screen.getByLabelText('Close insight panel'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('does NOT render a close button when onClose is not provided', () => {
    render(<AIInsightPanel />)

    expect(screen.queryByLabelText('Close insight panel')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-close')).not.toBeInTheDocument()
  })
})
