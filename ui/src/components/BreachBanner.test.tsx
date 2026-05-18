import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlertEventDto } from '../types'
import { BreachBanner } from './BreachBanner'

const now = new Date('2026-02-28T12:00:00Z')

function makeAlert(overrides: Partial<AlertEventDto> = {}): AlertEventDto {
  return {
    id: 'alert-1',
    ruleId: 'rule-1',
    ruleName: 'VaR Limit',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR breached',
    currentValue: 2300000,
    threshold: 2000000,
    bookId: 'book-1',
    triggeredAt: '2026-02-28T11:58:00Z',
    status: 'TRIGGERED',
    ...overrides,
  }
}

describe('BreachBanner', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(now)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders nothing on a tab that is not in the visible list (e.g. reports), regardless of breach', () => {
    render(
      <BreachBanner
        activeTab="reports"
        varValue={950_000}
        varLimit={1_000_000}
        alerts={[makeAlert({ severity: 'CRITICAL' })]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
  })

  it('renders banner on positions tab when VaR utilisation is above 80%', () => {
    render(
      <BreachBanner
        activeTab="positions"
        varValue={850_000}
        varLimit={1_000_000}
        alerts={[]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.getByTestId('breach-banner')).toBeInTheDocument()
  })

  it('renders banner on risk tab when VaR utilisation is above 80%', () => {
    render(
      <BreachBanner
        activeTab="risk"
        varValue={900_000}
        varLimit={1_000_000}
        alerts={[]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.getByTestId('breach-banner')).toBeInTheDocument()
  })

  it('renders banner on pnl tab when VaR utilisation is above 80%', () => {
    render(
      <BreachBanner
        activeTab="pnl"
        varValue={810_000}
        varLimit={1_000_000}
        alerts={[]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.getByTestId('breach-banner')).toBeInTheDocument()
  })

  it('hides banner when VaR utilisation is at or below 80% and no CRITICAL alerts', () => {
    render(
      <BreachBanner
        activeTab="positions"
        varValue={600_000}
        varLimit={1_000_000}
        alerts={[makeAlert({ severity: 'WARNING' })]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
  })

  it('shows banner when a CRITICAL alert is active even if VaR is well within limit', () => {
    render(
      <BreachBanner
        activeTab="positions"
        varValue={500_000}
        varLimit={1_000_000}
        alerts={[makeAlert({ severity: 'CRITICAL' })]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.getByTestId('breach-banner')).toBeInTheDocument()
  })

  it('hides banner when there are only non-CRITICAL alerts and VaR is below 80%', () => {
    render(
      <BreachBanner
        activeTab="risk"
        varValue={500_000}
        varLimit={1_000_000}
        alerts={[
          makeAlert({ id: 'a-warn', severity: 'WARNING' }),
          makeAlert({ id: 'a-info', severity: 'INFO' }),
        ]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
  })

  it('hides banner when varLimit is missing (cannot compute utilisation) and no CRITICAL alerts', () => {
    render(
      <BreachBanner
        activeTab="positions"
        varValue={900_000}
        varLimit={null}
        alerts={[]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
  })

  it('only forwards CRITICAL alerts to the inner banner — WARNING/INFO are hidden', () => {
    render(
      <BreachBanner
        activeTab="positions"
        varValue={500_000}
        varLimit={1_000_000}
        alerts={[
          makeAlert({ id: 'crit', severity: 'CRITICAL' }),
          makeAlert({ id: 'warn', severity: 'WARNING' }),
          makeAlert({ id: 'info', severity: 'INFO' }),
        ]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.getByTestId('alert-item-crit')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-item-warn')).not.toBeInTheDocument()
    expect(screen.queryByTestId('alert-item-info')).not.toBeInTheDocument()
  })

  it('forwards dismiss to the supplied callback', () => {
    const onDismiss = vi.fn()
    render(
      <BreachBanner
        activeTab="positions"
        varValue={500_000}
        varLimit={1_000_000}
        alerts={[makeAlert({ id: 'crit', severity: 'CRITICAL' })]}
        onDismiss={onDismiss}
      />,
    )

    fireEvent.click(screen.getByTestId('alert-dismiss-crit'))
    expect(onDismiss).toHaveBeenCalledWith('crit')
  })

  it('shows a synthetic VaR-breach banner row when VaR is the only trigger (no CRITICAL alerts)', () => {
    render(
      <BreachBanner
        activeTab="positions"
        varValue={900_000}
        varLimit={1_000_000}
        alerts={[]}
        onDismiss={vi.fn()}
      />,
    )

    // When the trigger is VaR alone, the banner renders a synthetic
    // breach row so the user sees an explanation rather than an empty container.
    const banner = screen.getByTestId('breach-banner')
    expect(banner).toHaveTextContent(/VaR/i)
    expect(banner).toHaveTextContent(/limit/i)
  })

  it('hides on trades tab even with breach conditions', () => {
    render(
      <BreachBanner
        activeTab="trades"
        varValue={900_000}
        varLimit={1_000_000}
        alerts={[makeAlert({ severity: 'CRITICAL' })]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
  })

  describe('Hedge CTA (plan §8.2)', () => {
    it('shows a "Need a hedge?" button when the banner is visible and onOpenHedgePanel is supplied', () => {
      render(
        <BreachBanner
          activeTab="positions"
          varValue={900_000}
          varLimit={1_000_000}
          alerts={[]}
          onDismiss={vi.fn()}
          onOpenHedgePanel={vi.fn()}
        />,
      )

      const cta = screen.getByTestId('breach-banner-hedge-cta')
      expect(cta).toBeInTheDocument()
      expect(cta).toHaveTextContent(/need a hedge/i)
    })

    it('invokes onOpenHedgePanel when the CTA is clicked', () => {
      const onOpenHedgePanel = vi.fn()
      render(
        <BreachBanner
          activeTab="positions"
          varValue={900_000}
          varLimit={1_000_000}
          alerts={[]}
          onDismiss={vi.fn()}
          onOpenHedgePanel={onOpenHedgePanel}
        />,
      )

      fireEvent.click(screen.getByTestId('breach-banner-hedge-cta'))
      expect(onOpenHedgePanel).toHaveBeenCalledTimes(1)
    })

    it('does not render the CTA when the banner itself is hidden (no breach)', () => {
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[]}
          onDismiss={vi.fn()}
          onOpenHedgePanel={vi.fn()}
        />,
      )

      expect(screen.queryByTestId('breach-banner-hedge-cta')).not.toBeInTheDocument()
    })

    it('does not render the CTA when no callback is supplied', () => {
      render(
        <BreachBanner
          activeTab="positions"
          varValue={900_000}
          varLimit={1_000_000}
          alerts={[]}
          onDismiss={vi.fn()}
        />,
      )

      expect(screen.queryByTestId('breach-banner-hedge-cta')).not.toBeInTheDocument()
    })

    it('still shows the CTA when the trigger is a CRITICAL alert (no VaR breach)', () => {
      render(
        <BreachBanner
          activeTab="risk"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[makeAlert({ severity: 'CRITICAL' })]}
          onDismiss={vi.fn()}
          onOpenHedgePanel={vi.fn()}
        />,
      )

      expect(screen.getByTestId('breach-banner-hedge-cta')).toBeInTheDocument()
    })
  })

  it('hides on alerts tab even with breach conditions (alerts tab itself shows them)', () => {
    render(
      <BreachBanner
        activeTab="alerts"
        varValue={900_000}
        varLimit={1_000_000}
        alerts={[makeAlert({ severity: 'CRITICAL' })]}
        onDismiss={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('breach-banner')).not.toBeInTheDocument()
  })
})
