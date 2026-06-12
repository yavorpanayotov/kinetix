import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { AlertRuleDto, AlertEventDto } from '../types'
import type { ChatChunk, ChatRequest } from '../api/copilot'
import { NotificationCenter } from './NotificationCenter'

/**
 * Build a `ReadableStream<ChatChunk>` that emits a delta chunk then a
 * terminal `done` — the streaming-explainer test double for §9.3.
 */
function fakeChatStream(): ReadableStream<ChatChunk> {
  return new ReadableStream<ChatChunk>({
    start(controller) {
      controller.enqueue({ type: 'delta', delta: 'VaR breached its limit.' })
      controller.enqueue({
        type: 'done',
        session_id: 's',
        conversation_id: 'c',
        model: 'canned-chat',
        mode: 'canned',
      })
      controller.close()
    },
  })
}

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

    // Queue ordering puts CRITICAL above WARNING regardless of recency,
    // so the first card is the CRITICAL alert (red border).
    const alertsList = screen.getByTestId('alerts-list')
    const cards = alertsList.children
    expect(cards[0].className).toContain('border-red-500')
    expect(cards[1].className).toContain('border-yellow-500')

    vi.useRealTimers()
  })

  describe('alerts queue ordering and filtering', () => {
    // §3.2 of docs/plans/ui-overhaul.md — Alerts as a queue, not a list.
    // Reference: 2025-01-15T12:00:00Z system time.
    const FIXED_NOW = '2025-01-15T12:00:00Z'

    const criticalNew: AlertEventDto = {
      id: 'q-crit-new',
      ruleId: 'rule-1',
      ruleName: 'VaR Critical',
      type: 'VAR_BREACH',
      severity: 'CRITICAL',
      message: 'CRITICAL recent',
      currentValue: 250000,
      threshold: 100000,
      bookId: 'book-1',
      // 30 minutes before "now"
      triggeredAt: '2025-01-15T11:30:00Z',
      status: 'TRIGGERED',
    }

    const criticalOld: AlertEventDto = {
      ...criticalNew,
      id: 'q-crit-old',
      message: 'CRITICAL old',
      // 3 hours before "now"
      triggeredAt: '2025-01-15T09:00:00Z',
    }

    const warningRecent: AlertEventDto = {
      ...criticalNew,
      id: 'q-warn',
      severity: 'WARNING',
      message: 'WARNING recent',
      // 5 minutes before "now" — strictly newer than the criticals
      triggeredAt: '2025-01-15T11:55:00Z',
      status: 'ACKNOWLEDGED',
    }

    const infoRecent: AlertEventDto = {
      ...criticalNew,
      id: 'q-info',
      severity: 'INFO',
      message: 'INFO recent',
      // 1 minute before "now" — newest of all
      triggeredAt: '2025-01-15T11:59:00Z',
      status: 'TRIGGERED',
    }

    const resolvedRecent: AlertEventDto = {
      ...criticalNew,
      id: 'q-resolved-recent',
      severity: 'WARNING',
      message: 'RESOLVED recent',
      // resolved 6 hours ago — within 24h window
      triggeredAt: '2025-01-15T05:00:00Z',
      status: 'RESOLVED',
      resolvedAt: '2025-01-15T06:00:00Z',
    }

    const resolvedOld: AlertEventDto = {
      ...criticalNew,
      id: 'q-resolved-old',
      severity: 'CRITICAL',
      message: 'RESOLVED old',
      // resolved 36 hours ago — older than the 24h auto-collapse window
      triggeredAt: '2025-01-13T23:00:00Z',
      status: 'RESOLVED',
      resolvedAt: '2025-01-14T00:00:00Z',
    }

    function renderQueue(alerts: AlertEventDto[]) {
      return render(
        <NotificationCenter
          rules={[]}
          alerts={alerts}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )
    }

    it('sorts CRITICAL above WARNING above INFO regardless of recency', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([infoRecent, warningRecent, criticalOld])

      const badges = screen
        .getByTestId('alerts-list')
        .querySelectorAll('[data-testid^="severity-badge-"]')
      expect(badges[0]).toHaveTextContent('CRITICAL')
      expect(badges[1]).toHaveTextContent('WARNING')
      expect(badges[2]).toHaveTextContent('INFO')

      vi.useRealTimers()
    })

    it('paginates the queue at 25 rows so a 60-alert backlog is not one endless page', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      const many = Array.from({ length: 60 }, (_, i) => ({
        ...criticalNew,
        id: `q-bulk-${String(i + 1).padStart(2, '0')}`,
        message: `Bulk alert ${i + 1}`,
      }))
      renderQueue(many)

      const rowCount = () =>
        screen.getByTestId('alerts-list').querySelectorAll('[data-testid^="severity-badge-"]').length
      expect(rowCount()).toBe(25)
      expect(screen.getByTestId('pagination-info')).toHaveTextContent('Page 1 of 3')

      fireEvent.click(screen.getByTestId('pagination-next'))
      expect(rowCount()).toBe(25)
      expect(screen.getByTestId('pagination-info')).toHaveTextContent('Page 2 of 3')

      fireEvent.click(screen.getByTestId('pagination-next'))
      expect(rowCount()).toBe(10)

      vi.useRealTimers()
    })

    it('does not render a pager for 25 or fewer queue rows', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([criticalNew, warningRecent])
      expect(screen.queryByTestId('pagination-controls')).not.toBeInTheDocument()

      vi.useRealTimers()
    })

    it('within a severity bucket, sorts newest first by triggeredAt', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([criticalOld, criticalNew])

      const cards = screen
        .getByTestId('alerts-list')
        .querySelectorAll('[data-testid^="status-badge-"]')
      // First card should be the newer CRITICAL alert.
      expect(cards[0].getAttribute('data-testid')).toBe(
        'status-badge-q-crit-new',
      )
      expect(cards[1].getAttribute('data-testid')).toBe(
        'status-badge-q-crit-old',
      )

      vi.useRealTimers()
    })

    it('hides RESOLVED alerts by default', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([criticalNew, resolvedRecent, resolvedOld])

      const alertsList = screen.getByTestId('alerts-list')
      // Only the CRITICAL TRIGGERED alert is visible by default.
      expect(
        alertsList.querySelectorAll('[data-testid^="status-badge-"]').length,
      ).toBe(1)
      expect(screen.queryByTestId('status-badge-q-resolved-recent')).toBeNull()
      expect(screen.queryByTestId('status-badge-q-resolved-old')).toBeNull()

      vi.useRealTimers()
    })

    it('renders filter chips with counts per status', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([
        criticalNew,
        criticalOld,
        warningRecent, // ACKNOWLEDGED
        infoRecent,
        resolvedRecent,
        resolvedOld,
      ])

      // Counts: TRIGGERED=3 (criticalNew, criticalOld, infoRecent),
      //         ACKNOWLEDGED=1 (warningRecent), ESCALATED=0, RESOLVED=2.
      expect(screen.getByTestId('status-filter-triggered')).toHaveTextContent(
        '3',
      )
      expect(
        screen.getByTestId('status-filter-acknowledged'),
      ).toHaveTextContent('1')
      expect(screen.getByTestId('status-filter-escalated')).toHaveTextContent(
        '0',
      )
      expect(screen.getByTestId('status-filter-resolved')).toHaveTextContent(
        '2',
      )

      vi.useRealTimers()
    })

    it('clicking the RESOLVED chip surfaces resolved alerts', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([criticalNew, resolvedRecent])

      // Initially hidden.
      expect(screen.queryByTestId('status-badge-q-resolved-recent')).toBeNull()

      fireEvent.click(screen.getByTestId('status-filter-resolved'))

      // Now visible (resolvedRecent is within the 24h window).
      expect(
        screen.getByTestId('status-badge-q-resolved-recent'),
      ).toBeInTheDocument()

      vi.useRealTimers()
    })

    it('auto-collapses RESOLVED alerts older than 24h into a single summary row', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([criticalNew, resolvedRecent, resolvedOld])
      // Show RESOLVED in the filter.
      fireEvent.click(screen.getByTestId('status-filter-resolved'))

      // Recent RESOLVED is visible directly.
      expect(
        screen.getByTestId('status-badge-q-resolved-recent'),
      ).toBeInTheDocument()
      // Old RESOLVED is collapsed away from the list.
      expect(screen.queryByTestId('status-badge-q-resolved-old')).toBeNull()
      // Summary row exists and reports the count.
      const summary = screen.getByTestId('older-resolved-summary')
      expect(summary).toBeInTheDocument()
      expect(summary).toHaveTextContent('1')

      vi.useRealTimers()
    })

    it('expanding the older-resolved summary reveals the old RESOLVED alerts', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([criticalNew, resolvedOld])
      fireEvent.click(screen.getByTestId('status-filter-resolved'))
      fireEvent.click(screen.getByTestId('older-resolved-toggle'))

      expect(
        screen.getByTestId('status-badge-q-resolved-old'),
      ).toBeInTheDocument()

      vi.useRealTimers()
    })

    it('toggling a default-on chip removes that status from the visible queue', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(FIXED_NOW))

      renderQueue([criticalNew, warningRecent])

      // Initially both visible (TRIGGERED + ACKNOWLEDGED are default-on).
      expect(
        screen.getByTestId('status-badge-q-crit-new'),
      ).toBeInTheDocument()
      expect(screen.getByTestId('status-badge-q-warn')).toBeInTheDocument()

      // Toggle TRIGGERED off — only the ACKNOWLEDGED warning remains.
      fireEvent.click(screen.getByTestId('status-filter-triggered'))
      expect(screen.queryByTestId('status-badge-q-crit-new')).toBeNull()
      expect(screen.getByTestId('status-badge-q-warn')).toBeInTheDocument()

      vi.useRealTimers()
    })
  })

  describe('create-rule form accessibility', () => {
    // §6.4 of docs/plans/ui-overhaul.md — surfacing field errors to screen
    // readers via aria-invalid + aria-describedby linked inline messages,
    // not just HTML5 `required`.
    it('does not call onCreateRule when a required field is empty', () => {
      const onCreateRule = vi.fn()
      render(
        <NotificationCenter
          rules={[]}
          alerts={[]}
          loading={false}
          error={null}
          onCreateRule={onCreateRule}
          onDeleteRule={() => {}}
        />,
      )

      // Name is empty by default — submitting should surface a validation
      // error rather than firing the callback.
      fireEvent.click(screen.getByTestId('create-rule-btn'))

      expect(onCreateRule).not.toHaveBeenCalled()
    })

    it('marks the empty name field with aria-invalid and an inline error linked via aria-describedby', () => {
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

      const nameInput = screen.getByTestId('rule-name-input')
      // Pristine state: no aria-invalid yet (don't yell at the user before
      // they've tried to submit).
      expect(nameInput.getAttribute('aria-invalid')).not.toBe('true')

      fireEvent.click(screen.getByTestId('create-rule-btn'))

      expect(nameInput.getAttribute('aria-invalid')).toBe('true')

      const describedBy = nameInput.getAttribute('aria-describedby')
      expect(describedBy).toBeTruthy()

      const errorMessage = screen.getByTestId('rule-name-error')
      expect(errorMessage).toBeInTheDocument()
      expect(errorMessage.id).toBe(describedBy)
      expect(errorMessage.textContent).toBeTruthy()
    })

    it('marks the empty threshold field with aria-invalid and an inline error linked via aria-describedby', () => {
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

      const thresholdInput = screen.getByTestId('rule-threshold-input')
      expect(thresholdInput.getAttribute('aria-invalid')).not.toBe('true')

      fireEvent.click(screen.getByTestId('create-rule-btn'))

      expect(thresholdInput.getAttribute('aria-invalid')).toBe('true')

      const describedBy = thresholdInput.getAttribute('aria-describedby')
      expect(describedBy).toBeTruthy()

      const errorMessage = screen.getByTestId('rule-threshold-error')
      expect(errorMessage).toBeInTheDocument()
      expect(errorMessage.id).toBe(describedBy)
      expect(errorMessage.textContent).toBeTruthy()
    })

    it('clears the error and aria-invalid once the field is fixed', () => {
      const onCreateRule = vi.fn()
      render(
        <NotificationCenter
          rules={[]}
          alerts={[]}
          loading={false}
          error={null}
          onCreateRule={onCreateRule}
          onDeleteRule={() => {}}
        />,
      )

      // Trigger validation errors first.
      fireEvent.click(screen.getByTestId('create-rule-btn'))
      expect(screen.getByTestId('rule-name-error')).toBeInTheDocument()
      expect(screen.getByTestId('rule-threshold-error')).toBeInTheDocument()

      // Fix the name field — its error should clear, aria-invalid should drop.
      const nameInput = screen.getByTestId('rule-name-input')
      fireEvent.change(nameInput, { target: { value: 'My Rule' } })
      expect(screen.queryByTestId('rule-name-error')).not.toBeInTheDocument()
      expect(nameInput.getAttribute('aria-invalid')).not.toBe('true')

      // Threshold error still present because that field is still empty.
      expect(screen.getByTestId('rule-threshold-error')).toBeInTheDocument()

      // Fix threshold too — error clears.
      const thresholdInput = screen.getByTestId('rule-threshold-input')
      fireEvent.change(thresholdInput, { target: { value: '100' } })
      expect(screen.queryByTestId('rule-threshold-error')).not.toBeInTheDocument()
      expect(thresholdInput.getAttribute('aria-invalid')).not.toBe('true')
    })

    it('submits successfully when all required fields are populated', () => {
      const onCreateRule = vi.fn()
      render(
        <NotificationCenter
          rules={[]}
          alerts={[]}
          loading={false}
          error={null}
          onCreateRule={onCreateRule}
          onDeleteRule={() => {}}
        />,
      )

      fireEvent.change(screen.getByTestId('rule-name-input'), {
        target: { value: 'My Rule' },
      })
      fireEvent.change(screen.getByTestId('rule-threshold-input'), {
        target: { value: '100' },
      })
      fireEvent.click(screen.getByTestId('create-rule-btn'))

      expect(onCreateRule).toHaveBeenCalledTimes(1)
      expect(onCreateRule).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'My Rule', threshold: 100 }),
      )
    })
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
      // Pin "now" so the resolvedAlert below stays inside the 24h auto-collapse
      // window and renders as a normal row once RESOLVED is surfaced.
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

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

      // RESOLVED is hidden by default under the queue model — surface it
      // before asserting on its badge.
      fireEvent.click(screen.getByTestId('status-filter-resolved'))

      expect(screen.getByTestId('status-badge-ack-evt-1')).toHaveTextContent('TRIGGERED')
      expect(screen.getByTestId('status-badge-ack-evt-2')).toHaveTextContent('ACKNOWLEDGED')
      expect(screen.getByTestId('status-badge-ack-evt-3')).toHaveTextContent('RESOLVED')

      vi.useRealTimers()
    })

    it('renders an Acknowledge button for TRIGGERED alerts only', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

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

      // Surface RESOLVED so we can prove its row also lacks an Acknowledge
      // button.
      fireEvent.click(screen.getByTestId('status-filter-resolved'))

      expect(screen.getByTestId('acknowledge-btn-ack-evt-1')).toBeInTheDocument()
      expect(screen.queryByTestId('acknowledge-btn-ack-evt-2')).not.toBeInTheDocument()
      expect(screen.queryByTestId('acknowledge-btn-ack-evt-3')).not.toBeInTheDocument()

      vi.useRealTimers()
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

    it('ESCALATED filter isolates escalated alerts when other statuses are toggled off', () => {
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

      // Multi-select chip model: ESCALATED + TRIGGERED + ACKNOWLEDGED are on
      // by default. Drop the others to isolate ESCALATED.
      fireEvent.click(screen.getByTestId('status-filter-triggered'))
      fireEvent.click(screen.getByTestId('status-filter-acknowledged'))

      const alertsList = screen.getByTestId('alerts-list')
      expect(alertsList.children.length).toBe(1)
      expect(screen.getByTestId('escalation-badge-esc-1')).toBeInTheDocument()
    })
  })

  describe('escalate and resolve actions', () => {
    // §3.1b.2 of docs/plans/ui-overhaul.md — operator-driven escalate + resolve
    // actions matching the Acknowledge pattern. Visibility follows the backend
    // transition rules:
    //   - Escalate: TRIGGERED or ACKNOWLEDGED only
    //   - Resolve: any non-RESOLVED status

    const triggered: AlertEventDto = {
      id: 'esc-trig',
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

    const acknowledged: AlertEventDto = {
      ...triggered,
      id: 'esc-ack',
      status: 'ACKNOWLEDGED',
    }

    const escalated: AlertEventDto = {
      ...triggered,
      id: 'esc-esc',
      status: 'ESCALATED',
      escalatedAt: '2025-01-15T10:30:00Z',
      escalatedTo: 'risk-manager',
    }

    const resolvedAlert: AlertEventDto = {
      ...triggered,
      id: 'esc-res',
      status: 'RESOLVED',
      resolvedAt: '2025-01-15T11:00:00Z',
    }

    it('renders an Escalate button on TRIGGERED and ACKNOWLEDGED alerts only', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered, acknowledged, escalated, resolvedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onEscalate={async () => {}}
        />,
      )

      // Surface RESOLVED so we can inspect that row too.
      fireEvent.click(screen.getByTestId('status-filter-resolved'))

      expect(screen.getByTestId('escalate-btn-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('escalate-btn-esc-ack')).toBeInTheDocument()
      expect(screen.queryByTestId('escalate-btn-esc-esc')).not.toBeInTheDocument()
      expect(screen.queryByTestId('escalate-btn-esc-res')).not.toBeInTheDocument()

      vi.useRealTimers()
    })

    it('renders a Resolve button on any non-RESOLVED alert', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered, acknowledged, escalated, resolvedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onResolve={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('status-filter-resolved'))

      expect(screen.getByTestId('resolve-btn-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('resolve-btn-esc-ack')).toBeInTheDocument()
      expect(screen.getByTestId('resolve-btn-esc-esc')).toBeInTheDocument()
      expect(screen.queryByTestId('resolve-btn-esc-res')).not.toBeInTheDocument()

      vi.useRealTimers()
    })

    it('omits Escalate / Resolve buttons when the callbacks are not provided', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      expect(screen.queryByTestId('escalate-btn-esc-trig')).not.toBeInTheDocument()
      expect(screen.queryByTestId('resolve-btn-esc-trig')).not.toBeInTheDocument()
    })

    it('clicking Escalate opens an inline form with reason + assignee fields', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onEscalate={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('escalate-btn-esc-trig'))

      expect(screen.getByTestId('escalate-form-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('escalate-reason-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('escalate-assignee-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('escalate-submit-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('escalate-cancel-esc-trig')).toBeInTheDocument()
    })

    it('submitting Escalate without a reason does not call onEscalate', async () => {
      const onEscalate = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onEscalate={onEscalate}
        />,
      )

      fireEvent.click(screen.getByTestId('escalate-btn-esc-trig'))
      fireEvent.click(screen.getByTestId('escalate-submit-esc-trig'))

      // Form should still be open and the callback untouched.
      expect(onEscalate).not.toHaveBeenCalled()
      expect(screen.getByTestId('escalate-form-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('escalate-reason-error-esc-trig')).toBeInTheDocument()
    })

    it('submitting Escalate with a reason calls onEscalate with reason + assignee', async () => {
      const onEscalate = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onEscalate={onEscalate}
        />,
      )

      fireEvent.click(screen.getByTestId('escalate-btn-esc-trig'))
      fireEvent.change(screen.getByTestId('escalate-reason-esc-trig'), {
        target: { value: 'unack for 30 minutes' },
      })
      fireEvent.change(screen.getByTestId('escalate-assignee-esc-trig'), {
        target: { value: 'risk-manager' },
      })
      fireEvent.click(screen.getByTestId('escalate-submit-esc-trig'))

      await waitFor(() => {
        expect(onEscalate).toHaveBeenCalledWith(
          'esc-trig',
          'unack for 30 minutes',
          'risk-manager',
        )
      })
    })

    it('submitting Escalate without an assignee passes undefined', async () => {
      const onEscalate = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onEscalate={onEscalate}
        />,
      )

      fireEvent.click(screen.getByTestId('escalate-btn-esc-trig'))
      fireEvent.change(screen.getByTestId('escalate-reason-esc-trig'), {
        target: { value: 'urgent' },
      })
      fireEvent.click(screen.getByTestId('escalate-submit-esc-trig'))

      await waitFor(() => {
        expect(onEscalate).toHaveBeenCalledTimes(1)
      })
      const [, reason, assignee] = onEscalate.mock.calls[0]
      expect(reason).toBe('urgent')
      expect(assignee === undefined || assignee === '').toBe(true)
    })

    it('Cancel closes the Escalate form without calling the callback', () => {
      const onEscalate = vi.fn()
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onEscalate={onEscalate}
        />,
      )

      fireEvent.click(screen.getByTestId('escalate-btn-esc-trig'))
      fireEvent.click(screen.getByTestId('escalate-cancel-esc-trig'))

      expect(screen.queryByTestId('escalate-form-esc-trig')).not.toBeInTheDocument()
      expect(onEscalate).not.toHaveBeenCalled()
    })

    it('clicking Resolve opens an inline form with a resolution field', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onResolve={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('resolve-btn-esc-trig'))

      expect(screen.getByTestId('resolve-form-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('resolve-text-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('resolve-submit-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('resolve-cancel-esc-trig')).toBeInTheDocument()
    })

    it('submitting Resolve without text does not call onResolve', () => {
      const onResolve = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onResolve={onResolve}
        />,
      )

      fireEvent.click(screen.getByTestId('resolve-btn-esc-trig'))
      fireEvent.click(screen.getByTestId('resolve-submit-esc-trig'))

      expect(onResolve).not.toHaveBeenCalled()
      expect(screen.getByTestId('resolve-form-esc-trig')).toBeInTheDocument()
      expect(screen.getByTestId('resolve-text-error-esc-trig')).toBeInTheDocument()
    })

    it('submitting Resolve with text calls onResolve with the resolutionText', async () => {
      const onResolve = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onResolve={onResolve}
        />,
      )

      fireEvent.click(screen.getByTestId('resolve-btn-esc-trig'))
      fireEvent.change(screen.getByTestId('resolve-text-esc-trig'), {
        target: { value: 'positions reduced' },
      })
      fireEvent.click(screen.getByTestId('resolve-submit-esc-trig'))

      await waitFor(() => {
        expect(onResolve).toHaveBeenCalledWith('esc-trig', 'positions reduced')
      })
    })

    it('Cancel closes the Resolve form without calling the callback', () => {
      const onResolve = vi.fn()
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onResolve={onResolve}
        />,
      )

      fireEvent.click(screen.getByTestId('resolve-btn-esc-trig'))
      fireEvent.click(screen.getByTestId('resolve-cancel-esc-trig'))

      expect(screen.queryByTestId('resolve-form-esc-trig')).not.toBeInTheDocument()
      expect(onResolve).not.toHaveBeenCalled()
    })
  })

  describe('snooze action', () => {
    // §3.1b.4 of docs/plans/ui-overhaul.md — operator-driven snooze action with
    // preset offsets (1h / 4h / 24h / Until tomorrow).
    //
    // Visibility: any non-RESOLVED alert.
    // Preset click computes an ISO timestamp at +offset from "now" and
    // dispatches onSnooze(alertId, isoTimestamp).

    const triggered: AlertEventDto = {
      id: 'sn-trig',
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

    const acknowledged: AlertEventDto = {
      ...triggered,
      id: 'sn-ack',
      status: 'ACKNOWLEDGED',
    }

    const escalated: AlertEventDto = {
      ...triggered,
      id: 'sn-esc',
      status: 'ESCALATED',
      escalatedAt: '2025-01-15T10:30:00Z',
      escalatedTo: 'risk-manager',
    }

    const resolvedAlert: AlertEventDto = {
      ...triggered,
      id: 'sn-res',
      status: 'RESOLVED',
      resolvedAt: '2025-01-15T11:00:00Z',
    }

    it('renders a Snooze button on TRIGGERED, ACKNOWLEDGED, and ESCALATED alerts but not RESOLVED', () => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2025-01-15T12:00:00Z'))

      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered, acknowledged, escalated, resolvedAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('status-filter-resolved'))

      expect(screen.getByTestId('snooze-btn-sn-trig')).toBeInTheDocument()
      expect(screen.getByTestId('snooze-btn-sn-ack')).toBeInTheDocument()
      expect(screen.getByTestId('snooze-btn-sn-esc')).toBeInTheDocument()
      expect(screen.queryByTestId('snooze-btn-sn-res')).not.toBeInTheDocument()

      vi.useRealTimers()
    })

    it('omits Snooze button when the callback is not provided', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      expect(screen.queryByTestId('snooze-btn-sn-trig')).not.toBeInTheDocument()
    })

    it('clicking Snooze opens a popover with four preset buttons', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('snooze-btn-sn-trig'))

      expect(screen.getByTestId('snooze-popover-sn-trig')).toBeInTheDocument()
      expect(screen.getByTestId('snooze-preset-1h-sn-trig')).toBeInTheDocument()
      expect(screen.getByTestId('snooze-preset-4h-sn-trig')).toBeInTheDocument()
      expect(screen.getByTestId('snooze-preset-24h-sn-trig')).toBeInTheDocument()
      expect(
        screen.getByTestId('snooze-preset-tomorrow-sn-trig'),
      ).toBeInTheDocument()
    })

    it('clicking the 1h preset calls onSnooze with an ISO timestamp ~1h in the future', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const onSnooze = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={onSnooze}
        />,
      )

      fireEvent.click(screen.getByTestId('snooze-btn-sn-trig'))
      fireEvent.click(screen.getByTestId('snooze-preset-1h-sn-trig'))

      await waitFor(() => {
        expect(onSnooze).toHaveBeenCalledTimes(1)
      })
      const [alertId, iso] = onSnooze.mock.calls[0]
      expect(alertId).toBe('sn-trig')
      const expected = new Date(now.getTime() + 60 * 60 * 1000).getTime()
      const actual = new Date(iso as string).getTime()
      // Tolerate a small skew (within 1s) since the component recomputes "now".
      expect(Math.abs(actual - expected)).toBeLessThanOrEqual(1000)

      vi.useRealTimers()
    })

    it('clicking the 4h preset calls onSnooze with an ISO timestamp ~4h in the future', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const onSnooze = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={onSnooze}
        />,
      )

      fireEvent.click(screen.getByTestId('snooze-btn-sn-trig'))
      fireEvent.click(screen.getByTestId('snooze-preset-4h-sn-trig'))

      await waitFor(() => {
        expect(onSnooze).toHaveBeenCalledTimes(1)
      })
      const [, iso] = onSnooze.mock.calls[0]
      const expected = new Date(now.getTime() + 4 * 60 * 60 * 1000).getTime()
      const actual = new Date(iso as string).getTime()
      expect(Math.abs(actual - expected)).toBeLessThanOrEqual(1000)

      vi.useRealTimers()
    })

    it('clicking the 24h preset calls onSnooze with an ISO timestamp ~24h in the future', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const onSnooze = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={onSnooze}
        />,
      )

      fireEvent.click(screen.getByTestId('snooze-btn-sn-trig'))
      fireEvent.click(screen.getByTestId('snooze-preset-24h-sn-trig'))

      await waitFor(() => {
        expect(onSnooze).toHaveBeenCalledTimes(1)
      })
      const [, iso] = onSnooze.mock.calls[0]
      const expected = new Date(now.getTime() + 24 * 60 * 60 * 1000).getTime()
      const actual = new Date(iso as string).getTime()
      expect(Math.abs(actual - expected)).toBeLessThanOrEqual(1000)

      vi.useRealTimers()
    })

    it('clicking "Until tomorrow" calls onSnooze with next-day 09:00 local time', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const onSnooze = vi.fn().mockResolvedValue(undefined)
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={onSnooze}
        />,
      )

      fireEvent.click(screen.getByTestId('snooze-btn-sn-trig'))
      fireEvent.click(screen.getByTestId('snooze-preset-tomorrow-sn-trig'))

      await waitFor(() => {
        expect(onSnooze).toHaveBeenCalledTimes(1)
      })
      const [, iso] = onSnooze.mock.calls[0]
      const result = new Date(iso as string)
      // Tomorrow at 09:00 local time.
      const tomorrow9 = new Date(now)
      tomorrow9.setDate(tomorrow9.getDate() + 1)
      tomorrow9.setHours(9, 0, 0, 0)
      expect(result.getTime()).toBe(tomorrow9.getTime())

      vi.useRealTimers()
    })

    it('selecting a preset closes the popover', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggered]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('snooze-btn-sn-trig'))
      expect(screen.getByTestId('snooze-popover-sn-trig')).toBeInTheDocument()
      fireEvent.click(screen.getByTestId('snooze-preset-1h-sn-trig'))

      expect(
        screen.queryByTestId('snooze-popover-sn-trig'),
      ).not.toBeInTheDocument()
    })

    it('renders a "Snoozed until …" badge when snoozedUntil is in the future', () => {
      vi.useFakeTimers()
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const snoozed: AlertEventDto = {
        ...triggered,
        id: 'sn-snz',
        snoozedUntil: new Date(now.getTime() + 60 * 60 * 1000).toISOString(),
      }

      render(
        <NotificationCenter
          rules={[]}
          alerts={[snoozed]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={async () => {}}
        />,
      )

      const badge = screen.getByTestId('snoozed-until-badge-sn-snz')
      expect(badge).toBeInTheDocument()
      expect(badge.textContent).toMatch(/Snoozed until/i)

      vi.useRealTimers()
    })

    it('does not render the snoozed-until badge when snoozedUntil is in the past', () => {
      vi.useFakeTimers()
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const snoozed: AlertEventDto = {
        ...triggered,
        id: 'sn-past',
        snoozedUntil: new Date(now.getTime() - 60 * 60 * 1000).toISOString(),
      }

      render(
        <NotificationCenter
          rules={[]}
          alerts={[snoozed]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onSnooze={async () => {}}
        />,
      )

      expect(
        screen.queryByTestId('snoozed-until-badge-sn-past'),
      ).not.toBeInTheDocument()

      vi.useRealTimers()
    })
  })

  describe('cross-tab jump to Risk', () => {
    const triggeredAlert: AlertEventDto = {
      id: 'jump-evt-1',
      ruleId: 'rule-1',
      ruleName: 'VaR Limit',
      type: 'VAR_BREACH',
      severity: 'CRITICAL',
      message: 'VaR breach on book-1',
      currentValue: 250000,
      threshold: 100000,
      bookId: 'book-1',
      triggeredAt: '2025-01-15T09:00:00Z',
      status: 'TRIGGERED',
    }

    it('renders a "Go to Risk" button next to each alert row when onJumpToRisk is provided', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onJumpToRisk={() => {}}
        />,
      )

      expect(screen.getByTestId('jump-to-risk-jump-evt-1')).toBeInTheDocument()
    })

    it('does not render the "Go to Risk" button when onJumpToRisk is omitted', () => {
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
        />,
      )

      expect(screen.queryByTestId('jump-to-risk-jump-evt-1')).not.toBeInTheDocument()
    })

    it('clicking "Go to Risk" calls onJumpToRisk with the alert bookId', () => {
      const onJumpToRisk = vi.fn()
      render(
        <NotificationCenter
          rules={[]}
          alerts={[triggeredAlert]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onJumpToRisk={onJumpToRisk}
        />,
      )

      fireEvent.click(screen.getByTestId('jump-to-risk-jump-evt-1'))

      expect(onJumpToRisk).toHaveBeenCalledWith('book-1')
    })

    it('passes null bookId when the alert has no bookId', () => {
      const onJumpToRisk = vi.fn()
      const alertNoBook: AlertEventDto = { ...triggeredAlert, id: 'jump-evt-2', bookId: '' }
      render(
        <NotificationCenter
          rules={[]}
          alerts={[alertNoBook]}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          onJumpToRisk={onJumpToRisk}
        />,
      )

      fireEvent.click(screen.getByTestId('jump-to-risk-jump-evt-2'))

      expect(onJumpToRisk).toHaveBeenCalledWith(null)
    })
  })

  describe('inline alert explainer', () => {
    // docs/plans/ai-v2.md §9.3 — an <ExplainButton> on every alert/breach row
    // opens an inline <AIInsightPanel> wired to the v2 /chat stream.
    it('renders an Explain button on every alert row', () => {
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

      expect(screen.getByTestId('explain-alert-evt-1')).toBeInTheDocument()
      expect(screen.getByTestId('explain-alert-evt-2')).toBeInTheDocument()
      // No panel before any button is clicked.
      expect(
        screen.queryByTestId('alert-explain-panel-evt-1'),
      ).not.toBeInTheDocument()
    })

    it('opens the streaming insight panel when an alert Explain button is clicked', async () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(
        <NotificationCenter
          rules={[]}
          alerts={sampleAlerts}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          chatFn={chatFn}
        />,
      )

      fireEvent.click(screen.getByTestId('explain-alert-evt-1'))

      expect(
        screen.getByTestId('alert-explain-panel-evt-1'),
      ).toBeInTheDocument()
      await waitFor(() =>
        expect(
          screen.getByTestId('streaming-narrative-text'),
        ).toHaveTextContent('VaR breached its limit.'),
      )
    })

    it('attaches alertId, type, currentValue, threshold and severity to the explain page_context', () => {
      const chatFn = vi.fn<(req: ChatRequest) => ReadableStream<ChatChunk>>(
        () => fakeChatStream(),
      )
      render(
        <NotificationCenter
          rules={[]}
          alerts={sampleAlerts}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          chatFn={chatFn}
        />,
      )

      fireEvent.click(screen.getByTestId('explain-alert-evt-1'))

      expect(chatFn).toHaveBeenCalledTimes(1)
      const request = chatFn.mock.calls[0][0]
      const ctx = request.page_context as Record<string, unknown>
      expect(ctx.page).toBe('alerts')
      expect(ctx.alertId).toBe('evt-1')
      expect(ctx.type).toBe('VAR_BREACH')
      expect(ctx.currentValue).toBe(150000)
      expect(ctx.threshold).toBe(100000)
      expect(ctx.severity).toBe('CRITICAL')
    })

    it('a rapid double-click does not fire a duplicate chat request', () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(
        <NotificationCenter
          rules={[]}
          alerts={sampleAlerts}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          chatFn={chatFn}
        />,
      )

      const button = screen.getByTestId('explain-alert-evt-1')
      fireEvent.click(button)
      fireEvent.click(button)
      fireEvent.click(button)

      expect(chatFn).toHaveBeenCalledTimes(1)
      expect(screen.getAllByTestId('alert-explain-panel-evt-1')).toHaveLength(1)
    })

    it('opening a second alert explainer closes the first (only one panel open)', () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(
        <NotificationCenter
          rules={[]}
          alerts={sampleAlerts}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          chatFn={chatFn}
        />,
      )

      fireEvent.click(screen.getByTestId('explain-alert-evt-1'))
      expect(
        screen.getByTestId('alert-explain-panel-evt-1'),
      ).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('explain-alert-evt-2'))
      expect(
        screen.queryByTestId('alert-explain-panel-evt-1'),
      ).not.toBeInTheDocument()
      expect(
        screen.getByTestId('alert-explain-panel-evt-2'),
      ).toBeInTheDocument()
    })

    it('the panel close button dismisses the explainer', () => {
      const chatFn = vi.fn(() => fakeChatStream())
      render(
        <NotificationCenter
          rules={[]}
          alerts={sampleAlerts}
          loading={false}
          error={null}
          onCreateRule={() => {}}
          onDeleteRule={() => {}}
          chatFn={chatFn}
        />,
      )

      fireEvent.click(screen.getByTestId('explain-alert-evt-1'))
      expect(
        screen.getByTestId('alert-explain-panel-evt-1'),
      ).toBeInTheDocument()

      fireEvent.click(screen.getByTestId('ai-insight-close'))
      expect(
        screen.queryByTestId('alert-explain-panel-evt-1'),
      ).not.toBeInTheDocument()
    })
  })
})
