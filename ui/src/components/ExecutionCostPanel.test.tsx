import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, test, expect, vi, beforeEach } from 'vitest'
import { ExecutionCostPanel } from './ExecutionCostPanel'
import * as executionApi from '../api/execution'
import type { ExecutionCostDto } from '../types'

vi.mock('../api/execution')

const mockFetchExecutionCosts = vi.mocked(executionApi.fetchExecutionCosts)

const sampleCost: ExecutionCostDto = {
  orderId: 'ord-001',
  bookId: 'book-alpha',
  instrumentId: 'AAPL',
  completedAt: '2026-03-24T15:00:00Z',
  arrivalPrice: '150.00',
  averageFillPrice: '150.15',
  side: 'BUY',
  totalQty: '100',
  slippageBps: '10.00',
  marketImpactBps: null,
  timingCostBps: null,
  totalCostBps: '10.00',
}

describe('ExecutionCostPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  test('shows loading state initially', () => {
    mockFetchExecutionCosts.mockReturnValue(new Promise(() => {}))
    render(<ExecutionCostPanel bookId="book-alpha" />)
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  test('renders execution cost table with slippage data', async () => {
    mockFetchExecutionCosts.mockResolvedValue([sampleCost])
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByText('AAPL')).toBeInTheDocument()
    })
    expect(screen.getByTestId('slippage-ord-001')).toHaveTextContent('10.0')
    expect(screen.getByTestId('cost-row-ord-001')).toBeInTheDocument()
  })

  test('paginates beyond 50 rows instead of rendering one endless table', async () => {
    // UX review: ~500 unvirtualised rows produced a 20,949px page.
    const many = Array.from({ length: 120 }, (_, i) => ({
      ...sampleCost,
      orderId: `ord-${String(i + 1).padStart(3, '0')}`,
    }))
    mockFetchExecutionCosts.mockResolvedValue(many)
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByTestId('execution-cost-table')).toBeInTheDocument()
    })

    expect(screen.getAllByTestId(/^cost-row-/)).toHaveLength(50)
    expect(screen.getByTestId('pagination-info')).toHaveTextContent('Page 1 of 3')

    fireEvent.click(screen.getByTestId('pagination-next'))
    expect(screen.getByTestId('cost-row-ord-051')).toBeInTheDocument()
    expect(screen.getByTestId('pagination-info')).toHaveTextContent('Page 2 of 3')
  })

  test('does not render pagination for 50 rows or fewer', async () => {
    mockFetchExecutionCosts.mockResolvedValue([sampleCost])
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByTestId('execution-cost-table')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('pagination-controls')).not.toBeInTheDocument()
  })

  test('formats raw numeric strings: quantity separators, price and bps precision, ISO timestamp', async () => {
    mockFetchExecutionCosts.mockResolvedValue([
      {
        ...sampleCost,
        totalQty: '7000000000000',
        arrivalPrice: '98.5600000000000',
        averageFillPrice: '98.7250000000001',
        slippageBps: '12',
        totalCostBps: '20.5000000',
      },
    ])
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByText('7,000,000,000,000')).toBeInTheDocument()
    })
    expect(screen.getByText('98.56')).toBeInTheDocument()
    expect(screen.getByText('98.73')).toBeInTheDocument()
    expect(screen.getByTestId('slippage-ord-001')).toHaveTextContent('12.0')
    expect(screen.getByText('20.5')).toBeInTheDocument()
    // Completed timestamps render in the platform-wide ISO format, not locale-dependent toLocaleString
    expect(screen.getByTestId('cost-row-ord-001').textContent).toMatch(/2026-03-24 \d{2}:00:00/)
  })

  test('shows arrival price and average fill price columns', async () => {
    mockFetchExecutionCosts.mockResolvedValue([sampleCost])
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByText('150.00')).toBeInTheDocument()
    })
    expect(screen.getByText('150.15')).toBeInTheDocument()
  })

  test('shows empty state when no execution costs exist', async () => {
    mockFetchExecutionCosts.mockResolvedValue([])
    render(<ExecutionCostPanel bookId="book-empty" />)

    await waitFor(() => {
      expect(screen.getByText(/no execution cost data/i)).toBeInTheDocument()
    })
  })

  test('shows error state when fetch fails', async () => {
    mockFetchExecutionCosts.mockRejectedValue(new Error('Network error'))
    render(<ExecutionCostPanel bookId="book-fail" />)

    await waitFor(() => {
      expect(screen.getByText(/network error/i)).toBeInTheDocument()
    })
  })

  test('shows empty state when bookId is null', () => {
    render(<ExecutionCostPanel bookId={null} />)
    expect(screen.getByText(/select a book/i)).toBeInTheDocument()
  })

  test('highlights positive slippage (cost) in amber', async () => {
    mockFetchExecutionCosts.mockResolvedValue([sampleCost])
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByTestId('slippage-ord-001')).toHaveClass('text-amber-600')
    })
  })

  test('shows BUY side with correct label', async () => {
    mockFetchExecutionCosts.mockResolvedValue([sampleCost])
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByTestId('side-ord-001')).toHaveTextContent('BUY')
    })
  })

  test('shows simulation mode banner when a book is selected and loading', () => {
    mockFetchExecutionCosts.mockReturnValue(new Promise(() => {}))
    render(<ExecutionCostPanel bookId="book-alpha" />)
    expect(screen.getByTestId('simulation-mode-banner')).toBeInTheDocument()
    expect(screen.getByText(/simulation mode/i)).toBeInTheDocument()
  })

  test('shows simulation mode banner when execution costs are rendered', async () => {
    mockFetchExecutionCosts.mockResolvedValue([sampleCost])
    render(<ExecutionCostPanel bookId="book-alpha" />)

    await waitFor(() => {
      expect(screen.getByTestId('execution-cost-table')).toBeInTheDocument()
    })
    expect(screen.getByTestId('simulation-mode-banner')).toBeInTheDocument()
  })

  test('shows simulation mode banner when no costs exist for a book', async () => {
    mockFetchExecutionCosts.mockResolvedValue([])
    render(<ExecutionCostPanel bookId="book-empty" />)

    await waitFor(() => {
      expect(screen.getByText(/no execution cost data/i)).toBeInTheDocument()
    })
    expect(screen.getByTestId('simulation-mode-banner')).toBeInTheDocument()
  })

  test('does not show simulation mode banner when no book is selected', () => {
    render(<ExecutionCostPanel bookId={null} />)
    expect(screen.queryByTestId('simulation-mode-banner')).not.toBeInTheDocument()
  })
})
