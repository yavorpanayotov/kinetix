import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { AlertDrillDownPanel } from './AlertDrillDownPanel'
import type { AlertEventDto } from '../types'
import type { PositionContributor } from '../api/alertContributors'

vi.mock('../api/alertContributors', () => ({
  fetchAlertContributors: vi.fn(),
}))

import { fetchAlertContributors } from '../api/alertContributors'

const mockFetch = fetchAlertContributors as unknown as ReturnType<typeof vi.fn>

const sampleAlert: AlertEventDto = {
  id: 'alert-1',
  ruleId: 'rule-var-limit',
  ruleName: 'VaR Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR limit breached',
  currentValue: 1250000,
  threshold: 1000000,
  bookId: 'EQ-001',
  triggeredAt: '2025-01-15T10:00:00Z',
  status: 'TRIGGERED',
}

const contributor = (overrides: Partial<PositionContributor> = {}): PositionContributor => ({
  instrumentId: 'AAPL',
  instrumentName: 'Apple Inc.',
  assetClass: 'EQUITY',
  marketValue: '500000',
  varContribution: '125000',
  percentageOfTotal: '25.0',
  ...overrides,
})

describe('AlertDrillDownPanel', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('renders the alert header with bookId, triggered timestamp, and breach magnitude', async () => {
    mockFetch.mockResolvedValue([])
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    await waitFor(() => expect(mockFetch).toHaveBeenCalledWith('alert-1'))

    const panel = screen.getByTestId('alert-drill-down-panel')
    expect(panel).toHaveTextContent('EQ-001')
    // breach = 1,250,000 - 1,000,000 = 250,000
    expect(panel).toHaveTextContent('$250,000.00')
    expect(panel).toHaveTextContent('$1,000,000.00')
  })

  it('shows loading message before contributors resolve', () => {
    mockFetch.mockImplementation(() => new Promise(() => {
      // never resolves
    }))
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    expect(screen.getByText('Loading contributors...')).toBeInTheDocument()
  })

  it('renders contributors when the fetch resolves', async () => {
    mockFetch.mockResolvedValue([
      contributor({ instrumentId: 'AAPL', instrumentName: 'Apple Inc.', percentageOfTotal: '25.0', varContribution: '125000' }),
      contributor({ instrumentId: 'MSFT', instrumentName: 'Microsoft', percentageOfTotal: '20.0', varContribution: '100000' }),
    ])
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    await waitFor(() => {
      expect(screen.getByTestId('contributor-AAPL')).toBeInTheDocument()
      expect(screen.getByTestId('contributor-MSFT')).toBeInTheDocument()
    })
    expect(screen.getByTestId('contributor-AAPL')).toHaveTextContent('Apple Inc.')
    expect(screen.getByTestId('contributor-AAPL')).toHaveTextContent('25.0%')
    expect(screen.getByTestId('contributor-AAPL')).toHaveTextContent('$125,000.00')
  })

  it('shows the first 5 contributors by default and a "N more positions" button when more exist', async () => {
    const many = Array.from({ length: 8 }, (_, i) =>
      contributor({ instrumentId: `INST-${i}`, instrumentName: `Instrument ${i}` }),
    )
    mockFetch.mockResolvedValue(many)
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    await waitFor(() => expect(screen.getByTestId('show-more-contributors')).toBeInTheDocument())

    expect(screen.getByTestId('contributor-INST-0')).toBeInTheDocument()
    expect(screen.getByTestId('contributor-INST-4')).toBeInTheDocument()
    expect(screen.queryByTestId('contributor-INST-5')).not.toBeInTheDocument()
    expect(screen.getByTestId('show-more-contributors')).toHaveTextContent('3 more positions')
  })

  it('expands to show all contributors when "N more positions" is clicked', async () => {
    const many = Array.from({ length: 7 }, (_, i) =>
      contributor({ instrumentId: `INST-${i}`, instrumentName: `Instrument ${i}` }),
    )
    mockFetch.mockResolvedValue(many)
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    await waitFor(() => expect(screen.getByTestId('show-more-contributors')).toBeInTheDocument())
    fireEvent.click(screen.getByTestId('show-more-contributors'))

    expect(screen.getByTestId('contributor-INST-5')).toBeInTheDocument()
    expect(screen.getByTestId('contributor-INST-6')).toBeInTheDocument()
    expect(screen.queryByTestId('show-more-contributors')).not.toBeInTheDocument()
  })

  it('shows the empty-state message when contributors is empty', async () => {
    mockFetch.mockResolvedValue([])
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    await waitFor(() =>
      expect(screen.getByText('No contributor data available for this alert.')).toBeInTheDocument(),
    )
  })

  it('renders the suggestedAction panel when present on the alert', async () => {
    mockFetch.mockResolvedValue([])
    render(
      <AlertDrillDownPanel
        alert={{ ...sampleAlert, suggestedAction: 'Reduce AAPL by 20% to cut VaR below limit' }}
        onClose={() => {}}
      />,
    )

    await waitFor(() => expect(screen.getByTestId('suggested-action')).toBeInTheDocument())
    expect(screen.getByTestId('suggested-action')).toHaveTextContent('Reduce AAPL by 20%')
  })

  it('calls onClose when the close button is clicked', async () => {
    mockFetch.mockResolvedValue([])
    const onClose = vi.fn()
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={onClose} />)

    fireEvent.click(screen.getByTestId('drill-down-close'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('calls onClose when Escape is pressed inside the panel', async () => {
    mockFetch.mockResolvedValue([])
    const onClose = vi.fn()
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={onClose} />)

    fireEvent.keyDown(screen.getByTestId('alert-drill-down-panel'), { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('falls back to instrumentId when instrumentName is missing', async () => {
    mockFetch.mockResolvedValue([
      contributor({ instrumentId: 'RARE-01', instrumentName: undefined }),
    ])
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    await waitFor(() => expect(screen.getByTestId('contributor-RARE-01')).toBeInTheDocument())
    expect(screen.getByTestId('contributor-RARE-01')).toHaveTextContent('RARE-01')
  })

  it('renders an empty-state message if the fetch rejects', async () => {
    mockFetch.mockRejectedValue(new Error('network down'))
    render(<AlertDrillDownPanel alert={sampleAlert} onClose={() => {}} />)

    await waitFor(() =>
      expect(screen.getByText('No contributor data available for this alert.')).toBeInTheDocument(),
    )
  })

  describe('acknowledge action', () => {
    it('renders an Acknowledge button when the alert is TRIGGERED and onAcknowledge is provided', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onAcknowledge={async () => {}}
        />,
      )

      expect(screen.getByTestId('drill-down-acknowledge-btn')).toBeInTheDocument()
    })

    it('does not render Acknowledge button on already-acknowledged alerts', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={{ ...sampleAlert, status: 'ACKNOWLEDGED' }}
          onClose={() => {}}
          onAcknowledge={async () => {}}
        />,
      )

      expect(screen.queryByTestId('drill-down-acknowledge-btn')).not.toBeInTheDocument()
    })

    it('shows the lifecycle status badge', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={{ ...sampleAlert, status: 'ACKNOWLEDGED' }}
          onClose={() => {}}
        />,
      )

      expect(screen.getByTestId('drill-down-status-badge')).toHaveTextContent(
        'ACKNOWLEDGED',
      )
    })

    it('submitting the Acknowledge form calls onAcknowledge with the alert id and note', async () => {
      mockFetch.mockResolvedValue([])
      const onAcknowledge = vi.fn().mockResolvedValue(undefined)
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onAcknowledge={onAcknowledge}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-acknowledge-btn'))
      fireEvent.change(screen.getByTestId('drill-down-acknowledge-note'), {
        target: { value: 'reviewing' },
      })
      fireEvent.click(screen.getByTestId('drill-down-acknowledge-submit'))

      await waitFor(() => {
        expect(onAcknowledge).toHaveBeenCalledWith('alert-1', 'reviewing')
      })
    })
  })

  describe('escalate and resolve actions', () => {
    it('renders an Escalate button on TRIGGERED alerts when onEscalate is provided', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onEscalate={async () => {}}
        />,
      )

      expect(screen.getByTestId('drill-down-escalate-btn')).toBeInTheDocument()
    })

    it('renders an Escalate button on ACKNOWLEDGED alerts', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={{ ...sampleAlert, status: 'ACKNOWLEDGED' }}
          onClose={() => {}}
          onEscalate={async () => {}}
        />,
      )

      expect(screen.getByTestId('drill-down-escalate-btn')).toBeInTheDocument()
    })

    it('does not render Escalate button on ESCALATED or RESOLVED alerts', async () => {
      mockFetch.mockResolvedValue([])
      const { rerender } = render(
        <AlertDrillDownPanel
          alert={{ ...sampleAlert, status: 'ESCALATED' }}
          onClose={() => {}}
          onEscalate={async () => {}}
        />,
      )
      expect(screen.queryByTestId('drill-down-escalate-btn')).not.toBeInTheDocument()

      rerender(
        <AlertDrillDownPanel
          alert={{ ...sampleAlert, status: 'RESOLVED' }}
          onClose={() => {}}
          onEscalate={async () => {}}
        />,
      )
      expect(screen.queryByTestId('drill-down-escalate-btn')).not.toBeInTheDocument()
    })

    it('renders a Resolve button on any non-RESOLVED alert', async () => {
      mockFetch.mockResolvedValue([])
      const statuses: Array<'TRIGGERED' | 'ACKNOWLEDGED' | 'ESCALATED'> = [
        'TRIGGERED',
        'ACKNOWLEDGED',
        'ESCALATED',
      ]
      for (const status of statuses) {
        const { unmount } = render(
          <AlertDrillDownPanel
            alert={{ ...sampleAlert, status }}
            onClose={() => {}}
            onResolve={async () => {}}
          />,
        )
        expect(screen.getByTestId('drill-down-resolve-btn')).toBeInTheDocument()
        unmount()
      }
    })

    it('does not render Resolve button on RESOLVED alerts', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={{ ...sampleAlert, status: 'RESOLVED' }}
          onClose={() => {}}
          onResolve={async () => {}}
        />,
      )

      expect(screen.queryByTestId('drill-down-resolve-btn')).not.toBeInTheDocument()
    })

    it('clicking Escalate opens an inline form with reason + assignee', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onEscalate={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-escalate-btn'))

      expect(screen.getByTestId('drill-down-escalate-form')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-escalate-reason')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-escalate-assignee')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-escalate-submit')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-escalate-cancel')).toBeInTheDocument()
    })

    it('submitting Escalate without a reason does not call onEscalate', () => {
      mockFetch.mockResolvedValue([])
      const onEscalate = vi.fn().mockResolvedValue(undefined)
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onEscalate={onEscalate}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-escalate-btn'))
      fireEvent.click(screen.getByTestId('drill-down-escalate-submit'))

      expect(onEscalate).not.toHaveBeenCalled()
      expect(screen.getByTestId('drill-down-escalate-form')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-escalate-reason-error')).toBeInTheDocument()
    })

    it('submitting Escalate with reason + assignee calls onEscalate', async () => {
      mockFetch.mockResolvedValue([])
      const onEscalate = vi.fn().mockResolvedValue(undefined)
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onEscalate={onEscalate}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-escalate-btn'))
      fireEvent.change(screen.getByTestId('drill-down-escalate-reason'), {
        target: { value: 'unack timeout' },
      })
      fireEvent.change(screen.getByTestId('drill-down-escalate-assignee'), {
        target: { value: 'risk-manager' },
      })
      fireEvent.click(screen.getByTestId('drill-down-escalate-submit'))

      await waitFor(() => {
        expect(onEscalate).toHaveBeenCalledWith(
          'alert-1',
          'unack timeout',
          'risk-manager',
        )
      })
    })

    it('clicking Resolve opens an inline form with a resolution field', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onResolve={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-resolve-btn'))

      expect(screen.getByTestId('drill-down-resolve-form')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-resolve-text')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-resolve-submit')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-resolve-cancel')).toBeInTheDocument()
    })

    it('submitting Resolve without text does not call onResolve', () => {
      mockFetch.mockResolvedValue([])
      const onResolve = vi.fn().mockResolvedValue(undefined)
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onResolve={onResolve}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-resolve-btn'))
      fireEvent.click(screen.getByTestId('drill-down-resolve-submit'))

      expect(onResolve).not.toHaveBeenCalled()
      expect(screen.getByTestId('drill-down-resolve-form')).toBeInTheDocument()
      expect(screen.getByTestId('drill-down-resolve-text-error')).toBeInTheDocument()
    })

    it('submitting Resolve with text calls onResolve with the resolutionText', async () => {
      mockFetch.mockResolvedValue([])
      const onResolve = vi.fn().mockResolvedValue(undefined)
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onResolve={onResolve}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-resolve-btn'))
      fireEvent.change(screen.getByTestId('drill-down-resolve-text'), {
        target: { value: 'positions reduced' },
      })
      fireEvent.click(screen.getByTestId('drill-down-resolve-submit'))

      await waitFor(() => {
        expect(onResolve).toHaveBeenCalledWith('alert-1', 'positions reduced')
      })
    })
  })

  describe('snooze action', () => {
    // §3.1b.4 of docs/plans/ui-overhaul.md — same Snooze action in the
    // drill-down view as in the per-row triage UI.

    it('renders a Snooze button on non-RESOLVED alerts when onSnooze is provided', async () => {
      mockFetch.mockResolvedValue([])
      const statuses: Array<'TRIGGERED' | 'ACKNOWLEDGED' | 'ESCALATED'> = [
        'TRIGGERED',
        'ACKNOWLEDGED',
        'ESCALATED',
      ]
      for (const status of statuses) {
        const { unmount } = render(
          <AlertDrillDownPanel
            alert={{ ...sampleAlert, status }}
            onClose={() => {}}
            onSnooze={async () => {}}
          />,
        )
        expect(screen.getByTestId('drill-down-snooze-btn')).toBeInTheDocument()
        unmount()
      }
    })

    it('does not render Snooze button on RESOLVED alerts', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={{ ...sampleAlert, status: 'RESOLVED' }}
          onClose={() => {}}
          onSnooze={async () => {}}
        />,
      )

      expect(screen.queryByTestId('drill-down-snooze-btn')).not.toBeInTheDocument()
    })

    it('clicking Snooze opens a preset popover with four buttons', async () => {
      mockFetch.mockResolvedValue([])
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onSnooze={async () => {}}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-snooze-btn'))

      expect(screen.getByTestId('drill-down-snooze-popover')).toBeInTheDocument()
      expect(
        screen.getByTestId('drill-down-snooze-preset-1h'),
      ).toBeInTheDocument()
      expect(
        screen.getByTestId('drill-down-snooze-preset-4h'),
      ).toBeInTheDocument()
      expect(
        screen.getByTestId('drill-down-snooze-preset-24h'),
      ).toBeInTheDocument()
      expect(
        screen.getByTestId('drill-down-snooze-preset-tomorrow'),
      ).toBeInTheDocument()
    })

    it('clicking the 1h preset calls onSnooze with a ~1h-future ISO timestamp', async () => {
      mockFetch.mockResolvedValue([])
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const onSnooze = vi.fn().mockResolvedValue(undefined)
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onSnooze={onSnooze}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-snooze-btn'))
      fireEvent.click(screen.getByTestId('drill-down-snooze-preset-1h'))

      await waitFor(() => {
        expect(onSnooze).toHaveBeenCalledTimes(1)
      })
      const [alertId, iso] = onSnooze.mock.calls[0]
      expect(alertId).toBe('alert-1')
      const expected = new Date(now.getTime() + 60 * 60 * 1000).getTime()
      const actual = new Date(iso as string).getTime()
      expect(Math.abs(actual - expected)).toBeLessThanOrEqual(1000)

      vi.useRealTimers()
    })

    it('clicking "Until tomorrow" calls onSnooze with next-day 09:00 local time', async () => {
      mockFetch.mockResolvedValue([])
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const onSnooze = vi.fn().mockResolvedValue(undefined)
      render(
        <AlertDrillDownPanel
          alert={sampleAlert}
          onClose={() => {}}
          onSnooze={onSnooze}
        />,
      )

      fireEvent.click(screen.getByTestId('drill-down-snooze-btn'))
      fireEvent.click(screen.getByTestId('drill-down-snooze-preset-tomorrow'))

      await waitFor(() => {
        expect(onSnooze).toHaveBeenCalledTimes(1)
      })
      const [, iso] = onSnooze.mock.calls[0]
      const result = new Date(iso as string)
      const tomorrow9 = new Date(now)
      tomorrow9.setDate(tomorrow9.getDate() + 1)
      tomorrow9.setHours(9, 0, 0, 0)
      expect(result.getTime()).toBe(tomorrow9.getTime())

      vi.useRealTimers()
    })

    it('renders a "Snoozed until …" badge when snoozedUntil is in the future', async () => {
      mockFetch.mockResolvedValue([])
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const now = new Date('2025-01-15T12:00:00Z')
      vi.setSystemTime(now)
      const snoozedAlert = {
        ...sampleAlert,
        snoozedUntil: new Date(now.getTime() + 60 * 60 * 1000).toISOString(),
      }
      render(
        <AlertDrillDownPanel
          alert={snoozedAlert}
          onClose={() => {}}
        />,
      )

      const badge = screen.getByTestId('drill-down-snoozed-until-badge')
      expect(badge).toBeInTheDocument()
      expect(badge.textContent).toMatch(/Snoozed until/i)

      vi.useRealTimers()
    })
  })
})
