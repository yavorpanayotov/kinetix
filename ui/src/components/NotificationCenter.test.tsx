import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { AlertRuleDto, AlertEventDto } from '../types'
import { NotificationCenter } from './NotificationCenter'

const sampleRules: AlertRuleDto[] = [
  {
    id: 'rule-1',
    name: 'VaR Limit',
    type: 'VAR_BREACH',
    threshold: 100000,
    operator: 'GREATER_THAN',
    severity: 'CRITICAL',
    channels: ['IN_APP', 'EMAIL'],
    enabled: true,
  },
  {
    id: 'rule-2',
    name: 'ES Warning',
    type: 'PNL_THRESHOLD',
    threshold: 200000,
    operator: 'GREATER_THAN',
    severity: 'WARNING',
    channels: ['IN_APP'],
    enabled: false,
  },
]

const sampleAlerts: AlertEventDto[] = [
  {
    id: 'evt-1',
    ruleId: 'rule-1',
    ruleName: 'VaR Limit',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR exceeded threshold',
    currentValue: 150000,
    threshold: 100000,
    bookId: 'book-1',
    triggeredAt: '2025-01-15T10:00:00Z',
    status: 'TRIGGERED',
  },
  {
    id: 'evt-2',
    ruleId: 'rule-2',
    ruleName: 'ES Warning',
    type: 'PNL_THRESHOLD',
    severity: 'WARNING',
    message: 'Expected shortfall exceeded',
    currentValue: 250000,
    threshold: 200000,
    bookId: 'book-1',
    triggeredAt: '2025-01-15T10:05:00Z',
    status: 'TRIGGERED',
  },
]

