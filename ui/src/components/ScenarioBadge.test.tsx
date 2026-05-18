import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ScenarioBadge } from './ScenarioBadge'

describe('ScenarioBadge', () => {
  it('renders nothing when neither scenario nor regime is provided', () => {
    const { container } = render(<ScenarioBadge scenario={null} regime={null} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing when scenario is null and regime is NORMAL', () => {
    const { container } = render(<ScenarioBadge scenario={null} regime="NORMAL" />)
    expect(container.firstChild).toBeNull()
  })

  it('renders a scenario annotation when scenario is non-null', () => {
    render(<ScenarioBadge scenario="stress" regime={null} />)
    const badge = screen.getByTestId('scenario-badge')
    expect(badge).toBeInTheDocument()
    expect(badge.getAttribute('aria-label')).toMatch(/scenario/i)
    expect(badge.getAttribute('aria-label')).toMatch(/stress/i)
  })

  it('uses friendly label for known scenarios', () => {
    render(<ScenarioBadge scenario="multi-asset" regime={null} />)
    const badge = screen.getByTestId('scenario-badge')
    expect(badge.getAttribute('aria-label')).toMatch(/Multi-Asset/)
  })

  it('renders a regime annotation when regime is non-normal', () => {
    render(<ScenarioBadge scenario={null} regime="CRISIS" />)
    const badge = screen.getByTestId('scenario-badge')
    expect(badge).toBeInTheDocument()
    expect(badge.getAttribute('aria-label')).toMatch(/regime/i)
    expect(badge.getAttribute('aria-label')).toMatch(/CRISIS/)
  })

  it('does not render regime annotation when regime is NORMAL', () => {
    const { container } = render(<ScenarioBadge scenario={null} regime="NORMAL" />)
    expect(container.firstChild).toBeNull()
  })

  it('combines scenario and regime annotations when both are present', () => {
    render(<ScenarioBadge scenario="stress" regime="ELEVATED_VOL" />)
    const badge = screen.getByTestId('scenario-badge')
    expect(badge.getAttribute('aria-label')).toMatch(/scenario/i)
    expect(badge.getAttribute('aria-label')).toMatch(/regime/i)
  })

  it('exposes data attributes for the active scenario and regime', () => {
    render(<ScenarioBadge scenario="stress" regime="CRISIS" />)
    const badge = screen.getByTestId('scenario-badge')
    expect(badge.getAttribute('data-scenario')).toBe('stress')
    expect(badge.getAttribute('data-regime')).toBe('CRISIS')
  })

  it('does not include data-regime when regime is NORMAL', () => {
    render(<ScenarioBadge scenario="stress" regime="NORMAL" />)
    const badge = screen.getByTestId('scenario-badge')
    expect(badge.getAttribute('data-scenario')).toBe('stress')
    expect(badge.hasAttribute('data-regime')).toBe(false)
  })
})
