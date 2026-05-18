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
})
