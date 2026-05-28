// Tests for the DeltaTooltip explainer (kx-6zu1).
//
// Delta measures the first-order sensitivity of option value to the
// underlying price. Pricing-aware traders read it as "shares-equivalent
// exposure", but junior users frequently mis-read 0.50 delta as "50% of
// the notional moves." The canonical one-line phrasing — "Estimated
// profit/loss per 1% underlying move" — anchors the unit next to the
// Delta column header without leaving the page.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { DeltaTooltip } from './DeltaTooltip'

describe('<DeltaTooltip />', () => {
  it('renders the trigger label', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    expect(screen.getByText('Δ')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the definition copy when the trigger is hovered', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('delta-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'Estimated profit/loss per 1% underlying move',
    )
  })

  it('shows the definition copy when the trigger receives keyboard focus', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    fireEvent.focus(screen.getByTestId('delta-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    const trigger = screen.getByTestId('delta-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    const trigger = screen.getByTestId('delta-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    const trigger = screen.getByTestId('delta-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    expect(
      screen.getByTestId('delta-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    expect(
      screen.getByTestId('delta-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    const trigger = screen.getByTestId('delta-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('mentions both "profit" and "underlying" so the metric scope is clear', () => {
    render(<DeltaTooltip>Δ</DeltaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('delta-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/profit/i)
    expect(tooltip.textContent).toMatch(/underlying/i)
  })
})
