// Tests for the VegaTooltip explainer (kx-buz7).
//
// Vega measures the change in option value per 1 percentage point change in
// implied volatility, expressed in the option's currency. The unit confuses
// traders new to options because "1 vol point" can mean either "1
// percentage point" or "1 basis point" depending on the vendor's reporting
// convention. Surfacing the canonical phrasing — "Per 1 percentage point
// (pp) in implied volatility" — pins down the unit.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { VegaTooltip } from './VegaTooltip'

describe('<VegaTooltip />', () => {
  it('renders the trigger label', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    expect(screen.getByText('ν')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the definition copy when the trigger is hovered', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('vega-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'Per 1 percentage point (pp) in implied volatility',
    )
  })

  it('shows the definition copy when the trigger receives keyboard focus', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    fireEvent.focus(screen.getByTestId('vega-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    const trigger = screen.getByTestId('vega-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    const trigger = screen.getByTestId('vega-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    const trigger = screen.getByTestId('vega-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    expect(
      screen.getByTestId('vega-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    expect(
      screen.getByTestId('vega-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    const trigger = screen.getByTestId('vega-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('mentions both "percentage point" and "volatility" so the unit is unambiguous', () => {
    render(<VegaTooltip>ν</VegaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('vega-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/percentage point/i)
    expect(tooltip.textContent).toMatch(/volatility/i)
  })
})
