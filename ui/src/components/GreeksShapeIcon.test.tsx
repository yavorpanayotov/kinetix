import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { GreeksShapeIcon } from './GreeksShapeIcon'

describe('GreeksShapeIcon', () => {
  it('renders an up triangle for the up direction', () => {
    render(<GreeksShapeIcon direction="up" label="Delta increased" />)
    const icon = screen.getByTestId('greeks-shape-icon')
    expect(icon).toHaveTextContent('▲')
    expect(icon).toHaveAttribute('role', 'img')
    expect(icon).toHaveAttribute('aria-label', 'Delta increased')
    expect(icon).toHaveAttribute('data-direction', 'up')
  })

  it('renders a down triangle for the down direction', () => {
    render(<GreeksShapeIcon direction="down" label="Gamma decreased" />)
    const icon = screen.getByTestId('greeks-shape-icon')
    expect(icon).toHaveTextContent('▼')
    expect(icon).toHaveAttribute('data-direction', 'down')
  })

  it('renders a circle for the neutral direction', () => {
    render(<GreeksShapeIcon direction="neutral" label="Vega unchanged" />)
    const icon = screen.getByTestId('greeks-shape-icon')
    expect(icon).toHaveTextContent('●')
    expect(icon).toHaveAttribute('data-direction', 'neutral')
  })

  it('uses three distinct glyphs for the three directions so meaning survives a monochrome render', () => {
    const { rerender } = render(<GreeksShapeIcon direction="up" label="u" />)
    const upText = screen.getByTestId('greeks-shape-icon').textContent

    rerender(<GreeksShapeIcon direction="down" label="d" />)
    const downText = screen.getByTestId('greeks-shape-icon').textContent

    rerender(<GreeksShapeIcon direction="neutral" label="n" />)
    const neutralText = screen.getByTestId('greeks-shape-icon').textContent

    const distinct = new Set([upText, downText, neutralText])
    expect(distinct.size).toBe(3)
  })
})
