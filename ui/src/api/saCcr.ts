import type { SaCcrSummaryDto } from '../types'
import { authFetch } from '../auth/authFetch'

export async function fetchSaCcr(counterpartyId: string): Promise<SaCcrSummaryDto> {
  const response = await authFetch(`/api/v1/counterparty/${encodeURIComponent(counterpartyId)}/sa-ccr`)
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.error || `Failed to fetch SA-CCR: ${response.status}`)
  }
  return response.json()
}