describe('NotificationCenter', () => {
  it('renders alert rules table', () => {
    render(
      <NotificationCenter
        rules={sampleRules}
        alerts={[]}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    const table = screen.getByTestId('rules-table')
    expect(table).toBeInTheDocument()
    const rows = table.querySelectorAll('tbody tr')
    expect(rows.length).toBe(2)
  })

  it('renders create rule form', () => {
    render(
      <NotificationCenter
        rules={[]}
        alerts={[]}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    expect(screen.getByTestId('create-rule-form')).toBeInTheDocument()
    expect(screen.getByTestId('rule-name-input')).toBeInTheDocument()
    expect(screen.getByTestId('rule-type-select')).toBeInTheDocument()
    expect(screen.getByTestId('rule-threshold-input')).toBeInTheDocument()
    expect(screen.getByTestId('create-rule-btn')).toBeInTheDocument()
  })

  it('does not offer DATA_STALENESS as an alert type option', () => {
    render(
      <NotificationCenter
        rules={[]}
        alerts={[]}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    const typeSelect = screen.getByTestId('rule-type-select')
    const options = Array.from(typeSelect.querySelectorAll('option')).map((o) => o.value)
    expect(options).not.toContain('DATA_STALENESS')
  })

  it('does not offer EQUALS as an operator option in the create-rule form', () => {
    // "Equal to" for floating-point risk values never fires — removed from new-rule creation.
    render(
      <NotificationCenter
        rules={[]}
        alerts={[]}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    const operatorSelect = screen.getByTestId('rule-operator-select')
    const optionValues = Array.from(operatorSelect.querySelectorAll('option')).map((o) => o.value)
    const optionLabels = Array.from(operatorSelect.querySelectorAll('option')).map((o) => o.textContent)

    expect(optionValues).not.toContain('EQUALS')
    expect(optionLabels).not.toContain('Equal to')

    // Sanity-check that the other operators are still present.
    expect(optionValues).toContain('GREATER_THAN')
    expect(optionValues).toContain('LESS_THAN')
  })

  it('renders recent alerts list', () => {
    render(
      <NotificationCenter
        rules={[]}
        alerts={sampleAlerts}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    const alertsList = screen.getByTestId('alerts-list')
    expect(alertsList).toBeInTheDocument()
    expect(alertsList.children.length).toBe(2)
  })

  it('shows severity badges', () => {
    render(
      <NotificationCenter
        rules={[]}
        alerts={sampleAlerts}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    const criticalBadge = screen.getByTestId('severity-badge-evt-1')
    expect(criticalBadge).toHaveTextContent('CRITICAL')
    expect(criticalBadge.className).toContain('bg-red')

    const warningBadge = screen.getByTestId('severity-badge-evt-2')
    expect(warningBadge).toHaveTextContent('WARNING')
    expect(warningBadge.className).toContain('bg-yellow')
  })

  it('shows loading state', () => {
    render(
      <NotificationCenter
        rules={[]}
        alerts={[]}
        loading={true}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    expect(screen.getByTestId('notification-loading')).toBeInTheDocument()
  })

  it('shows error state', () => {
    render(
      <NotificationCenter
        rules={[]}
        alerts={[]}
        loading={false}
        error="Failed to load"
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    expect(screen.getByTestId('notification-error')).toBeInTheDocument()
    expect(screen.getByTestId('notification-error')).toHaveTextContent('Failed to load')
  })

  it('shows relative timestamps instead of ISO strings', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

    render(
      <NotificationCenter
        rules={[]}
        alerts={sampleAlerts}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    expect(screen.getByText(/2h ago/)).toBeInTheDocument()
    expect(screen.queryByText('2025-01-15T10:00:00Z')).not.toBeInTheDocument()

    vi.useRealTimers()
  })

  it('applies severity border colors to alert cards', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

    render(
      <NotificationCenter
        rules={[]}
        alerts={sampleAlerts}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    const alertsList = screen.getByTestId('alerts-list')
    const cards = alertsList.children
    expect(cards[0].className).toContain('border-yellow-500')
    expect(cards[1].className).toContain('border-red-500')

    vi.useRealTimers()
  })

  it('sorts alerts by recency then severity', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

    const alerts: AlertEventDto[] = [
      {
        ...sampleAlerts[0],
        id: 'evt-old',
        triggeredAt: '2025-01-15T09:00:00Z',
        severity: 'CRITICAL',
      },
      {
        ...sampleAlerts[1],
        id: 'evt-new',
        triggeredAt: '2025-01-15T11:00:00Z',
        severity: 'WARNING',
      },
    ]

    render(
      <NotificationCenter
        rules={[]}
        alerts={alerts}
        loading={false}
        error={null}
        onCreateRule={() => {}}
        onDeleteRule={() => {}}
      />,
    )

    const alertsList = screen.getByTestId('alerts-list')
    const badges = alertsList.querySelectorAll('[data-testid^="severity-badge"]')
    // Most recent first (WARNING at 11:00), then older (CRITICAL at 09:00)
    expect(badges[0]).toHaveTextContent('WARNING')
    expect(badges[1]).toHaveTextContent('CRITICAL')

    vi.useRealTimers()
  })

  describe('CSV export', () => {
    it('should have a CSV export button for alerts', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={sampleAlerts}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      expect(screen.getByTestId('alerts-csv-export')).toBeInTheDocument()
    })
  })

  describe('delete confirmation', () => {
    it('should show confirmation dialog when delete button clicked', () => {
      const onDeleteRule = vi.fn()
      render(
        <NotificationCenter
          rules={sampleRules}
          alerts={[]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={onDeleteRule}
        />,
      )

      fireEvent.click(screen.getByTestId('delete-rule-rule-1'))

      expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument()
      expect(onDeleteRule).not.toHaveBeenCalled()
    })

    it('should not delete rule if dialog cancelled', () => {
      const onDeleteRule = vi.fn()
      render(
        <NotificationCenter
          rules={sampleRules}
          alerts={[]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={onDeleteRule}
        />,
      )

      fireEvent.click(screen.getByTestId('delete-rule-rule-1'))
      fireEvent.click(screen.getByTestId('confirm-dialog-cancel'))

      expect(onDeleteRule).not.toHaveBeenCalled()
      expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument()
    })

    it('should delete rule if dialog confirmed', () => {
      const onDeleteRule = vi.fn()
      render(
        <NotificationCenter
          rules={sampleRules}
          alerts={[]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={onDeleteRule}
        />,
      )

      fireEvent.click(screen.getByTestId('delete-rule-rule-1'))
      fireEvent.click(screen.getByTestId('confirm-dialog-confirm'))

      expect(onDeleteRule).toHaveBeenCalledWith('rule-1')
      expect(screen.queryByTestId('confirm-dialog')).not.toBeInTheDocument()
    })
  })

  describe('acknowledge action', () => {
    const triggeredAlert: AlertEventDto = {
      id: 'ack-evt-1',
      ruleId: 'rule-1',
      ruleName: 'VaR Limit',
      type: 'VAR_BREACH',
      severity: 'CRITICAL',
      message: 'VaR exceeded threshold',
      currentValue: 150000,
      threshold: 100000,
      bookId: 'book-1',
      triggeredAt: '2025-01-15T10:00:00Z',
      status: 'TRIGGERED',
    }

    const acknowledgedAlert: AlertEventDto = {
      ...triggeredAlert,
      id: 'ack-evt-2',
      status: 'ACKNOWLEDGED',
    }

    const resolvedAlert: AlertEventDto = {
      ...triggeredAlert,
      id: 'ack-evt-3',
      status: 'RESOLVED',
      resolvedAt: '2025-01-15T11:00:00Z',
    }

    it('renders a lifecycle status badge for each alert', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert, acknowledgedAlert, resolvedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onAcknowledge={async () => {}}
        />,
      )

      expect(screen.getByTestId('status-badge-ack-evt-1')).toHaveTextContent('TRIGGERED')
      expect(screen.getByTestId('status-badge-ack-evt-2')).toHaveTextContent('ACKNOWLEDGED')
      expect(screen.getByTestId('status-badge-ack-evt-3')).toHaveTextContent('RESOLVED')
    })

    it('renders an Acknowledge button for TRIGGERED alerts only', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert, acknowledgedAlert, resolvedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onAcknowledge={async () => {}}
        />,
      )

      expect(screen.getByTestId('acknowledge-btn-ack-evt-1')).toBeInTheDocument()
      expect(screen.queryByTestId('acknowledge-btn-ack-evt-2')).not.toBeInTheDocument()
      expect(screen.queryByTestId('acknowledge-btn-ack-evt-3')).not.toBeInTheDocument()
    })

    it('clicking Acknowledge opens an inline form with optional note', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onAcknowledge={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('acknowledge-btn-ack-evt-1'))

      expect(screen.getByTestId('acknowledge-form-ack-evt-1')).toBeInTheDocument()
      expect(screen.getByTestId('acknowledge-note-ack-evt-1')).toBeInTheDocument()
      expect(screen.getByTestId('acknowledge-submit-ack-evt-1')).toBeInTheDocument()
      expect(screen.getByTestId('acknowledge-cancel-ack-evt-1')).toBeInTheDocument()
    })

    it('submitting the Acknowledge form calls onAcknowledge with the note', async () => {
      const onAcknowledge = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onAcknowledge={onAcknowledge}
        />,
      )

      fireEvent.click(screen.getByTestId('acknowledge-btn-ack-evt-1'))
      fireEvent.change(screen.getByTestId('acknowledge-note-ack-evt-1'), {
        target: { value: 'investigating' },
      })
      fireEvent.click(screen.getByTestId('acknowledge-submit-ack-evt-1'))

      await waitFor(() => {
        expect(onAcknowledge).toHaveBeenCalledWith('ack-evt-1', 'investigating')
      })
    })

    it('submitting with no note passes an empty/undefined note', async () => {
      const onAcknowledge = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onAcknowledge={onAcknowledge}
        />,
      )

      fireEvent.click(screen.getByTestId('acknowledge-btn-ack-evt-1'))
      fireEvent.click(screen.getByTestId('acknowledge-submit-ack-evt-1'))

      await waitFor(() => {
        expect(onAcknowledge).toHaveBeenCalledTimes(1)
      })
      const [, note] = onAcknowledge.mock.calls[0]
      // empty string or undefined is acceptable
      expect(note === undefined || note === '').toBe(true)
    })

    it('Cancel closes the Acknowledge form without calling the callback', () => {
      const onAcknowledge = vi.fn()
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onAcknowledge={onAcknowledge}
        />,
      )

      fireEvent.click(screen.getByTestId('acknowledge-btn-ack-evt-1'))
      fireEvent.click(screen.getByTestId('acknowledge-cancel-ack-evt-1'))

      expect(screen.queryByTestId('acknowledge-form-ack-evt-1')).not.toBeInTheDocument()
      expect(onAcknowledge).not.toHaveBeenCalled()
    })
  })

  describe('escalation', () => {
    const escalatedAlert: AlertEventDto = {
      id: 'esc-1',
      ruleId: 'rule-1',
      ruleName: 'VaR Limit',
      type: 'VAR_BREACH',
      severity: 'CRITICAL',
      message: 'VaR breach not acknowledged in time',
      currentValue: 250000,
      threshold: 100000,
      bookId: 'book-1',
      triggeredAt: '2025-01-15T09:00:00Z',
      status: 'ESCALATED',
      escalatedAt: '2025-01-15T09:35:00Z',
      escalatedTo: 'risk-manager,cro',
    }

    it('shows ESCALATED status filter option', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      expect(screen.getByTestId('status-filter-escalated')).toBeInTheDocument()
    })

    it('shows escalation badge (orange) on escalated alerts', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[escalatedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      const badge = screen.getByTestId('escalation-badge-esc-1')
      expect(badge).toBeInTheDocument()
      expect(badge.className).toContain('orange')
    })

    it('shows escalatedTo in escalated alert detail', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[escalatedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      expect(screen.getByTestId('escalated-to-esc-1')).toHaveTextContent('risk-manager,cro')
    })

    it('shows escalatedAt in escalated alert detail', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

      render(
        <NotificationCenter
          rules={[]}
          alerts={[escalatedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      expect(screen.getByTestId('escalated-at-esc-1')).toBeInTheDocument()

      vi.useRealTimers()
    })

    it('ESCALATED filter shows only escalated alerts', () => {
      const triggeredAlert: AlertEventDto = { ...sampleAlerts[0], id: 'trig-1', status: 'TRIGGERED' }

      render(
        <NotificationCenter
          rules={[]}
          alerts={[escalatedAlert, triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('status-filter-escalated'))

      const alertsList = screen.getByTestId('alerts-list')
      expect(alertsList.children.length).toBe(1)
      expect(screen.getByTestId('escalation-badge-esc-1')).toBeInTheDocument()
    })
  })
})
