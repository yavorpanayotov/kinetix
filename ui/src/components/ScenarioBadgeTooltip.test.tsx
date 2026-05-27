// Tests for the ScenarioBadgeTooltip (kx-qtdb).
//
// Stress scenarios are presented across the platform as compact badges with
// the scenario name (e.g. "2020 COVID Shock"). Risk managers reviewing a
// page full of badges need to know what each shock actually consists of
// without leaving the page — what was shifted, by how much. The tooltip
// renders the scenario name plus a list of shocks (factor + magnitude) on
// hover or focus.

import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import { ScenarioBadgeTooltip } from './ScenarioBadgeTooltip'

const scenario = {
  name: '2020 COVID Shock',
  shocks: [
    { factor: 'SPX', magnitude: '-30%' },
    { factor: 'USD 10Y', magnitude: '-150bp' },
    { factor: 'IG Credit Spread', magnitude: '+250bp' },
  ],
}

describe('<ScenarioBadgeTooltip />', () => {
  it('renders the trigger children', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    expect(screen.getByText('BADGE')).toBeInTheDocument()
  })

  it('hides the tooltip body by default', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('shows the scenario name when the trigger is hovered', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('scenario-badge-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toHaveTextContent('2020 COVID Shock')
  })

  it('shows each shock factor and magnitude when open', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    fireEvent.mouseEnter(screen.getByTestId('scenario-badge-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip).toHaveTextContent('SPX')
    expect(tooltip).toHaveTextContent('-30%')
    expect(tooltip).toHaveTextContent('USD 10Y')
    expect(tooltip).toHaveTextContent('-150bp')
    expect(tooltip).toHaveTextContent('IG Credit Spread')
    expect(tooltip).toHaveTextContent('+250bp')
  })

  it('opens on keyboard focus for screen-reader and keyboard users', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    fireEvent.focus(screen.getByTestId('scenario-badge-tooltip-trigger'))
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
  })

  it('closes when the pointer leaves', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    const trigger = screen.getByTestId('scenario-badge-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    fireEvent.mouseLeave(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('closes when focus leaves', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    const trigger = screen.getByTestId('scenario-badge-tooltip-trigger')
    fireEvent.focus(trigger)
    fireEvent.blur(trigger)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('Escape closes the tooltip', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    const trigger = screen.getByTestId('scenario-badge-tooltip-trigger')
    fireEvent.focus(trigger)
    expect(screen.getByRole('tooltip')).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('wires aria-describedby to the tooltip id while open', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    const trigger = screen.getByTestId('scenario-badge-tooltip-trigger')
    fireEvent.mouseEnter(trigger)
    const tooltip = screen.getByRole('tooltip')
    const id = tooltip.getAttribute('id')
    expect(id).toBeTruthy()
    expect(trigger).toHaveAttribute('aria-describedby', id ?? '')
  })

  it('omits aria-describedby while the tooltip is closed', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    expect(screen.getByTestId('scenario-badge-tooltip-trigger')).not.toHaveAttribute(
      'aria-describedby',
    )
  })

  it('is keyboard reachable via tabIndex on the trigger', () => {
    render(<ScenarioBadgeTooltip scenario={scenario}>BADGE</ScenarioBadgeTooltip>)
    expect(screen.getByTestId('scenario-badge-tooltip-trigger')).toHaveAttribute(
      'tabindex',
      '0',
    )
  })

  it('renders gracefully when the shocks list is empty', () => {
    render(
      <ScenarioBadgeTooltip scenario={{ name: 'Calm', shocks: [] }}>
        BADGE
      </ScenarioBadgeTooltip>,
    )
    fireEvent.mouseEnter(screen.getByTestId('scenario-badge-tooltip-trigger'))
    const tooltip = screen.getByRole('tooltip')
    expect(tooltip).toHaveTextContent('Calm')
    expect(tooltip).toHaveTextContent(/no shocks/i)
  })
})
