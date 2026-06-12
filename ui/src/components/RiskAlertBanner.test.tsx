import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlertEventDto } from '../types'
import { RiskAlertBanner } from './RiskAlertBanner'

const now = new Date('2026-02-28T12:00:00Z')

function makeAlert(overrides: Partial<AlertEventDto> = {}): AlertEventDto {
  return {
    id: 'alert-1',
    ruleId: 'rule-1',
    ruleName: 'VaR Limit',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR exceeds limit by 15%',
    currentValue: 2300000,
    threshold: 2000000,
    bookId: 'book-1',
    triggeredAt: '2026-02-28T11:58:00Z',
    status: 'TRIGGERED',
    ...overrides,
  }
}

describe('RiskAlertBanner', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(now)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders nothing when there are no alerts', () => {
    const { container } = render(
      <RiskAlertBanner alerts={[]} onDismiss={vi.fn()} />,
    )

    expect(container.firstChild).toBeNull()
  })

  it('renders a critical alert with red styling and formatted message', () => {
    const alert = makeAlert({ severity: 'CRITICAL' })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    const item = screen.getByTestId('alert-item-alert-1')
    expect(item).toBeInTheDocument()
    expect(item.className).toContain('border-red-200')
    expect(item.className).toContain('bg-red-50')
    expect(item).toHaveTextContent('CRITICAL: VaR breached $2,000,000 limit')
  })

  it('renders a warning alert with amber styling and formatted message', () => {
    const alert = makeAlert({
      id: 'alert-2',
      severity: 'WARNING',
      type: 'PNL_THRESHOLD',
      threshold: 100000,
      currentValue: 120000,
      bookId: 'equity-book',
      message: 'PNL_THRESHOLD GREATER_THAN 100000',
    })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    const item = screen.getByTestId('alert-item-alert-2')
    expect(item.className).toContain('border-amber-200')
    expect(item.className).toContain('bg-amber-50')
    expect(item).toHaveTextContent('WARNING: Daily P&L exceeded $100,000 limit — current: $120,000 (equity-book)')
  })

  it('renders other severity alerts with slate styling and no severity prefix', () => {
    const alert = makeAlert({
      id: 'alert-3',
      severity: 'INFO',
      type: 'SYSTEM_INFO',
      message: 'Informational alert',
    })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    const item = screen.getByTestId('alert-item-alert-3')
    expect(item.className).toContain('border-slate-200')
    expect(item.className).toContain('bg-slate-50')
    expect(item).toHaveTextContent('Informational alert')
    expect(item).not.toHaveTextContent('INFO:')
  })

  it('adds role=alert to critical alerts for accessibility', () => {
    const alert = makeAlert({ severity: 'CRITICAL' })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    const item = screen.getByTestId('alert-item-alert-1')
    expect(item).toHaveAttribute('role', 'alert')
  })

  it('does not add role=alert to non-critical alerts', () => {
    const alert = makeAlert({ id: 'a-warn', severity: 'WARNING' })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    const item = screen.getByTestId('alert-item-a-warn')
    expect(item).not.toHaveAttribute('role')
  })

  it('shows relative time for triggeredAt', () => {
    const alert = makeAlert({
      triggeredAt: '2026-02-28T11:58:00Z', // 2 min ago
    })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    expect(screen.getByText('2 min ago')).toBeInTheDocument()
  })

  it('shows "just now" for alerts less than 60 seconds ago', () => {
    const alert = makeAlert({
      triggeredAt: '2026-02-28T11:59:30Z', // 30 seconds ago
    })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    expect(screen.getByText('just now')).toBeInTheDocument()
  })

  it('shows hours for alerts more than 60 minutes ago', () => {
    const alert = makeAlert({
      triggeredAt: '2026-02-28T09:00:00Z', // 3 hours ago
    })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    expect(screen.getByText('3 hours ago')).toBeInTheDocument()
  })

  it('shows days for alerts more than 24 hours ago', () => {
    const alert = makeAlert({
      triggeredAt: '2026-02-26T12:00:00Z', // 2 days ago
    })

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    expect(screen.getByText('2 days ago')).toBeInTheDocument()
  })

  it('calls onDismiss with alert id when dismiss button is clicked', () => {
    const onDismiss = vi.fn()
    const alert = makeAlert()

    render(<RiskAlertBanner alerts={[alert]} onDismiss={onDismiss} />)

    fireEvent.click(screen.getByTestId('alert-dismiss-alert-1'))

    expect(onDismiss).toHaveBeenCalledWith('alert-1')
  })

  it('shows at most 3 alerts', () => {
    const alerts = [
      makeAlert({ id: 'a1' }),
      makeAlert({ id: 'a2' }),
      makeAlert({ id: 'a3' }),
      makeAlert({ id: 'a4' }),
    ]

    render(<RiskAlertBanner alerts={alerts} onDismiss={vi.fn()} />)

    expect(screen.getByTestId('alert-item-a1')).toBeInTheDocument()
    expect(screen.getByTestId('alert-item-a2')).toBeInTheDocument()
    expect(screen.getByTestId('alert-item-a3')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-item-a4')).not.toBeInTheDocument()
  })

  it('shows "View all in Alerts tab" link when more than 3 alerts', () => {
    const alerts = [
      makeAlert({ id: 'a1' }),
      makeAlert({ id: 'a2' }),
      makeAlert({ id: 'a3' }),
      makeAlert({ id: 'a4' }),
    ]

    render(<RiskAlertBanner alerts={alerts} onDismiss={vi.fn()} />)

    expect(screen.getByText('View all 4 in Alerts tab')).toBeInTheDocument()
  })

  it('renders the overflow line as a real button when onViewAll is supplied', () => {
    const onViewAll = vi.fn()
    const alerts = [
      makeAlert({ id: 'a1' }),
      makeAlert({ id: 'a2' }),
      makeAlert({ id: 'a3' }),
      makeAlert({ id: 'a4' }),
    ]

    render(<RiskAlertBanner alerts={alerts} onDismiss={vi.fn()} onViewAll={onViewAll} />)

    const link = screen.getByTestId('risk-alert-banner-view-all')
    fireEvent.click(link)
    expect(onViewAll).toHaveBeenCalledTimes(1)
  })

  it('does not show "View all" link when 3 or fewer alerts', () => {
    const alerts = [makeAlert({ id: 'a1' }), makeAlert({ id: 'a2' })]

    render(<RiskAlertBanner alerts={alerts} onDismiss={vi.fn()} />)

    expect(screen.queryByText(/View all .*in Alerts tab/)).not.toBeInTheDocument()
  })

  it('marks rows older than 4h as stale and fresh rows as not stale', () => {
    const alerts = [
      makeAlert({ id: 'old', triggeredAt: new Date(Date.now() - 5 * 60 * 60 * 1000).toISOString() }),
      makeAlert({ id: 'fresh', triggeredAt: new Date(Date.now() - 60_000).toISOString() }),
    ]

    render(<RiskAlertBanner alerts={alerts} onDismiss={vi.fn()} />)

    expect(screen.getByTestId('alert-item-old')).toHaveAttribute('data-stale', 'true')
    expect(screen.getByTestId('alert-item-fresh')).toHaveAttribute('data-stale', 'false')
  })

  it('has data-testid on the container', () => {
    const alert = makeAlert()

    render(<RiskAlertBanner alerts={[alert]} onDismiss={vi.fn()} />)

    expect(screen.getByTestId('risk-alert-banner')).toBeInTheDocument()
  })
})
