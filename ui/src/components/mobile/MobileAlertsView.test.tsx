import { render, screen, fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlertEventDto } from '../../types'
import type { UseNotificationsResult } from '../../hooks/useNotifications'

vi.mock('../../hooks/useNotifications')

import { MobileAlertsView } from './MobileAlertsView'
import { useNotifications } from '../../hooks/useNotifications'

const mockUseNotifications = vi.mocked(useNotifications)

function alert(overrides: Partial<AlertEventDto> = {}): AlertEventDto {
  return {
    id: 'alert-1',
    ruleId: 'rule-1',
    ruleName: 'VaR breach',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR exceeded the limit',
    currentValue: 1_500_000,
    threshold: 1_000_000,
    bookId: 'BOOK-EQ',
    // Recent so relative-time renders deterministically-ish.
    triggeredAt: new Date().toISOString(),
    status: 'TRIGGERED',
    ...overrides,
  }
}

function setNotifications(overrides: Partial<UseNotificationsResult> = {}) {
  mockUseNotifications.mockReturnValue({
    rules: [],
    alerts: [alert()],
    loading: false,
    error: null,
    connected: true,
    createRule: vi.fn(),
    deleteRule: vi.fn(),
    acknowledgeAlert: vi.fn(),
    escalateAlert: vi.fn(),
    resolveAlert: vi.fn(),
    snoozeAlert: vi.fn(),
    ...overrides,
  })
}

describe('MobileAlertsView', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    setNotifications()
  })

  it('renders one card per alert showing the book and severity', () => {
    setNotifications({
      alerts: [
        alert({ id: 'a', bookId: 'BOOK-EQ', severity: 'CRITICAL' }),
        alert({ id: 'b', bookId: 'BOOK-FX', severity: 'WARNING' }),
      ],
    })

    render(<MobileAlertsView />)

    expect(screen.getByTestId('mobile-alerts-view')).toBeInTheDocument()
    expect(screen.getByTestId('mobile-alert-card-a')).toHaveTextContent('BOOK-EQ')
    expect(screen.getByTestId('mobile-alert-card-a')).toHaveTextContent('CRITICAL')
    expect(screen.getByTestId('mobile-alert-card-b')).toHaveTextContent('BOOK-FX')
    expect(screen.getByTestId('mobile-alert-card-b')).toHaveTextContent('WARNING')
  })

  it('paints a CRITICAL card with the critical background and others differently', () => {
    setNotifications({
      alerts: [
        alert({ id: 'crit', severity: 'CRITICAL' }),
        alert({ id: 'warn', severity: 'WARNING' }),
        alert({ id: 'info', severity: 'INFO' }),
      ],
    })

    render(<MobileAlertsView />)

    const crit = screen.getByTestId('mobile-alert-card-crit').className
    const warn = screen.getByTestId('mobile-alert-card-warn').className
    const info = screen.getByTestId('mobile-alert-card-info').className

    expect(crit).toContain('red')
    expect(warn).not.toContain('bg-red')
    expect(info).not.toContain('bg-red')
    expect(warn).not.toEqual(crit)
    expect(info).not.toEqual(warn)
  })

  it('sorts alerts by severity (critical first) regardless of arrival order', () => {
    setNotifications({
      alerts: [
        alert({ id: 'info', severity: 'INFO' }),
        alert({ id: 'crit', severity: 'CRITICAL' }),
        alert({ id: 'warn', severity: 'WARNING' }),
      ],
    })

    render(<MobileAlertsView />)

    const cards = screen.getAllByTestId(/^mobile-alert-card-/)
    expect(cards.map((c) => c.getAttribute('data-testid'))).toEqual([
      'mobile-alert-card-crit',
      'mobile-alert-card-warn',
      'mobile-alert-card-info',
    ])
  })

  it('opens a detail panel when a card is tapped and closes it again', () => {
    render(<MobileAlertsView />)

    expect(screen.queryByTestId('mobile-alert-detail')).not.toBeInTheDocument()

    fireEvent.click(screen.getByTestId('mobile-alert-card-alert-1'))

    const detail = screen.getByTestId('mobile-alert-detail')
    expect(detail).toBeInTheDocument()
    expect(detail).toHaveTextContent('VaR exceeded the limit')

    fireEvent.click(screen.getByTestId('mobile-alert-detail-close'))

    expect(screen.queryByTestId('mobile-alert-detail')).not.toBeInTheDocument()
  })

  it('shows no acknowledge, escalate, resolve, or snooze controls (read-only)', () => {
    render(<MobileAlertsView />)
    fireEvent.click(screen.getByTestId('mobile-alert-card-alert-1'))

    expect(screen.queryByRole('button', { name: /acknowledge/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /escalate/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /resolve/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /snooze/i })).not.toBeInTheDocument()
  })

  it('shows a reassuring empty state when the feed is healthy and there are no alerts', () => {
    setNotifications({ alerts: [], connected: true, error: null })

    render(<MobileAlertsView />)

    const empty = screen.getByTestId('mobile-alerts-empty')
    expect(empty).toBeInTheDocument()
    expect(empty).toHaveTextContent('No active alerts')
    expect(empty).toHaveTextContent(/all caught up/i)
    // The muted "caught up" subtitle must clear WCAG AA contrast on the
    // near-black dark surface: slate-400 (brighter), not slate-500.
    const subtitle = screen.getByText(/all caught up/i)
    expect(subtitle.className).toContain('dark:text-slate-400')
    expect(subtitle.className).not.toContain('dark:text-slate-500')
    // The reassurance is earned: a "feed live" affordance is shown.
    expect(screen.getByTestId('mobile-alerts-feed-status')).toHaveTextContent(/live/i)
    // No warning copy when the feed is healthy.
    expect(empty).not.toHaveTextContent(/unavailable/i)
  })

  it('warns instead of reassuring when the alert feed is disconnected', () => {
    setNotifications({ alerts: [], connected: false, error: null })

    render(<MobileAlertsView />)

    const empty = screen.getByTestId('mobile-alerts-empty')
    expect(empty).toBeInTheDocument()
    // Does NOT falsely reassure.
    expect(empty).not.toHaveTextContent(/all caught up/i)
    // Surfaces a feed-health warning instead.
    expect(empty).toHaveTextContent(/feed unavailable/i)
    // Rendered in amber to read as a warning, not a neutral note.
    expect(empty.className).toMatch(/amber|yellow/)
  })

  it('warns when the snapshot fetch errored even if the socket reports connected', () => {
    setNotifications({ alerts: [], connected: true, error: 'network down' })

    render(<MobileAlertsView />)

    const empty = screen.getByTestId('mobile-alerts-empty')
    expect(empty).not.toHaveTextContent(/all caught up/i)
    expect(empty).toHaveTextContent(/feed unavailable/i)
  })

  it('shows a loading state while alerts are loading', () => {
    setNotifications({ alerts: [], loading: true })

    render(<MobileAlertsView />)

    expect(screen.getByTestId('mobile-alerts-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('mobile-alerts-empty')).not.toBeInTheDocument()
  })
})
