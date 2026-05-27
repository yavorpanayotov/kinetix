// Tests for the RegimeIndicatorTooltip explainer (kx-ek9v).
//
// The platform tags each scenario run with a "regime" (e.g. "Risk-Off",
// "Calm", "Tightening Cycle"). The regime drives volatility and correlation
// adjustments before pricing — vols are scaled up in stress regimes, and the
// correlation matrix is shifted toward the regime's empirical mean. Traders
// see the regime label next to the run summary, but the actual adjustments
// applied are buried in the run manifest. This tooltip surfaces a one-line
// summary of those adjustments on hover/focus so the user does not have to
// dig into the manifest to understand why their stress numbers moved.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { RegimeIndicatorTooltip } from './RegimeIndicatorTooltip'

const PROPS = {
  regimeName: 'Risk-Off',
  volMultiplier: 1.5,
  correlationShift: 0.2,
}

describe('<RegimeIndicatorTooltip />', () => {
  it('renders the trigger label', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    expect(screen.getAllByText('Risk-Off').length).toBeGreaterThan(0)
  })

  it('hides the tooltip body by default', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the regime name in the tooltip when hovered', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('regime-indicator-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/Risk-Off/)
  })

  it('shows the vol multiplier and correlation shift on hover', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('regime-indicator-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/1\.5/)
    expect(tooltip.textContent).toMatch(/0\.2/)
  })

  it('shows the tooltip when the trigger receives keyboard focus', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    fireEvent.focus(screen.getByTestId('regime-indicator-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    const trigger = screen.getByTestId('regime-indicator-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    const trigger = screen.getByTestId('regime-indicator-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    const trigger = screen.getByTestId('regime-indicator-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    expect(
      screen.getByTestId('regime-indicator-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    expect(
      screen.getByTestId('regime-indicator-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    const trigger = screen.getByTestId('regime-indicator-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('labels the vol and correlation adjustments so the numbers are unambiguous', () => {
    render(<RegimeIndicatorTooltip {...PROPS}>Risk-Off</RegimeIndicatorTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('regime-indicator-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/vol/i)
    expect(tooltip.textContent).toMatch(/correlation/i)
  })
})
