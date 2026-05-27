// Tests for the GreeksStressTooltip explainer (kx-7xic).
//
// When a market-regime stress is applied (low-vol → high-vol regime, calm →
// crisis), the Greeks shown for an options book are adjusted by the regime's
// stress factors — typically a multiplier on vega/gamma and a shift on
// implied vol. Traders looking at a single delta number on the screen have
// no idea whether the figure is "raw" or "stressed", and they certainly
// can't tell which factors were applied without digging into the model
// configuration.
//
// The GreeksStressTooltip surfaces "Greeks adjusted for market regime" and
// the list of applied stress factors (e.g. "vega × 1.4, vol shift +5%")
// next to a stressed Greeks figure so the adjustment is documented exactly
// where the value lives.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { GreeksStressTooltip, type StressFactor } from './GreeksStressTooltip'

const FACTORS: StressFactor[] = [
  { label: 'vega', multiplier: 1.4 },
  { label: 'gamma', multiplier: 1.25 },
  { label: 'vol shift', shift: 0.05 },
]

describe('<GreeksStressTooltip />', () => {
  it('renders the trigger label', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    expect(screen.getByText('0.42')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the regime adjustment message when the trigger is hovered', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('greeks-stress-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      /Greeks adjusted for market regime/i,
    )
  })

  it('shows the tooltip when the trigger receives keyboard focus', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    fireEvent.focus(screen.getByTestId('greeks-stress-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('lists each multiplier factor with its multiplier value', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('greeks-stress-tooltip-trigger'))
    const body = screen.getByRole('tooltip').textContent ?? ''
    expect(body).toMatch(/vega × 1\.4/)
    expect(body).toMatch(/gamma × 1\.25/)
  })

  it('lists each shift factor with a signed percentage', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('greeks-stress-tooltip-trigger'))
    const body = screen.getByRole('tooltip').textContent ?? ''
    expect(body).toMatch(/vol shift \+5%/)
  })

  it('renders negative shifts with a leading minus rather than a plus', () => {
    const negative: StressFactor[] = [{ label: 'vol shift', shift: -0.03 }]
    render(<GreeksStressTooltip factors={negative}>0.42</GreeksStressTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('greeks-stress-tooltip-trigger'))
    const body = screen.getByRole('tooltip').textContent ?? ''
    expect(body).toMatch(/vol shift -3%/)
  })

  it('hides the tooltip when the pointer leaves', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    const trigger = screen.getByTestId('greeks-stress-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip when focus leaves so keyboard users can dismiss it', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    const trigger = screen.getByTestId('greeks-stress-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    const trigger = screen.getByTestId('greeks-stress-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    expect(
      screen.getByTestId('greeks-stress-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    expect(
      screen.getByTestId('greeks-stress-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<GreeksStressTooltip factors={FACTORS}>0.42</GreeksStressTooltip>)
    const trigger = screen.getByTestId('greeks-stress-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('renders a fallback message when no stress factors are applied', () => {
    render(<GreeksStressTooltip factors={[]}>0.42</GreeksStressTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('greeks-stress-tooltip-trigger'))
    const body = screen.getByRole('tooltip').textContent ?? ''
    expect(body).toMatch(/No stress factors applied/i)
  })
})
