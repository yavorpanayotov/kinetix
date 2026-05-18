import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StalePanelWrapper } from './StalePanelWrapper'

describe('StalePanelWrapper', () => {
  it('renders children only when not stale (no wrapper styling, no provenance)', () => {
    render(
      <StalePanelWrapper stale={false}>
        <div data-testid="child">Child</div>
      </StalePanelWrapper>,
    )

    expect(screen.getByTestId('child')).toBeInTheDocument()
    expect(screen.queryByTestId('stale-wrapper')).toBeNull()
    expect(screen.queryByTestId('stale-provenance')).toBeNull()
  })

  it('renders an amber-tinted wrapper around children when stale', () => {
    render(
      <StalePanelWrapper stale={true}>
        <div data-testid="child">Child</div>
      </StalePanelWrapper>,
    )

    const wrapper = screen.getByTestId('stale-wrapper')
    expect(wrapper).toBeInTheDocument()
    expect(wrapper.className).toMatch(/amber/)
    // children still rendered inside
    expect(screen.getByTestId('child')).toBeInTheDocument()
  })

  it('does not render provenance line when stale but no timestamps provided', () => {
    render(
      <StalePanelWrapper stale={true}>
        <div>Child</div>
      </StalePanelWrapper>,
    )

    expect(screen.queryByTestId('stale-provenance')).toBeNull()
  })

  it('renders provenance line with both computedAt and sourceAsOf when stale', () => {
    render(
      <StalePanelWrapper
        stale={true}
        computedAt="2026-05-18T10:30:00Z"
        sourceAsOf="2026-05-18T08:30:00Z"
      >
        <div>Child</div>
      </StalePanelWrapper>,
    )

    const provenance = screen.getByTestId('stale-provenance')
    expect(provenance).toBeInTheDocument()
    expect(provenance.textContent).toContain('Computed at')
    expect(provenance.textContent).toContain('Source as of')
    expect(provenance.textContent).toContain('2026-05-18T10:30:00Z')
    expect(provenance.textContent).toContain('2026-05-18T08:30:00Z')
  })

  it('renders provenance with only computedAt when sourceAsOf omitted', () => {
    render(
      <StalePanelWrapper stale={true} computedAt="2026-05-18T10:30:00Z">
        <div>Child</div>
      </StalePanelWrapper>,
    )

    const provenance = screen.getByTestId('stale-provenance')
    expect(provenance).toBeInTheDocument()
    expect(provenance.textContent).toContain('Computed at')
    expect(provenance.textContent).not.toContain('Source as of')
  })

  it('renders provenance with only sourceAsOf when computedAt omitted', () => {
    render(
      <StalePanelWrapper stale={true} sourceAsOf="2026-05-18T08:30:00Z">
        <div>Child</div>
      </StalePanelWrapper>,
    )

    const provenance = screen.getByTestId('stale-provenance')
    expect(provenance).toBeInTheDocument()
    expect(provenance.textContent).toContain('Source as of')
    expect(provenance.textContent).not.toContain('Computed at')
  })

  it('does not render provenance line when not stale even if timestamps provided', () => {
    render(
      <StalePanelWrapper
        stale={false}
        computedAt="2026-05-18T10:30:00Z"
        sourceAsOf="2026-05-18T08:30:00Z"
      >
        <div>Child</div>
      </StalePanelWrapper>,
    )

    expect(screen.queryByTestId('stale-provenance')).toBeNull()
  })

  it('forwards data-testid to the wrapper when stale', () => {
    render(
      <StalePanelWrapper stale={true} data-testid="custom-wrapper">
        <div>Child</div>
      </StalePanelWrapper>,
    )

    expect(screen.getByTestId('custom-wrapper')).toBeInTheDocument()
  })
})
