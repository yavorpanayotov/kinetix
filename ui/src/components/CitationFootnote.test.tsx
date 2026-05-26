import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Citation } from '../api/copilot'
import { CitationFootnote } from './CitationFootnote'

// Helper to find the urgency dot within a <cite> element.
function getUrgencyDot(cite: Element): Element | null {
  return cite.querySelector('[data-testid="urgency-dot"]')
}

/**
 * Build a ``Citation`` with sensible defaults; tests override only the
 * fields they care about. Keeps fixtures focused on what each behaviour
 * is asserting rather than ceremonial boilerplate.
 */
function makeCitation(overrides: Partial<Citation> = {}): Citation {
  return {
    tool: 'get_book_var',
    params: {},
    result_field: 'var_value',
    result_value: 0,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-19T12:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 0,
    quality_flags: [],
    ...overrides,
  }
}

describe('CitationFootnote', () => {
  it('wraps a numeric token in a <cite> element with footnote sup', () => {
    const citations = [makeCitation({ result_value: 5200000.0 })]
    render(
      <CitationFootnote
        narrative="VaR is $5200000.00 today"
        citations={citations}
      />,
    )

    const cite = screen.getByText('$5200000.00').closest('cite')
    expect(cite).not.toBeNull()
    expect(cite).toHaveAttribute('data-citation-index', '1')
    expect(cite).toHaveAttribute('data-token', '$5200000.00')
    expect(cite?.querySelector('sup')?.textContent).toBe('1')
  })

  it('marks uncited numeric tokens with [uncited] sup', () => {
    const citations = [makeCitation({ result_value: 5200000.0 })]
    render(
      <CitationFootnote narrative="VaR is $9999.99" citations={citations} />,
    )

    const cite = screen.getByText('$9999.99').closest('cite')
    expect(cite).not.toBeNull()
    expect(cite).toHaveAttribute('data-uncited', 'true')
    expect(cite?.querySelector('sup')?.textContent).toBe('[uncited]')
  })

  it('does not wrap non-numeric text', () => {
    const { container } = render(
      <CitationFootnote narrative="Hello, world" citations={[]} />,
    )

    expect(container.querySelectorAll('cite')).toHaveLength(0)
    expect(container).toHaveTextContent('Hello, world')
  })

  it('renders citations in tool-call order with matching indices', () => {
    const citations = [
      makeCitation({ result_value: 100, tool: 'A' }),
      makeCitation({ result_value: 200, tool: 'B' }),
    ]
    const { container } = render(
      <CitationFootnote
        narrative="first 100 then 200"
        citations={citations}
      />,
    )

    const cites = container.querySelectorAll('cite')
    expect(cites).toHaveLength(2)
    expect(cites[0]).toHaveAttribute('data-citation-index', '1')
    expect(cites[0]).toHaveAttribute('data-token', '100')
    expect(cites[1]).toHaveAttribute('data-citation-index', '2')
    expect(cites[1]).toHaveAttribute('data-token', '200')
  })

  it('flagUncited=false suppresses the [uncited] marker but still wraps in cite', () => {
    const citations = [makeCitation({ result_value: 5200000.0 })]
    const { container } = render(
      <CitationFootnote
        narrative="VaR is $9999.99"
        citations={citations}
        flagUncited={false}
      />,
    )

    const cite = container.querySelector('cite')
    expect(cite).not.toBeNull()
    expect(cite).toHaveAttribute('data-uncited', 'true')
    expect(cite?.querySelector('sup')).toBeNull()
  })

  it('matches at the token decimal precision (5.2 vs 5.249 at 1dp)', () => {
    const citations = [makeCitation({ result_value: 5.249 })]
    const { container } = render(
      <CitationFootnote narrative="value 5.2" citations={citations} />,
    )

    const cite = container.querySelector('cite')
    expect(cite).not.toBeNull()
    expect(cite).toHaveAttribute('data-citation-index', '1')
    expect(cite).not.toHaveAttribute('data-uncited', 'true')
  })

  it('matches at the token decimal precision (5.20 vs 5.249 at 2dp is uncited)', () => {
    const citations = [makeCitation({ result_value: 5.249 })]
    const { container } = render(
      <CitationFootnote narrative="value 5.20" citations={citations} />,
    )

    const cite = container.querySelector('cite')
    expect(cite).not.toBeNull()
    expect(cite).toHaveAttribute('data-uncited', 'true')
  })

  it('parses string citation result_value via regex prefix', () => {
    const citations = [makeCitation({ result_value: '1234567.89 USD' })]
    const { container } = render(
      <CitationFootnote
        narrative="NAV is $1234567.89"
        citations={citations}
      />,
    )

    const cite = container.querySelector('cite')
    expect(cite).not.toBeNull()
    expect(cite).toHaveAttribute('data-citation-index', '1')
    expect(cite).not.toHaveAttribute('data-uncited', 'true')
  })

  it('strips $ and , and % when parsing tokens', () => {
    const citations = [makeCitation({ result_value: 12.5 })]
    const { container } = render(
      <CitationFootnote narrative="jumped 12.5%" citations={citations} />,
    )

    const cite = container.querySelector('cite')
    expect(cite).not.toBeNull()
    expect(cite).toHaveAttribute('data-token', '12.5%')
    expect(cite).toHaveAttribute('data-citation-index', '1')
    expect(cite).not.toHaveAttribute('data-uncited', 'true')
  })

  it('preserves intervening whitespace and punctuation', () => {
    const citations = [
      makeCitation({ result_value: 1.0, tool: 'A' }),
      makeCitation({ result_value: 2.0, tool: 'B' }),
    ]
    const { container } = render(
      <CitationFootnote
        narrative="AAA: 1.0 vs 2.0 today."
        citations={citations}
      />,
    )

    const cites = container.querySelectorAll('cite')
    expect(cites).toHaveLength(2)
    expect(cites[0]).toHaveAttribute('data-token', '1.0')
    expect(cites[1]).toHaveAttribute('data-token', '2.0')

    // Full text content (including the wrapped tokens and their footnote
    // supers) should still read in source order with surrounding prose.
    const text = container.textContent ?? ''
    expect(text.startsWith('AAA: 1.0')).toBe(true)
    expect(text.includes(' vs 2.0')).toBe(true)
    expect(text.endsWith(' today.')).toBe(true)
  })

  it('handles narrative with no numeric tokens and no citations', () => {
    const { container } = render(
      <CitationFootnote narrative="Hello" citations={[]} />,
    )

    expect(container.querySelectorAll('cite')).toHaveLength(0)
    expect(container).toHaveTextContent('Hello')
  })
})

