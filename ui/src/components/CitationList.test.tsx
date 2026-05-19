import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Citation } from '../api/copilot'
import { CitationList } from './CitationList'

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

describe('CitationList', () => {
  it('renders nothing when citations array is empty', () => {
    render(<CitationList citations={[]} />)
    expect(screen.queryByTestId('citation-list')).toBeNull()
  })

  it('renders one li per citation in input order', () => {
    const citations = [
      makeCitation({ tool: 'alpha' }),
      makeCitation({ tool: 'beta' }),
      makeCitation({ tool: 'gamma' }),
    ]
    render(<CitationList citations={citations} />)

    const list = screen.getByTestId('citation-list')
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent('alpha')
    expect(items[1]).toHaveTextContent('beta')
    expect(items[2]).toHaveTextContent('gamma')
  })

  it('shows tool, data_source, result_field, and result_value', () => {
    const citations = [
      makeCitation({
        tool: 'get_book_var',
        data_source: 'risk-orchestrator',
        result_field: 'var_value',
        result_value: 5200000,
      }),
    ]
    render(<CitationList citations={citations} />)

    const list = screen.getByTestId('citation-list')
    const item = within(list).getByRole('listitem')
    expect(item).toHaveTextContent('get_book_var')
    expect(item).toHaveTextContent('risk-orchestrator')
    expect(item).toHaveTextContent('var_value')
    expect(item).toHaveTextContent('5200000')
  })

  it('renders quality_flags as inline badges', () => {
    const citations = [
      makeCitation({ quality_flags: ['STALE', 'LOOKBACK_UNAVAILABLE'] }),
    ]
    render(<CitationList citations={citations} />)

    const flags = screen.getAllByTestId('citation-flag')
    expect(flags).toHaveLength(2)
    expect(flags[0]).toHaveTextContent('STALE')
    expect(flags[1]).toHaveTextContent('LOOKBACK_UNAVAILABLE')
  })

  it('formats freshness_seconds into a relative label', () => {
    const citations = [
      makeCitation({ tool: 'fresh', freshness_seconds: 0 }),
      makeCitation({ tool: 'twoMin', freshness_seconds: 120 }),
      makeCitation({ tool: 'eightHr', freshness_seconds: 28800 }),
    ]
    render(<CitationList citations={citations} />)

    const list = screen.getByTestId('citation-list')
    const items = within(list).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('just now')
    expect(items[1]).toHaveTextContent('2m ago')
    expect(items[2]).toHaveTextContent('8h ago')
  })

  it('Parameters details element collapses by default and expands on click', async () => {
    const user = userEvent.setup()
    const citations = [makeCitation({ params: { a: 1, b: 2 } })]
    render(<CitationList citations={citations} />)

    const summary = screen.getByText('Parameters')
    const details = summary.closest('details')
    expect(details).not.toBeNull()
    expect(details).not.toHaveAttribute('open')

    await user.click(summary)
    expect(details).toHaveAttribute('open')

    const pre = details?.querySelector('pre')
    expect(pre?.textContent).toContain('"a": 1')
    expect(pre?.textContent).toContain('"b": 2')
  })

  it('handles citations whose result_value is a formatted string', () => {
    const citations = [
      makeCitation({ result_value: '5.2M USD', result_currency: 'USD' }),
    ]
    render(<CitationList citations={citations} />)

    const list = screen.getByTestId('citation-list')
    expect(list).toHaveTextContent('5.2M USD')
  })
})
