// Tests for the accessible "no data" empty-state component (kx-67nh).

import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import { AccessibleEmptyState } from './AccessibleEmptyState'

describe('AccessibleEmptyState', () => {
  it('renders inside a polite live region so screen readers announce it', () => {
    render(<AccessibleEmptyState title="No data" />)
    const region = screen.getByRole('status')
    expect(region).toBeInTheDocument()
    expect(region.getAttribute('aria-live')).toBe('polite')
  })

  it('uses the title as the accessible name when no message is supplied', () => {
    render(<AccessibleEmptyState title="No alerts in the last 24 hours" />)
    const region = screen.getByRole('status')
    expect(region.getAttribute('aria-label')).toBe('No alerts in the last 24 hours')
  })

  it('composes title and string message into the accessible name', () => {
    render(
      <AccessibleEmptyState
        title="No positions"
        message="Book a trade to see positions appear here."
      />,
    )
    const region = screen.getByRole('status')
    expect(region.getAttribute('aria-label')).toBe(
      'No positions. Book a trade to see positions appear here.',
    )
  })

  it('renders the visible title text in the DOM', () => {
    render(<AccessibleEmptyState title="No data" message="Try widening the date range." />)
    expect(screen.getByText('No data')).toBeVisible()
    expect(screen.getByText('Try widening the date range.')).toBeVisible()
  })

  it('renders the optional action slot inside the region', () => {
    render(
      <AccessibleEmptyState
        title="No data"
        action={<button type="button">Clear filters</button>}
      />,
    )
    expect(
      screen.getByRole('button', { name: 'Clear filters' }),
    ).toBeInTheDocument()
  })

  it('lets callers override the announced label entirely', () => {
    render(
      <AccessibleEmptyState
        title="No data"
        message="ignored for assistive tech"
        ariaLabel="Positions table is empty"
      />,
    )
    const region = screen.getByRole('status')
    expect(region.getAttribute('aria-label')).toBe('Positions table is empty')
  })

  it('exposes a stable testid so e2e specs can target the surface', () => {
    render(<AccessibleEmptyState title="No data" />)
    expect(screen.getByTestId('accessible-empty-state')).toBeInTheDocument()
  })
})
