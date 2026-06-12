import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AlertEventDto } from '../types'
import { LimitsBreachHeader } from './LimitsBreachHeader'

const NOW = new Date('2026-05-18T12:00:00Z').getTime()

function alert(overrides: Partial<AlertEventDto> = {}): AlertEventDto {
  return {
    id: 'a-1',
    ruleId: 'rule-1',
    ruleName: 'VaR Limit',
    type: 'VAR_BREACH',
    severity: 'WARNING',
    message: 'VaR approaching limit',
    currentValue: 850_000,
    threshold: 1_000_000,
    bookId: 'book-1',
    triggeredAt: new Date(NOW - 5 * 60_000).toISOString(),
    status: 'TRIGGERED',
    ...overrides,
  }
}

describe('LimitsBreachHeader', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the breach count for alerts whose currentValue exceeds threshold', () => {
    render(
      <LimitsBreachHeader
        alerts={[
          alert({
            id: 'breach-1',
            bookId: 'book-1',
            severity: 'CRITICAL',
            currentValue: 1_300_000,
            threshold: 1_000_000,
          }),
          alert({
            id: 'breach-2',
            bookId: 'book-2',
            severity: 'CRITICAL',
            currentValue: 250_000,
            threshold: 100_000,
          }),
        ]}
      />,
    )

    expect(screen.getByTestId('limits-breach-header')).toBeInTheDocument()
    expect(screen.getByTestId('breach-count')).toHaveTextContent('2')
  })

  it('counts a breached condition once across repeated firings and severity shadows', () => {
    // UX review: the chip said "BREACHES 20" while only a handful of books
    // were actually in breach — every repeated firing and every WARNING
    // shadow of a CRITICAL inflated the count. The chip counts breached
    // conditions (book + alert type), not alert events.
    render(
      <LimitsBreachHeader
        alerts={[
          alert({ id: 'f-1', bookId: 'macro-hedge', severity: 'CRITICAL', currentValue: 30_447_680, threshold: 1_000_000 }),
          alert({ id: 'f-2', bookId: 'macro-hedge', severity: 'CRITICAL', currentValue: 30_447_700, threshold: 1_000_000 }),
          alert({ id: 'f-3', bookId: 'macro-hedge', severity: 'WARNING', currentValue: 30_447_680, threshold: 750_000 }),
          alert({ id: 'f-4', bookId: 'multi-asset', severity: 'CRITICAL', currentValue: 2_222_774, threshold: 1_000_000 }),
        ]}
      />,
    )

    expect(screen.getByTestId('breach-count')).toHaveTextContent('2')
  })

  it('renders the near-breach count for alerts at 80%–100% utilisation', () => {
    render(
      <LimitsBreachHeader
        alerts={[
          alert({ id: 'nb-1', bookId: 'book-1', currentValue: 820_000, threshold: 1_000_000 }),
          alert({ id: 'nb-2', bookId: 'book-2', currentValue: 999_000, threshold: 1_000_000 }),
          // Below 80% — should not count
          alert({ id: 'safe-1', currentValue: 500_000, threshold: 1_000_000 }),
          // Above 100% — should be a breach, not a near-breach
          alert({
            id: 'breach-1',
            severity: 'CRITICAL',
            currentValue: 1_300_000,
            threshold: 1_000_000,
          }),
        ]}
      />,
    )

    expect(screen.getByTestId('near-breach-count')).toHaveTextContent('2')
    expect(screen.getByTestId('breach-count')).toHaveTextContent('1')
  })

  it('counts WARNING and CRITICAL alerts within the last 30 minutes as recent', () => {
    render(
      <LimitsBreachHeader
        alerts={[
          alert({
            id: 'recent-1',
            severity: 'WARNING',
            triggeredAt: new Date(NOW - 5 * 60_000).toISOString(),
          }),
          alert({
            id: 'recent-2',
            severity: 'CRITICAL',
            currentValue: 1_300_000,
            threshold: 1_000_000,
            triggeredAt: new Date(NOW - 15 * 60_000).toISOString(),
          }),
        ]}
      />,
    )

    expect(screen.getByTestId('recent-alert-count')).toHaveTextContent('2')
  })

  it('filters out alerts older than 30 minutes from the recent-alerts count', () => {
    render(
      <LimitsBreachHeader
        alerts={[
          alert({
            id: 'old-1',
            severity: 'WARNING',
            triggeredAt: new Date(NOW - 45 * 60_000).toISOString(),
          }),
          alert({
            id: 'recent-1',
            severity: 'WARNING',
            triggeredAt: new Date(NOW - 10 * 60_000).toISOString(),
          }),
        ]}
      />,
    )

    expect(screen.getByTestId('recent-alert-count')).toHaveTextContent('1')
  })

  it('filters out INFO-severity alerts from the recent-alerts count', () => {
    render(
      <LimitsBreachHeader
        alerts={[
          alert({
            id: 'info-1',
            severity: 'INFO',
            currentValue: 100,
            threshold: 1000,
            triggeredAt: new Date(NOW - 1 * 60_000).toISOString(),
          }),
          alert({
            id: 'warn-1',
            severity: 'WARNING',
            triggeredAt: new Date(NOW - 1 * 60_000).toISOString(),
          }),
        ]}
      />,
    )

    expect(screen.getByTestId('recent-alert-count')).toHaveTextContent('1')
  })

  it('shows an "all clear" indicator when all three counts are zero', () => {
    render(<LimitsBreachHeader alerts={[]} />)

    expect(screen.getByTestId('limits-breach-header-all-clear')).toBeInTheDocument()
    expect(screen.getByTestId('breach-count')).toHaveTextContent('0')
    expect(screen.getByTestId('near-breach-count')).toHaveTextContent('0')
    expect(screen.getByTestId('recent-alert-count')).toHaveTextContent('0')
  })

  it('invokes onScrollToLimits when the breach chip is clicked', () => {
    const onScrollToLimits = vi.fn()

    render(
      <LimitsBreachHeader
        alerts={[
          alert({
            id: 'breach-1',
            severity: 'CRITICAL',
            currentValue: 1_300_000,
            threshold: 1_000_000,
          }),
        ]}
        onScrollToLimits={onScrollToLimits}
      />,
    )

    fireEvent.click(screen.getByTestId('breach-chip'))
    expect(onScrollToLimits).toHaveBeenCalledTimes(1)
  })

  it('invokes onShowAlerts when the recent-alerts chip is clicked', () => {
    const onShowAlerts = vi.fn()

    render(
      <LimitsBreachHeader
        alerts={[
          alert({
            id: 'recent-1',
            severity: 'WARNING',
            triggeredAt: new Date(NOW - 5 * 60_000).toISOString(),
          }),
        ]}
        onShowAlerts={onShowAlerts}
      />,
    )

    fireEvent.click(screen.getByTestId('recent-alert-chip'))
    expect(onShowAlerts).toHaveBeenCalledTimes(1)
  })

  it('ignores resolved alerts when counting breaches and near-breaches', () => {
    render(
      <LimitsBreachHeader
        alerts={[
          alert({
            id: 'resolved-breach',
            severity: 'CRITICAL',
            status: 'RESOLVED',
            currentValue: 1_300_000,
            threshold: 1_000_000,
          }),
          alert({
            id: 'resolved-near',
            severity: 'WARNING',
            status: 'RESOLVED',
            currentValue: 900_000,
            threshold: 1_000_000,
          }),
        ]}
      />,
    )

    expect(screen.getByTestId('breach-count')).toHaveTextContent('0')
    expect(screen.getByTestId('near-breach-count')).toHaveTextContent('0')
  })
})
