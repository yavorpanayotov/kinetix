import { describe, test, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MorningBriefCard } from './MorningBriefCard'
import type { MorningBrief } from '../api/brief'
import type { Citation } from '../api/copilot'

function makeCitation(): Citation {
  return {
    tool: 'get_book_var',
    params: { book_id: 'fx-main' },
    result_field: 'total_var',
    result_value: 5_200_000,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-20T07:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 300,
    quality_flags: [],
  }
}

function makeBrief(overrides: Partial<MorningBrief> = {}): MorningBrief {
  return {
    book_id: 'fx-main',
    generated_at: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
    mode: 'canned',
    sections: [
      {
        title: 'Overnight VaR move',
        narrative: 'VaR rose 12% overnight on widening EUR vol.',
        bullets: ['EURUSD vol up 4 pts', 'Net delta unchanged'],
        sources: [makeCitation()],
        severity: 'warning',
        status: 'ok',
      },
      {
        title: 'Top movers',
        narrative: 'Three positions drove the bulk of the move.',
        bullets: ['EURUSD', 'GBPUSD', 'USDJPY'],
        sources: [],
        severity: 'info',
        status: 'ok',
      },
    ],
    ...overrides,
  }
}

describe('MorningBriefCard', () => {
  test('renders the brief header with book_id', () => {
    render(<MorningBriefCard brief={makeBrief()} />)
    const card = screen.getByTestId('morning-brief-card')
    expect(card).toBeInTheDocument()
    expect(card).toHaveTextContent('Morning Brief')
    expect(card).toHaveTextContent('fx-main')
  })

  test('renders one block per section with title, narrative, bullets', () => {
    render(<MorningBriefCard brief={makeBrief()} />)
    const s0 = screen.getByTestId('brief-section-0')
    expect(s0).toHaveTextContent('Overnight VaR move')
    expect(s0).toHaveTextContent('VaR rose 12% overnight on widening EUR vol.')
    expect(within(s0).getByText('EURUSD vol up 4 pts')).toBeInTheDocument()
    expect(within(s0).getByText('Net delta unchanged')).toBeInTheDocument()

    const s1 = screen.getByTestId('brief-section-1')
    expect(s1).toHaveTextContent('Top movers')
    expect(within(s1).getByText('USDJPY')).toBeInTheDocument()
  })

  test('renders CitationList when a section has sources', () => {
    render(<MorningBriefCard brief={makeBrief()} />)
    const s0 = screen.getByTestId('brief-section-0')
    expect(within(s0).getByTestId('citation-list')).toBeInTheDocument()
  })

  test('does not render citations block when sources empty', () => {
    render(<MorningBriefCard brief={makeBrief()} />)
    const s1 = screen.getByTestId('brief-section-1')
    expect(within(s1).queryByTestId('citation-list')).not.toBeInTheDocument()
  })

  test('shows the demo-mode badge when mode is canned', () => {
    render(<MorningBriefCard brief={makeBrief({ mode: 'canned' })} />)
    const badge = screen.getByTestId('morning-brief-demo-badge')
    expect(badge).toBeInTheDocument()
    expect(badge).toHaveTextContent('Demo mode')
  })

  test('hides the demo-mode badge when mode is live', () => {
    render(<MorningBriefCard brief={makeBrief({ mode: 'live' })} />)
    expect(
      screen.queryByTestId('morning-brief-demo-badge'),
    ).not.toBeInTheDocument()
  })

  test('shows a status tag for a non-ok section', () => {
    const brief = makeBrief({
      sections: [
        {
          title: 'Greeks summary',
          narrative: 'Greeks unavailable.',
          bullets: [],
          sources: [],
          severity: 'info',
          status: 'error',
        },
      ],
    })
    render(<MorningBriefCard brief={brief} />)
    const s0 = screen.getByTestId('brief-section-0')
    expect(within(s0).getByTestId('brief-section-status')).toHaveTextContent(
      'error',
    )
  })

  test('does not show a status tag for an ok section', () => {
    render(<MorningBriefCard brief={makeBrief()} />)
    const s1 = screen.getByTestId('brief-section-1')
    expect(
      within(s1).queryByTestId('brief-section-status'),
    ).not.toBeInTheDocument()
  })

  test('severity accent classes map info/warning/critical', () => {
    const brief = makeBrief({
      sections: [
        {
          title: 'Info section',
          narrative: 'n',
          bullets: [],
          sources: [],
          severity: 'info',
          status: 'ok',
        },
        {
          title: 'Warning section',
          narrative: 'n',
          bullets: [],
          sources: [],
          severity: 'warning',
          status: 'ok',
        },
        {
          title: 'Critical section',
          narrative: 'n',
          bullets: [],
          sources: [],
          severity: 'critical',
          status: 'ok',
        },
      ],
    })
    render(<MorningBriefCard brief={brief} />)
    expect(
      within(screen.getByTestId('brief-section-0')).getByTestId(
        'brief-section-accent',
      ).className,
    ).toMatch(/slate/)
    expect(
      within(screen.getByTestId('brief-section-1')).getByTestId(
        'brief-section-accent',
      ).className,
    ).toMatch(/amber/)
    expect(
      within(screen.getByTestId('brief-section-2')).getByTestId(
        'brief-section-accent',
      ).className,
    ).toMatch(/red/)
  })
})