describe('CitationFootnote urgency dot', () => {
  it('shows no urgency dot for a fresh citation (freshness_seconds = 0)', () => {
    const citations = [makeCitation({ result_value: 100, freshness_seconds: 0 })]
    const { container } = render(
      <CitationFootnote narrative="value 100" citations={citations} />,
    )
    const cite = container.querySelector('cite[data-citation-index]')
    expect(cite).not.toBeNull()
    expect(getUrgencyDot(cite!)).toBeNull()
  })

  it('shows no urgency dot for a fresh citation (freshness_seconds = 30)', () => {
    const citations = [makeCitation({ result_value: 100, freshness_seconds: 30 })]
    const { container } = render(
      <CitationFootnote narrative="value 100" citations={citations} />,
    )
    const cite = container.querySelector('cite[data-citation-index]')
    expect(cite).not.toBeNull()
    expect(getUrgencyDot(cite!)).toBeNull()
  })

  it('shows an amber urgency dot for an aging citation (freshness_seconds = 45)', () => {
    const citations = [makeCitation({ result_value: 100, freshness_seconds: 45 })]
    const { container } = render(
      <CitationFootnote narrative="value 100" citations={citations} />,
    )
    const cite = container.querySelector('cite[data-citation-index]')
    expect(cite).not.toBeNull()
    const dot = getUrgencyDot(cite!)
    expect(dot).not.toBeNull()
    expect(dot!.textContent).toBe('•')
    expect(dot!.className).toContain('text-amber-300')
  })

  it('shows an amber urgency dot for an aging citation at boundary (freshness_seconds = 60)', () => {
    const citations = [makeCitation({ result_value: 100, freshness_seconds: 60 })]
    const { container } = render(
      <CitationFootnote narrative="value 100" citations={citations} />,
    )
    const cite = container.querySelector('cite[data-citation-index]')
    expect(cite).not.toBeNull()
    const dot = getUrgencyDot(cite!)
    expect(dot).not.toBeNull()
    expect(dot!.className).toContain('text-amber-300')
  })

  it('shows a rose urgency dot for a stale citation (freshness_seconds = 61)', () => {
    const citations = [makeCitation({ result_value: 100, freshness_seconds: 61 })]
    const { container } = render(
      <CitationFootnote narrative="value 100" citations={citations} />,
    )
    const cite = container.querySelector('cite[data-citation-index]')
    expect(cite).not.toBeNull()
    const dot = getUrgencyDot(cite!)
    expect(dot).not.toBeNull()
    expect(dot!.textContent).toBe('•')
    expect(dot!.className).toContain('text-rose-300')
  })

  it('shows a rose urgency dot for a very stale citation (freshness_seconds = 300)', () => {
    const citations = [makeCitation({ result_value: 100, freshness_seconds: 300 })]
    const { container } = render(
      <CitationFootnote narrative="value 100" citations={citations} />,
    )
    const cite = container.querySelector('cite[data-citation-index]')
    expect(cite).not.toBeNull()
    const dot = getUrgencyDot(cite!)
    expect(dot).not.toBeNull()
    expect(dot!.className).toContain('text-rose-300')
  })

  it('does not show a dot for uncited tokens regardless of citation freshness', () => {
    // The citation has a stale freshness but the token 9999 doesn't match it
    const citations = [makeCitation({ result_value: 100, freshness_seconds: 300 })]
    const { container } = render(
      <CitationFootnote narrative="value 9999" citations={citations} />,
    )
    const uncitedCite = container.querySelector('cite[data-uncited]')
    expect(uncitedCite).not.toBeNull()
    expect(getUrgencyDot(uncitedCite!)).toBeNull()
  })
})
