// Tests for the ExpectedShortfallTooltip explainer (kx-lwc6).
//
// Expected Shortfall (ES, also known as CVaR — Conditional Value-at-Risk) is
// the average loss on the worst X% of days, conditional on a VaR breach. It
// is harder for non-quants to read than VaR because the "X%" refers to the
// tail of the loss distribution, not the confidence level. To avoid junior
// traders silently misinterpreting the metric, the ES column header surfaces
// the canonical one-line definition on hover/focus.
//
// The component mirrors the Dv01Tooltip / RegimeIndicatorTooltip pattern:
// hover, focus, and Escape are all wired; aria-describedby points to the
// tooltip body while it is open; and the trigger is keyboard reachable.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { ExpectedShortfallTooltip } from './ExpectedShortfallTooltip'

describe('<ExpectedShortfallTooltip />', () => {
  it('renders the trigger label', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    expect(screen.getByText('ES')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the definition copy when the trigger is hovered', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('expected-shortfall-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'Average loss on worst X% of days',
    )
  })

  it('shows the definition copy when the trigger receives keyboard focus', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    fireEvent.focus(screen.getByTestId('expected-shortfall-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    const trigger = screen.getByTestId('expected-shortfall-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    const trigger = screen.getByTestId('expected-shortfall-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    const trigger = screen.getByTestId('expected-shortfall-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    expect(
      screen.getByTestId('expected-shortfall-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    expect(
      screen.getByTestId('expected-shortfall-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    const trigger = screen.getByTestId('expected-shortfall-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('mentions both "average loss" and "worst" so the definition is unambiguous', () => {
    render(<ExpectedShortfallTooltip>ES</ExpectedShortfallTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('expected-shortfall-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/average loss/i)
    expect(tooltip.textContent).toMatch(/worst/i)
  })
})
