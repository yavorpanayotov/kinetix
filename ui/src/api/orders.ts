import { authFetch } from '../auth/authFetch'
import type { OrderResponseDto, SubmitOrderRequestDto } from '../types'

export async function submitOrder(request: SubmitOrderRequestDto): Promise<OrderResponseDto> {
  const response = await authFetch('/api/v1/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!response.ok) {
    const detail = await response.text().catch(() => '')
    throw new Error(`Order submission failed: ${response.status} ${response.statusText}${detail ? ` — ${detail}` : ''}`)
  }
  return response.json()
}
