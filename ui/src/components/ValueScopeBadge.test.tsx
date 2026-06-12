import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ValueScopeBadge } from './ValueScopeBadge'

const NOW = new Date('2026-06-12T12:00:00Z')

describe('ValueScopeBadge', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the scope chip', () => {
    render(<ValueScopeBadge scope="fx-main" />)
    expect(screen.getByTestId('value-scope-badge-scope')).toHaveTextContent('fx-main')
  })

  it('renders a relative as-of time with the absolute timestamp as tooltip', () => {
    render(<ValueScopeBadge scope="Firm" asOf="2026-06-12T11:30:00Z" />)
    const asof = screen.getByTestId('value-scope-badge-asof')
    expect(asof).toHaveTextContent('30m ago')
    expect(asof.getAttribute('title')).toMatch(/2026-06-12/)
  })

  it('omits the as-of element when no timestamp is supplied', () => {
    render(<ValueScopeBadge scope="Firm" />)
    expect(screen.queryByTestId('value-scope-badge-asof')).not.toBeInTheDocument()
  })

  it('honours a custom testid prefix so multiple badges can coexist', () => {
    render(<ValueScopeBadge scope="Firm" asOf="2026-06-12T11:30:00Z" data-testid="ticker-var-scope" />)
    expect(screen.getByTestId('ticker-var-scope-scope')).toHaveTextContent('Firm')
    expect(screen.getByTestId('ticker-var-scope-asof')).toBeInTheDocument()
  })

  it('styles the as-of time as stale once it is older than the threshold', () => {
    // 30 minutes old against a 15-minute threshold — the freshness stamp must
    // visually flag that the number is no longer current.
    render(<ValueScopeBadge scope="Firm" asOf="2026-06-12T11:30:00Z" staleAfterMinutes={15} />)
    const asof = screen.getByTestId('value-scope-badge-asof')
    expect(asof.className).toContain('text-amber')
    expect(asof.getAttribute('title')).toMatch(/stale/i)
  })

  it('does not style the as-of time as stale while within the threshold', () => {
    // 5 minutes old against the default 15-minute threshold.
    render(<ValueScopeBadge scope="Firm" asOf="2026-06-12T11:55:00Z" />)
    const asof = screen.getByTestId('value-scope-badge-asof')
    expect(asof.className).not.toContain('text-amber')
    expect(asof.getAttribute('title')).not.toMatch(/stale/i)
  })

  it('applies a default 15-minute staleness threshold when none is given', () => {
    render(<ValueScopeBadge scope="Firm" asOf="2026-06-12T11:30:00Z" />)
    expect(screen.getByTestId('value-scope-badge-asof').className).toContain('text-amber')
  })
})
