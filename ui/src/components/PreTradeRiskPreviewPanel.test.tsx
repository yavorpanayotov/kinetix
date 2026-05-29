import { describe, expect, it, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { PreTradeRiskPreviewPanel } from './PreTradeRiskPreviewPanel'
import type { PreTradeRiskPreviewResponseDto } from '../types'

vi.mock('../api/preTradeRiskPreview', () => ({
  fetchPreTradeRiskPreview: vi.fn(),
}))

import { fetchPreTradeRiskPreview } from '../api/preTradeRiskPreview'

function preview(overrides: Partial<PreTradeRiskPreviewResponseDto> = {}): PreTradeRiskPreviewResponseDto {
  return {
    baseVaR: '10000.00',
    hypotheticalVaR: '11500.00',
    varChange: '1500.00',
    baseDelta: '100.000000',
    hypotheticalDelta: '150.000000',
    deltaChange: '50.000000',
    notionalChange: '15000.00',
    counterpartyId: null,
    counterpartyExposureChange: null,
    calculatedAt: '2026-05-29T10:00:00Z',
    ...overrides,
  }
}

const BASE_REQUEST = {
  bookId: 'port-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  side: 'BUY' as const,
  quantity: '100',
  priceAmount: '150.00',
  priceCurrency: 'USD',
  instrumentType: 'CASH_EQUITY',
  counterpartyId: null,
}

describe('PreTradeRiskPreviewPanel', () => {
  beforeEach(() => {
    vi.mocked(fetchPreTradeRiskPreview).mockReset()
  })

  it('renders nothing while the candidate trade is incomplete', () => {
    const { container } = render(<PreTradeRiskPreviewPanel candidate={null} />)
    expect(container.querySelector('[data-testid="place-order-risk-preview"]')).toBeNull()
    expect(fetchPreTradeRiskPreview).not.toHaveBeenCalled()
  })

  it('fetches the preview and renders the four deltas once the candidate is complete', async () => {
    vi.mocked(fetchPreTradeRiskPreview).mockResolvedValueOnce(preview())

    render(<PreTradeRiskPreviewPanel candidate={BASE_REQUEST} />)

    await waitFor(() => {
      expect(screen.getByTestId('place-order-risk-preview')).toBeInTheDocument()
    })

    expect(fetchPreTradeRiskPreview).toHaveBeenCalledTimes(1)
    expect(fetchPreTradeRiskPreview).toHaveBeenCalledWith(BASE_REQUEST)

    expect(screen.getByTestId('place-order-risk-preview-var-change')).toHaveTextContent('1,500.00')
    expect(screen.getByTestId('place-order-risk-preview-delta-change')).toHaveTextContent('50')
    expect(screen.getByTestId('place-order-risk-preview-notional-change')).toHaveTextContent('15,000.00')
    // No counterparty supplied — must show dash, not a misleading $0.
    expect(screen.getByTestId('place-order-risk-preview-counterparty-change')).toHaveTextContent('—')
  })

  it('echoes the counterparty exposure delta when the candidate carries a counterpartyId', async () => {
    vi.mocked(fetchPreTradeRiskPreview).mockResolvedValueOnce(
      preview({
        counterpartyId: 'JPM',
        counterpartyExposureChange: '-19900.00',
        notionalChange: '-19900.00',
      }),
    )

    render(
      <PreTradeRiskPreviewPanel
        candidate={{ ...BASE_REQUEST, counterpartyId: 'JPM', side: 'SELL', quantity: '200', priceAmount: '99.50' }}
      />,
    )

    await waitFor(() => {
      expect(screen.getByTestId('place-order-risk-preview-counterparty-change')).toHaveTextContent('JPM')
    })
    expect(screen.getByTestId('place-order-risk-preview-counterparty-change')).toHaveTextContent('-19,900.00')
  })

  it('renders an error row when the preview RPC fails (does not block the form)', async () => {
    vi.mocked(fetchPreTradeRiskPreview).mockRejectedValueOnce(new Error('upstream 502'))

    render(<PreTradeRiskPreviewPanel candidate={BASE_REQUEST} />)

    await waitFor(() => {
      expect(screen.getByTestId('place-order-risk-preview-error')).toBeInTheDocument()
    })
  })

  it('refetches the preview when the candidate trade changes', async () => {
    vi.mocked(fetchPreTradeRiskPreview).mockResolvedValue(preview())

    const { rerender } = render(<PreTradeRiskPreviewPanel candidate={BASE_REQUEST} />)
    await waitFor(() => expect(fetchPreTradeRiskPreview).toHaveBeenCalledTimes(1))

    rerender(<PreTradeRiskPreviewPanel candidate={{ ...BASE_REQUEST, quantity: '200' }} />)
    await waitFor(() => expect(fetchPreTradeRiskPreview).toHaveBeenCalledTimes(2))

    const calls = vi.mocked(fetchPreTradeRiskPreview).mock.calls
    expect(calls[1][0].quantity).toBe('200')

    // Re-rendering with the same candidate must not refetch.
    rerender(<PreTradeRiskPreviewPanel candidate={{ ...BASE_REQUEST, quantity: '200' }} />)
    expect(fetchPreTradeRiskPreview).toHaveBeenCalledTimes(2)
  })

  it('skips the fetch when only fire-and-forget data has changed (price currency same)', async () => {
    vi.mocked(fetchPreTradeRiskPreview).mockResolvedValue(preview())
    const { rerender } = render(<PreTradeRiskPreviewPanel candidate={BASE_REQUEST} />)
    await waitFor(() => expect(fetchPreTradeRiskPreview).toHaveBeenCalledTimes(1))

    rerender(<PreTradeRiskPreviewPanel candidate={BASE_REQUEST} />)
    expect(fetchPreTradeRiskPreview).toHaveBeenCalledTimes(1)
  })
})
