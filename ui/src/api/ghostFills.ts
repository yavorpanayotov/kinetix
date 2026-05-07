import { authFetch } from '../auth/authFetch'

export interface GhostFillDto {
  orderId: string
  priorStatus: string
  venue: string
  fixExecId: string
  fillQty: string
  fillPrice: string
  cumulativeQty: string
  detectedAt: string
}

/**
 * Fetch ghost fills for a specific order. A ghost fill is a FIX 35=8 fill
 * that arrived against an EXPIRED / CANCELLED / REJECTED order — Position
 * is NOT auto-updated and operator resolution is required (ADR-0035 phase 2).
 */
export async function fetchOrderGhostFills(orderId: string): Promise<GhostFillDto[]> {
  const response = await authFetch(`/api/v1/orders/${encodeURIComponent(orderId)}/ghost-fills`)
  if (!response.ok) {
    throw new Error(`Failed to fetch ghost fills: ${response.status} ${response.statusText}`)
  }
  return response.json()
}
