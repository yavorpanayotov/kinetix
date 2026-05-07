import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { OrderGhostFills } from './OrderGhostFills'
import * as ghostFillsApi from '../api/ghostFills'

vi.mock('../api/ghostFills')

describe('OrderGhostFills', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the CRITICAL banner and a row for each ghost fill', async () => {
    vi.spyOn(ghostFillsApi, 'fetchOrderGhostFills').mockResolvedValue([
      {
        orderId: 'ord-1',
        priorStatus: 'EXPIRED',
        venue: 'NYSE',
        fixExecId: 'exec-1',
        fillQty: '50',
        fillPrice: '150.25',
        cumulativeQty: '50',
        detectedAt: '2026-05-04T20:30:15.250Z',
      },
    ])

    render(<OrderGhostFills orderId="ord-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('order-ghost-fills')).toBeInTheDocument()
    })

    const banner = screen.getByTestId('ghost-fill-banner')
    expect(banner).toHaveAttribute('role', 'alert')
    expect(banner).toHaveTextContent(/Fill received after cancel — contact ops/)
    expect(banner).toHaveTextContent(/manual ops resolution/i)

    expect(screen.getAllByTestId('ghost-fill-row')).toHaveLength(1)
  })

  it('renders nothing when there are no ghost fills', async () => {
    vi.spyOn(ghostFillsApi, 'fetchOrderGhostFills').mockResolvedValue([])

    render(<OrderGhostFills orderId="ord-1" />)

    await waitFor(() => {
      expect(screen.queryByTestId('ghost-fills-loading')).not.toBeInTheDocument()
    })
    expect(screen.queryByTestId('order-ghost-fills')).not.toBeInTheDocument()
  })

  it('shows an error message when the fetch fails', async () => {
    vi.spyOn(ghostFillsApi, 'fetchOrderGhostFills').mockRejectedValue(new Error('boom'))

    render(<OrderGhostFills orderId="ord-1" />)

    await waitFor(() => {
      expect(screen.getByTestId('ghost-fills-error')).toHaveTextContent('boom')
    })
  })
})
