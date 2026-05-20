import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ChatChunk, Citation } from '../api/copilot'
import type { InsightResponse } from '../api/insights'
import { AIInsightPanel } from './AIInsightPanel'

function makeCitation(overrides: Partial<Citation> = {}): Citation {
  return {
    tool: 'risk.var',
    params: { book_id: 'BOOK-1' },
    result_field: 'value',
    result_value: 5.2,
    result_currency: 'USD',
    as_of_timestamp: '2025-05-19T00:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 30,
    quality_flags: [],
    ...overrides,
  }
}

function streamOf(...chunks: ChatChunk[]): ReadableStream<ChatChunk> {
  return new ReadableStream<ChatChunk>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    },
  })
}

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

  it('renders StreamingNarrative when stream prop is provided', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Streaming narrative text' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
      },
    )

    render(<AIInsightPanel stream={stream} />)

    expect(screen.getByTestId('ai-insight-streaming')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
        'Streaming narrative text',
      ),
    )
  })

  it('renders CitationList after stream completes with citations', async () => {
    const citation = makeCitation()
    const stream = streamOf(
      { type: 'delta', delta: 'VaR is 5.2 USD.' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
        citations: [citation],
      },
    )

    render(<AIInsightPanel stream={stream} />)

    await waitFor(() =>
      expect(screen.getByTestId('ai-insight-citations')).toBeInTheDocument(),
    )
  })

  it('renders model and demo-mode badge after stream completes', async () => {
    const stream = streamOf(
      { type: 'delta', delta: 'Hello' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'canned-chat',
        mode: 'canned',
      },
    )

    render(<AIInsightPanel stream={stream} />)

    await waitFor(() => {
      expect(screen.getByTestId('ai-insight-model')).toHaveTextContent(
        'canned-chat',
      )
      expect(screen.getByTestId('ai-insight-demo-badge')).toBeInTheDocument()
    })
  })

  it('stream branch coexists with insight branch — stream wins when both present', async () => {
    const legacyInsight: InsightResponse = {
      narrative: 'Legacy non-streaming narrative',
      bullets: ['Legacy bullet'],
      model: 'claude-3-5-sonnet',
      mode: 'live',
    }
    const stream = streamOf(
      { type: 'delta', delta: 'Live streamed body' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
      },
    )

    render(<AIInsightPanel stream={stream} insight={legacyInsight} />)

    expect(screen.getByTestId('ai-insight-streaming')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByTestId('streaming-narrative-text')).toHaveTextContent(
        'Live streamed body',
      ),
    )
    expect(screen.queryByTestId('ai-insight-content')).not.toBeInTheDocument()
    expect(
      screen.queryByText('Legacy non-streaming narrative'),
    ).not.toBeInTheDocument()
  })

  it('falls back to insight branch when stream prop is null', () => {
    const legacyInsight: InsightResponse = {
      narrative: 'Legacy non-streaming narrative',
      bullets: ['Legacy bullet'],
      model: 'claude-3-5-sonnet',
      mode: 'live',
    }

    render(<AIInsightPanel stream={null} insight={legacyInsight} />)

    expect(screen.getByTestId('ai-insight-content')).toBeInTheDocument()
    expect(screen.getByText('Legacy non-streaming narrative')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-streaming')).not.toBeInTheDocument()
  })

  it('loading state takes precedence over stream prop', () => {
    const stream = streamOf(
      { type: 'delta', delta: 'should not render' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
      },
    )

    render(<AIInsightPanel loading stream={stream} />)

    expect(screen.getByTestId('ai-insight-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-streaming')).not.toBeInTheDocument()
  })

  it('error state takes precedence over stream prop', () => {
    const stream = streamOf(
      { type: 'delta', delta: 'should not render' },
      {
        type: 'done',
        session_id: 's-1',
        conversation_id: 'c-1',
        model: 'claude-opus-4-7',
        mode: 'live',
      },
    )

    render(<AIInsightPanel error="oops" stream={stream} />)

    expect(screen.getByTestId('ai-insight-error')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-insight-streaming')).not.toBeInTheDocument()
  })
})
