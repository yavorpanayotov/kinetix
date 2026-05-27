import { authFetch } from '../auth/authFetch'

/**
 * Typed client for the gateway-proxied audit API.
 *
 * Backend: gateway audit proxy (`/api/v1/audit/*`) forwarding to audit-service.
 * Plan ref: plans/audit-v2.md PR 8 §8.1.
 */

/**
 * A single audit-trail event. Mirrors audit-service `AuditEventResponse`.
 *
 * Trade fields (`tradeId`, `bookId`, `instrumentId`, …) are null for governance
 * events; governance fields (`modelName`, `scenarioId`, …) are null for trade
 * events. `correlationId` is optional for forward compatibility — the current
 * audit-service `AuditEventResponse` DTO does not yet expose it.
 */
export interface AuditEventDto {
  id: number
  // Trade fields — null for governance events
  tradeId: string | null
  bookId: string | null
  instrumentId: string | null
  assetClass: string | null
  side: string | null
  quantity: string | null
  priceAmount: string | null
  priceCurrency: string | null
  tradedAt: string | null
  // Common fields
  receivedAt: string
  previousHash: string | null
  recordHash: string
  userId: string | null
  userRole: string | null
  // Optional on the wire: audit-service serializes with the kotlinx
  // default of ``encodeDefaults = false``, so a row whose
  // ``eventType`` equals the column default ("TRADE_BOOKED") is sent
  // with the field omitted. Consumers must tolerate ``undefined`` and
  // derive a sensible label from the event's other fields.
  eventType?: string
  // Governance fields — null for trade events
  modelName: string | null
  scenarioId: string | null
  limitId: string | null
  submissionId: string | null
  details: string | null
  sequenceNumber: number | null
  // Forward-compatible: not yet emitted by audit-service AuditEventResponse.
  correlationId?: string
}

/**
 * Optional filters and cursor pagination for `GET /api/v1/audit/events`.
 * Any absent field is omitted from the request query string.
 */
export interface AuditEventQuery {
  bookId?: string
  tradeId?: string
  eventType?: string
  /** Inclusive lower bound on `receivedAt`, ISO-8601 instant. */
  from?: string
  /** Inclusive upper bound on `receivedAt`, ISO-8601 instant. */
  to?: string
  /** Cursor: return events with `id` greater than this value. */
  afterId?: number
  /** Maximum number of events to return. */
  limit?: number
}

/** Result of `GET /api/v1/audit/verify`. Mirrors `ChainVerificationResult`. */
export interface AuditVerifyResultDto {
  valid: boolean
  eventCount: number
}

/**
 * Lists audit events, newest cursor page first, applying the given optional
 * filters. The audit-service `/events` endpoint responds with a bare array.
 */
export async function fetchAuditEvents(
  query: AuditEventQuery = {},
): Promise<AuditEventDto[]> {
  const params = new URLSearchParams()
  if (query.bookId) params.set('bookId', query.bookId)
  if (query.tradeId) params.set('tradeId', query.tradeId)
  if (query.eventType) params.set('eventType', query.eventType)
  if (query.from) params.set('from', query.from)
  if (query.to) params.set('to', query.to)
  if (query.afterId !== undefined) params.set('afterId', query.afterId.toString())
  if (query.limit !== undefined) params.set('limit', query.limit.toString())

  const queryString = params.toString()
  const url = queryString
    ? `/api/v1/audit/events?${queryString}`
    : '/api/v1/audit/events'

  const response = await authFetch(url)
  if (!response.ok) {
    throw new Error(
      `Failed to fetch audit events: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

/**
 * Verifies the integrity of the hash-chained audit trail.
 */
export async function verifyAuditChain(): Promise<AuditVerifyResultDto> {
  const response = await authFetch('/api/v1/audit/verify')
  if (!response.ok) {
    throw new Error(
      `Failed to verify audit chain: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}
