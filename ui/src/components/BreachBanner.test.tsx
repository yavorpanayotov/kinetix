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

  describe('Duplicate rollup (plan §3.1, G3)', () => {
    it('folds three matching VaR breaches into a single rollup banner with a count badge and View all link', () => {
      const onViewAllAlerts = vi.fn()
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[
            // Mirrors the audit screenshot: three near-identical VaR breaches
            // all "3 days ago", values within ~$70 of each other.
            makeAlert({
              id: 'a1',
              severity: 'CRITICAL',
              type: 'VAR_BREACH',
              bookId: 'derivatives-book',
              currentValue: 2_512_672,
              threshold: 1_000_000,
              triggeredAt: '2026-02-25T13:00:00Z',
            }),
            makeAlert({
              id: 'a2',
              severity: 'CRITICAL',
              type: 'VAR_BREACH',
              bookId: 'derivatives-book',
              currentValue: 2_512_658,
              threshold: 1_000_000,
              triggeredAt: '2026-02-25T13:30:00Z',
            }),
            makeAlert({
              id: 'a3',
              severity: 'CRITICAL',
              type: 'VAR_BREACH',
              bookId: 'derivatives-book',
              currentValue: 2_512_730,
              threshold: 1_000_000,
              triggeredAt: '2026-02-25T13:45:00Z', // latest (most recent) — all within 45min
            }),
          ]}
          onDismiss={vi.fn()}
          onViewAllAlerts={onViewAllAlerts}
        />,
      )

      // Exactly one alert row is rendered (rollup) — not three separate items.
      expect(screen.queryByTestId('alert-item-a1')).not.toBeInTheDocument()
      expect(screen.queryByTestId('alert-item-a2')).not.toBeInTheDocument()
      expect(screen.queryByTestId('alert-item-a3')).not.toBeInTheDocument()

      const rollup = screen.getByTestId('breach-banner-rollup')
      expect(rollup).toBeInTheDocument()
      expect(rollup).toHaveTextContent(/3 VaR breaches in the last 24h/i)
      expect(rollup).toHaveTextContent(/\$2,512,730/)
      expect(rollup).toHaveTextContent(/derivatives-book/)

      // Count badge has the required testid and shows the group size.
      const countBadge = screen.getByTestId('breach-banner-count')
      expect(countBadge.tagName).toBe('SPAN')
      expect(countBadge).toHaveTextContent('3')

      // View all CTA navigates to the Alerts tab.
      const viewAll = screen.getByTestId('breach-banner-view-all')
      expect(viewAll).toHaveTextContent(/view all/i)
      fireEvent.click(viewAll)
      expect(onViewAllAlerts).toHaveBeenCalledTimes(1)
    })

    it('rolls up same-type breaches across different books into one banner naming the worst book', () => {
      // UX review: three CRITICAL VaR banners (macro-hedge, multi-asset,
      // emerging-markets) stacked 130px of permanent red on every tab. One
      // condition class → one rollup row with cross-book context.
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[
            makeAlert({ id: 'a1', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'macro-hedge', currentValue: 30_447_680, triggeredAt: '2026-02-28T11:50:00Z' }),
            makeAlert({ id: 'a2', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'multi-asset', currentValue: 2_222_774, triggeredAt: '2026-02-28T11:55:00Z' }),
            makeAlert({ id: 'a3', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'emerging-markets', currentValue: 27_284_598, triggeredAt: '2026-02-28T11:58:00Z' }),
          ]}
          onDismiss={vi.fn()}
        />,
      )

      expect(screen.queryByTestId('alert-item-a1')).not.toBeInTheDocument()
      expect(screen.queryByTestId('alert-item-a2')).not.toBeInTheDocument()
      expect(screen.queryByTestId('alert-item-a3')).not.toBeInTheDocument()

      const rollup = screen.getByTestId('breach-banner-rollup')
      expect(rollup).toHaveTextContent(/3 VaR breaches across 3 books/i)
      expect(rollup).toHaveTextContent(/\$30,447,680/)
      expect(rollup).toHaveTextContent(/macro-hedge/)
      expect(rollup).toHaveTextContent(/oldest/i)
      expect(screen.getByTestId('breach-banner-count')).toHaveTextContent('3')
    })

    it('dedupes the same book+type to the highest severity before rolling up', () => {
      // One VaR condition that tripped both the WARNING and CRITICAL rule
      // must not inflate the rollup count.
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[
            makeAlert({ id: 'crit-a', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'macro-hedge', threshold: 1_000_000, triggeredAt: '2026-02-28T11:50:00Z' }),
            makeAlert({ id: 'warn-a', severity: 'WARNING', type: 'VAR_BREACH', bookId: 'macro-hedge', threshold: 750_000, triggeredAt: '2026-02-28T11:51:00Z' }),
            makeAlert({ id: 'crit-b', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'multi-asset', triggeredAt: '2026-02-28T11:55:00Z' }),
          ]}
          onDismiss={vi.fn()}
        />,
      )

      // Two distinct breached books → rollup of 2, not 3.
      expect(screen.getByTestId('breach-banner-count')).toHaveTextContent('2')
    })

    it('marks a rollup whose newest member is older than 4h as stale', () => {
      // Faked "now" is 2026-02-28T12:00Z — these fired ~15h earlier.
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[
            makeAlert({ id: 'a1', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'book-a', triggeredAt: '2026-02-27T21:00:00Z' }),
            makeAlert({ id: 'a2', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'book-b', triggeredAt: '2026-02-27T21:05:00Z' }),
          ]}
          onDismiss={vi.fn()}
        />,
      )

      expect(screen.getByTestId('breach-banner-rollup')).toHaveAttribute('data-stale', 'true')
    })

    it('does not mark a rollup with a fresh member as stale', () => {
      const now = Date.now()
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[
            makeAlert({ id: 'a1', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'book-a', triggeredAt: new Date(now - 60_000).toISOString() }),
            makeAlert({ id: 'a2', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'book-b', triggeredAt: new Date(now - 120_000).toISOString() }),
          ]}
          onDismiss={vi.fn()}
        />,
      )

      expect(screen.getByTestId('breach-banner-rollup')).toHaveAttribute('data-stale', 'false')
    })

    it('does not roll up alerts older than the 24h window into the group', () => {
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[
            // 26h ago — outside the 24h window, must NOT be rolled up with a1/a2
            makeAlert({ id: 'old', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'derivatives-book', triggeredAt: '2026-02-27T10:00:00Z' }),
            makeAlert({ id: 'a1', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'derivatives-book', triggeredAt: '2026-02-28T11:50:00Z' }),
            makeAlert({ id: 'a2', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'derivatives-book', triggeredAt: '2026-02-28T11:55:00Z' }),
          ]}
          onDismiss={vi.fn()}
        />,
      )

      // The two in-window alerts roll up; the stale alert renders independently.
      const rollup = screen.getByTestId('breach-banner-rollup')
      expect(rollup).toHaveTextContent(/2 VaR breaches in the last 24h/i)
      expect(screen.getByTestId('breach-banner-count')).toHaveTextContent('2')
      expect(screen.getByTestId('alert-item-old')).toBeInTheDocument()
    })

    it('leaves a singleton group rendered as a regular alert row (no rollup)', () => {
      render(
        <BreachBanner
          activeTab="positions"
          varValue={500_000}
          varLimit={1_000_000}
          alerts={[makeAlert({ id: 'solo', severity: 'CRITICAL', type: 'VAR_BREACH', bookId: 'book-1' })]}
          onDismiss={vi.fn()}
        />,
      )

      expect(screen.queryByTestId('breach-banner-rollup')).not.toBeInTheDocument()
      expect(screen.getByTestId('alert-item-solo')).toBeInTheDocument()
    })
  })
})
