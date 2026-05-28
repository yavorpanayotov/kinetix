// Tests for the GammaTooltip explainer (kx-d4m6).
//
// Gamma is the second-order Greek that measures the rate of change of delta
// with respect to the underlying price. The intuition that trips up new
// option traders is the sign: positive gamma (long options) benefits from
// volatility, while negative gamma (short options) bleeds in a moving
// market. Surfacing the canonical phrasing — "Rate of change of delta;
// >0 benefits from volatility" — keeps the sign convention anchored.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { GammaTooltip } from './GammaTooltip'

describe('<GammaTooltip />', () => {
  it('renders the trigger label', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    expect(screen.getByText('Γ')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the definition copy when the trigger is hovered', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('gamma-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'Rate of change of delta; >0 benefits from volatility',
    )
  })

  it('shows the definition copy when the trigger receives keyboard focus', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    fireEvent.focus(screen.getByTestId('gamma-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    const trigger = screen.getByTestId('gamma-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    const trigger = screen.getByTestId('gamma-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    const trigger = screen.getByTestId('gamma-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    expect(
      screen.getByTestId('gamma-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    expect(
      screen.getByTestId('gamma-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    const trigger = screen.getByTestId('gamma-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('mentions both "delta" and "volatility" so the relationship is unambiguous', () => {
    render(<GammaTooltip>Γ</GammaTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('gamma-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/delta/i)
    expect(tooltip.textContent).toMatch(/volatility/i)
  })
})
