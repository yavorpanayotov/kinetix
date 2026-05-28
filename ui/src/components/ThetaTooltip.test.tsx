// Tests for the ThetaTooltip explainer (kx-oqog).
//
// Theta is the option Greek that measures the change in option value per
// passage of time, typically expressed per day. The sign convention is the
// constant source of confusion: a long option position has negative theta
// (it loses value as time passes) while a short option position has
// positive theta (it gains as time decays). Surfacing the canonical
// one-line phrasing — "Daily P&L from time passage (positive = long decay)"
// — keeps junior traders from inverting the sign in their head.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { ThetaTooltip } from './ThetaTooltip'

describe('<ThetaTooltip />', () => {
  it('renders the trigger label', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    expect(screen.getByText('Θ')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the definition copy when the trigger is hovered', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('theta-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'Daily P&L from time passage (positive = long decay)',
    )
  })

  it('shows the definition copy when the trigger receives keyboard focus', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    fireEvent.focus(screen.getByTestId('theta-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    const trigger = screen.getByTestId('theta-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    const trigger = screen.getByTestId('theta-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    const trigger = screen.getByTestId('theta-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    expect(
      screen.getByTestId('theta-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    expect(
      screen.getByTestId('theta-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    const trigger = screen.getByTestId('theta-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('mentions both "time" and "decay" so the long/short convention is anchored', () => {
    render(<ThetaTooltip>Θ</ThetaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('theta-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/time/i)
    expect(tooltip.textContent).toMatch(/decay/i)
  })
})
