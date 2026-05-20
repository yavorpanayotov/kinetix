import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, test, expect, vi, beforeEach } from 'vitest'
import { AuditLogPanel } from './AuditLogPanel'
import * as auditApi from '../api/audit'
import type { AuditEventDto } from '../api/audit'

vi.mock('../api/audit')

const mockFetchAuditEvents = vi.mocked(auditApi.fetchAuditEvents)
const mockVerifyAuditChain = vi.mocked(auditApi.verifyAuditChain)

/** Builds an audit event with sensible defaults; override any field per test. */
function buildEvent(overrides: Partial<AuditEventDto> = {}): AuditEventDto {
  return {
    id: 1,
    tradeId: 'TRD-1',
    bookId: 'BOOK-A',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '100',
    priceAmount: '150.00',
    priceCurrency: 'USD',
    tradedAt: '2026-05-19T09:00:00Z',
    receivedAt: '2026-05-19T09:00:01Z',
    previousHash: 'prev-hash',
    recordHash: 'record-hash',
    userId: 'trader-1',
    userRole: 'TRADER',
    eventType: 'TRADE_BOOKED',
    modelName: null,
    scenarioId: null,
    limitId: null,
    submissionId: null,
    details: null,
    sequenceNumber: 1,
    ...overrides,
  }
}

/** A full cursor page (25 events) so the panel believes more pages exist. */
function buildFullPage(startId: number): AuditEventDto[] {
  return Array.from({ length: 25 }, (_, i) =>
    buildEvent({ id: startId + i, tradeId: `TRD-${startId + i}` }),
  )
}

describe('AuditLogPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetchAuditEvents.mockResolvedValue([])
    mockVerifyAuditChain.mockResolvedValue({ valid: true, eventCount: 0 })
  })

  test('shows the empty state when no events match', async () => {
    mockFetchAuditEvents.mockResolvedValue([])
    render(<AuditLogPanel />)

    await waitFor(() => {
      expect(screen.getByText(/no audit events match/i)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('audit-events-table')).not.toBeInTheDocument()
  })

  test('renders a row and an event-type badge for each event', async () => {
    mockFetchAuditEvents.mockResolvedValue([
      buildEvent({ id: 7, eventType: 'TRADE_BOOKED' }),
      buildEvent({ id: 8, eventType: 'LIMIT_BREACHED', tradeId: null, limitId: 'LIM-9' }),
    ])
    render(<AuditLogPanel />)

    await waitFor(() => {
      expect(screen.getByTestId('audit-row-7')).toBeInTheDocument()
    })
    expect(screen.getByTestId('audit-row-8')).toBeInTheDocument()
    expect(screen.getByTestId('audit-event-badge-7')).toHaveTextContent('TRADE_BOOKED')
    expect(screen.getByTestId('audit-event-badge-8')).toHaveTextContent('LIMIT_BREACHED')
  })

  test('requests events with the default page size on mount', async () => {
    render(<AuditLogPanel />)
    await waitFor(() => {
      expect(mockFetchAuditEvents).toHaveBeenCalledWith({ limit: 25 })
    })
  })

  test('refetches with the book filter when the book field changes', async () => {
    render(<AuditLogPanel />)
    await waitFor(() => expect(mockFetchAuditEvents).toHaveBeenCalled())

    fireEvent.change(screen.getByTestId('audit-filter-book'), {
      target: { value: 'BOOK-X' },
    })

    await waitFor(() => {
      expect(mockFetchAuditEvents).toHaveBeenCalledWith({ limit: 25, bookId: 'BOOK-X' })
    })
  })

  test('refetches with the event-type filter when that field changes', async () => {
    render(<AuditLogPanel />)
    await waitFor(() => expect(mockFetchAuditEvents).toHaveBeenCalled())

    fireEvent.change(screen.getByTestId('audit-filter-event-type'), {
      target: { value: 'LIMIT_BREACHED' },
    })

    await waitFor(() => {
      expect(mockFetchAuditEvents).toHaveBeenCalledWith({
        limit: 25,
        eventType: 'LIMIT_BREACHED',
      })
    })
  })

  test('shows the chain-verified indicator when the chain is valid', async () => {
    mockVerifyAuditChain.mockResolvedValue({ valid: true, eventCount: 142 })
    render(<AuditLogPanel />)

    await waitFor(() => {
      expect(screen.getByTestId('audit-chain-valid')).toBeInTheDocument()
    })
    expect(screen.getByTestId('audit-chain-valid')).toHaveTextContent('142')
  })

  test('shows the chain-broken indicator when the chain is invalid', async () => {
    mockVerifyAuditChain.mockResolvedValue({ valid: false, eventCount: 99 })
    render(<AuditLogPanel />)

    await waitFor(() => {
      expect(screen.getByTestId('audit-chain-broken')).toBeInTheDocument()
    })
    expect(screen.getByTestId('audit-chain-broken')).toHaveTextContent('99')
    expect(screen.queryByTestId('audit-chain-valid')).not.toBeInTheDocument()
  })

  test('shows the error state when the events fetch fails', async () => {
    mockFetchAuditEvents.mockRejectedValue(new Error('Audit service unavailable'))
    render(<AuditLogPanel />)

    await waitFor(() => {
      expect(screen.getByText(/audit service unavailable/i)).toBeInTheDocument()
    })
  })

  test('shows a Load more button when a full page is returned and fetches the next page with afterId', async () => {
    const firstPage = buildFullPage(1) // ids 1..25
    const secondPage = [buildEvent({ id: 26 })]
    mockFetchAuditEvents
      .mockResolvedValueOnce(firstPage)
      .mockResolvedValueOnce(secondPage)

    render(<AuditLogPanel />)

    await waitFor(() => {
      expect(screen.getByTestId('audit-load-more')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByTestId('audit-load-more'))

    await waitFor(() => {
      expect(mockFetchAuditEvents).toHaveBeenCalledWith({ limit: 25, afterId: 25 })
    })
    await waitFor(() => {
      expect(screen.getByTestId('audit-row-26')).toBeInTheDocument()
    })
  })

  test('does not show Load more when fewer than a full page is returned', async () => {
    mockFetchAuditEvents.mockResolvedValue([buildEvent({ id: 1 })])
    render(<AuditLogPanel />)

    await waitFor(() => {
      expect(screen.getByTestId('audit-row-1')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('audit-load-more')).not.toBeInTheDocument()
  })
})
