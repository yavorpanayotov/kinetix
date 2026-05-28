// Tests for the VarConfidenceTooltip explainer (kx-r4ak).
//
// Value-at-Risk is reported as a confidence level plus a holding period, and
// junior traders frequently confuse "95% VaR" (the loss exceeded on the worst
// 5% of days) with "95% chance of losing this much". This tooltip surfaces
// the canonical one-line phrasing — "X% of days, max loss ≤ Y over 1d
// horizon" — so the VaR column header can be read without leaving the page.
//
// The component mirrors the ExpectedShortfallTooltip pattern: hover, focus
// and Escape are wired; aria-describedby points at the body while open; the
// trigger is keyboard reachable.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { VarConfidenceTooltip } from './VarConfidenceTooltip'

describe('<VarConfidenceTooltip />', () => {
  it('renders the trigger label', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    expect(screen.getByText('VaR')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the definition copy when the trigger is hovered', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('var-confidence-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'X% of days, max loss ≤ Y over 1d horizon',
    )
  })

  it('shows the definition copy when the trigger receives keyboard focus', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    fireEvent.focus(screen.getByTestId('var-confidence-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    const trigger = screen.getByTestId('var-confidence-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    const trigger = screen.getByTestId('var-confidence-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    const trigger = screen.getByTestId('var-confidence-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    expect(
      screen.getByTestId('var-confidence-tooltip-trigger'),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    expect(
      screen.getByTestId('var-confidence-tooltip-trigger'),
    ).toHaveAttribute('tabindex', '0')
  })

  it('Escape key closes the tooltip', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    const trigger = screen.getByTestId('var-confidence-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('mentions both "days" and "max loss" so the definition is unambiguous', () => {
    render(<VarConfidenceTooltip>VaR</VarConfidenceTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('var-confidence-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/days/i)
    expect(tooltip.textContent).toMatch(/max loss/i)
  })
})
