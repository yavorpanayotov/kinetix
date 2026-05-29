import { authFetch } from '../auth/authFetch'
import type {
  PreTradeRiskPreviewRequestDto,
  PreTradeRiskPreviewResponseDto,
} from '../types'

/**
 * Trader-review P2 (ui-trader-review.md): advisory pre-trade risk
 * preview for the Place Order ticket. Hits the gateway's pretrade route
 * which reuses the existing What-If valuation path and projects the
 * upstream response into the four-delta preview shape.
 */
export async function fetchPreTradeRiskPreview(
  request: PreTradeRiskPreviewRequestDto,
): Promise<PreTradeRiskPreviewResponseDto> {
  const response = await authFetch('/api/v1/risk/pretrade-preview', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!response.ok) {
    const detail = await response.text().catch(() => '')
    throw new Error(
      `Pre-trade risk preview failed: ${response.status} ${response.statusText}${detail ? ` — ${detail}` : ''}`,
    )
  }
  return response.json()
}
