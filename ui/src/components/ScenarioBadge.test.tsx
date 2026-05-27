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

  it('renders a spinner instead of plain "Loading" text while loading (kx-1crp)', () => {
    render(<ScenarioBadge scenario="stress" regime={null} loading />)
    const badge = screen.getByTestId('scenario-badge')
    // The visible label must NOT be the plain word "Loading" — that's the
    // failure mode the issue asks us to fix. A spinner element is expected
    // alongside an accessible status announcement.
    expect(badge.textContent ?? '').not.toMatch(/^\s*Loading\s*$/)
    expect(badge.querySelector('[data-testid="scenario-badge-spinner"]')).toBeInTheDocument()
    // Screen-reader users still need to know what state they're in.
    const status = badge.querySelector('[role="status"]')
    expect(status).not.toBeNull()
    expect(status?.textContent ?? '').toMatch(/loading/i)
  })

  it('renders while loading even with no scenario or regime set', () => {
    // Loading happens before the scenario is known; the badge must still
    // appear so the user gets an early signal that something is computing.
    render(<ScenarioBadge scenario={null} regime={null} loading />)
    expect(screen.getByTestId('scenario-badge')).toBeInTheDocument()
    expect(screen.getByTestId('scenario-badge-spinner')).toBeInTheDocument()
  })

  it('prefers the loading spinner over the scenario label while computing', () => {
    render(<ScenarioBadge scenario="stress" regime="CRISIS" loading />)
    const badge = screen.getByTestId('scenario-badge')
    // The chip should not be advertising the stale scenario · regime-adj
    // visible label while a new scenario is being computed. The sr-only
    // status announcement may still mention "scenario".
    expect(badge.textContent ?? '').not.toMatch(/regime-adj/)
    expect(badge.textContent ?? '').not.toMatch(/scenario · /)
    expect(screen.getByTestId('scenario-badge-spinner')).toBeInTheDocument()
  })

  it('renders the spinner with an animation class so users see motion', () => {
    render(<ScenarioBadge scenario={null} regime={null} loading />)
    const spinner = screen.getByTestId('scenario-badge-spinner')
    // SVG elements expose `className` as SVGAnimatedString — read the raw
    // attribute so the regex check works regardless of element type.
    expect(spinner.getAttribute('class') ?? '').toMatch(/animate-spin/)
  })
})
