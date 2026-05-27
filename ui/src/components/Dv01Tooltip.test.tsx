// Tests for the Dv01Tooltip explainer (kx-29ry).
//
// DV01 (dollar value of a basis point) is a rates-only sensitivity — for a
// 1bp parallel shift in the yield curve, how much does the position's value
// change? It only makes sense for rates instruments: bonds, swaps, futures,
// caps/floors, swaptions. Equity and FX positions render an em-dash in the
// DV01 column, which confuses junior traders who assume it's a bug.
//
// The Dv01Tooltip surfaces the explanation "Rates-only metric; shows — for
// equities/FX" on hover/focus of the column header or any DV01 cell, so the
// inapplicability is documented in-context rather than requiring the trader
// to ask in chat.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { Dv01Tooltip } from './Dv01Tooltip'

describe('<Dv01Tooltip />', () => {
  it('renders the trigger label', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    expect(screen.getByText('DV01')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the rates-only message when the trigger is hovered', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    fireEvent.mouseEnter(screen.getByTestId('dv01-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      'Rates-only metric; shows — for equities/FX',
    )
  })

  it('shows the rates-only message when the trigger receives keyboard focus', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    fireEvent.focus(screen.getByTestId('dv01-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('hides the tooltip body when the pointer leaves', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    const trigger = screen.getByTestId('dv01-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('hides the tooltip body when focus leaves so keyboard users can dismiss it', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    const trigger = screen.getByTestId('dv01-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    const trigger = screen.getByTestId('dv01-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    expect(screen.getByTestId('dv01-tooltip-trigger')).not.toHaveAttribute(
      'aria-describedby',
    )
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    expect(screen.getByTestId('dv01-tooltip-trigger')).toHaveAttribute('tabindex', '0')
  })

  it('includes both "rates-only" and "equities/FX" so the message is unambiguous', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    fireEvent.mouseEnter(screen.getByTestId('dv01-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip.textContent).toMatch(/rates-only/i)
    expect(tooltip.textContent).toMatch(/equities\/FX/i)
  })

  it('Escape key closes the tooltip', () => {
    render(<Dv01Tooltip>DV01</Dv01Tooltip>)
    const trigger = screen.getByTestId('dv01-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })
})
