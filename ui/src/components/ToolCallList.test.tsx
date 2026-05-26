import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ToolCall } from '../api/copilot'
import { ToolCallList } from './ToolCallList'

function makeToolCall(overrides: Partial<ToolCall> = {}): ToolCall {
  return {
    name: 'get_book_var',
    params: { book_id: 'fx-main' },
    status: 'ok',
    started_at: '2026-05-19T08:00:00.000Z',
    completed_at: '2026-05-19T08:00:00.250Z',
    ...overrides,
  }
}

describe('ToolCallList', () => {
  it('renders nothing when toolCalls is undefined', () => {
    const { container } = render(<ToolCallList toolCalls={undefined} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when toolCalls is an empty array', () => {
    const { container } = render(<ToolCallList toolCalls={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the wrapper with data-testid="tool-call-list"', () => {
    render(<ToolCallList toolCalls={[makeToolCall()]} />)
    expect(screen.getByTestId('tool-call-list')).toBeInTheDocument()
  })

  it('uses singular label when there is exactly one tool call', () => {
    render(<ToolCallList toolCalls={[makeToolCall()]} />)
    const summary = screen.getByTestId('tool-call-list').querySelector('summary')
    expect(summary?.textContent).toMatch(/1 tool call[^s]|1 tool call$/)
  })

  it('uses plural label when there are multiple tool calls', () => {
    render(<ToolCallList toolCalls={[makeToolCall(), makeToolCall({ name: 'get_greeks' })]} />)
    const summary = screen.getByTestId('tool-call-list').querySelector('summary')
    expect(summary?.textContent).toMatch(/2 tool calls/)
  })

  it('wrapper <details> is collapsed by default (no open attribute)', () => {
    render(<ToolCallList toolCalls={[makeToolCall()]} />)
    const details = screen.getByTestId('tool-call-list')
    expect(details.tagName.toLowerCase()).toBe('details')
    expect(details).not.toHaveAttribute('open')
  })

  it('renders one row per tool call with data-testid="tool-call-row"', () => {
    render(
      <ToolCallList
        toolCalls={[makeToolCall({ name: 'get_book_var' }), makeToolCall({ name: 'get_greeks' })]}
      />,
    )
    const rows = screen.getAllByTestId('tool-call-row')
    expect(rows).toHaveLength(2)
  })

  it('each row shows the tool name', () => {
    render(<ToolCallList toolCalls={[makeToolCall({ name: 'get_book_var' })]} />)
    const row = screen.getByTestId('tool-call-row')
    expect(row).toHaveTextContent('get_book_var')
  })

  it('displays the checkmark icon for status "ok"', () => {
    render(<ToolCallList toolCalls={[makeToolCall({ status: 'ok' })]} />)
    const row = screen.getByTestId('tool-call-row')
    expect(row).toHaveTextContent('✓')
  })

  it('displays the warning icon for status "error"', () => {
    render(<ToolCallList toolCalls={[makeToolCall({ status: 'error' })]} />)
    const row = screen.getByTestId('tool-call-row')
    expect(row).toHaveTextContent('⚠')
  })

  it('displays the warning icon for status "timeout"', () => {
    render(<ToolCallList toolCalls={[makeToolCall({ status: 'timeout' })]} />)
    const row = screen.getByTestId('tool-call-row')
    expect(row).toHaveTextContent('⚠')
  })

  it('shows sub-millisecond durations in ms (e.g. 250ms)', () => {
    // 250 ms
    render(
      <ToolCallList
        toolCalls={[
          makeToolCall({
            started_at: '2026-05-19T08:00:00.000Z',
            completed_at: '2026-05-19T08:00:00.250Z',
          }),
        ]}
      />,
    )
    expect(screen.getByTestId('tool-call-row')).toHaveTextContent('250ms')
  })

  it('shows durations >= 1000ms in seconds (e.g. 1.23s)', () => {
    // 1230 ms → 1.23s
    render(
      <ToolCallList
        toolCalls={[
          makeToolCall({
            started_at: '2026-05-19T08:00:00.000Z',
            completed_at: '2026-05-19T08:00:01.230Z',
          }),
        ]}
      />,
    )
    expect(screen.getByTestId('tool-call-row')).toHaveTextContent('1.23s')
  })

  it('each row contains a nested <details> for params JSON', () => {
    render(
      <ToolCallList
        toolCalls={[makeToolCall({ params: { book_id: 'fx-main', horizon_days: 1 } })]}
      />,
    )
    const row = screen.getByTestId('tool-call-row')
    const nested = row.querySelector('details')
    expect(nested).toBeInTheDocument()
  })

  it('params nested <details> reveals JSON when opened', () => {
    render(
      <ToolCallList
        toolCalls={[makeToolCall({ params: { book_id: 'fx-main', horizon_days: 1 } })]}
      />,
    )
    const row = screen.getByTestId('tool-call-row')
    const nested = row.querySelector('details')!
    // Open it programmatically so the content is visible in jsdom
    nested.setAttribute('open', '')
    const pre = row.querySelector('pre')
    expect(pre).toBeInTheDocument()
    expect(pre?.textContent).toContain('fx-main')
    expect(pre?.textContent).toContain('horizon_days')
  })

  it('the summary label contains "Show reasoning"', () => {
    render(<ToolCallList toolCalls={[makeToolCall()]} />)
    const summary = screen.getByTestId('tool-call-list').querySelector('summary')
    expect(summary?.textContent?.toLowerCase()).toContain('show reasoning')
  })
})
