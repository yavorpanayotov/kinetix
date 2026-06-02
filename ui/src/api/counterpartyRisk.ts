import { authFetch } from '../auth/authFetch'

/**
 * Default client-side deadline for counterparty-risk reads. The gateway
 * enriches the canonical risk snapshot with trade-derived counterparties by
 * fanning out to the position-service; under heavy demo trade volume that
 * enrichment can be slow. Without a client-side timeout a hung gateway leaves
 * the Counterparty Risk tab stuck on a perpetual "Loading…" spinner (kx-qfqn).
 * A bounded AbortController surfaces the existing error banner / empty state
 * instead.
 */
const COUNTERPARTY_FETCH_TIMEOUT_MS = 15_000

/**
 * Wraps authFetch with an AbortController-based timeout. If the request does
 * not settle within [timeoutMs], the controller aborts and the underlying
 * fetch rejects (AbortError), which the hook turns into an error state.
 */
async function authFetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs: number = COUNTERPARTY_FETCH_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await authFetch(input, { ...init, signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

export interface ExposureAtTenorDto {
  tenor: string
  tenorYears: number
  expectedExposure: number
  pfe95: number
  pfe99: number
}

export type AgreementStatus = 'ACTIVE' | 'EXPIRED' | 'SUSPENDED'

export interface CounterpartyExposureDto {
  counterpartyId: string
  calculatedAt: string
  currentNetExposure: number
  peakPfe: number
  cva: number | null
  cvaEstimated: boolean
  currency: string
  pfeProfile: ExposureAtTenorDto[]
  // Optional until the Gap 8 anomaly contract lands (NettingAgreement.expiryDate
  // → CounterpartyExposureResponse). Present means the gateway has agreement
  // status data; null/undefined means no agreement is associated.
  agreementStatus?: AgreementStatus | null
}

export async function fetchAllCounterpartyExposures(): Promise<CounterpartyExposureDto[]> {
  const response = await authFetchWithTimeout('/api/v1/counterparty-risk')
  if (!response.ok) {
    throw new Error(`Failed to fetch counterparty exposures: ${response.status} ${response.statusText}`)
  }
  return response.json()
}

export async function fetchCounterpartyExposure(
  counterpartyId: string,
): Promise<CounterpartyExposureDto | null> {
  const response = await authFetchWithTimeout(
    `/api/v1/counterparty-risk/${encodeURIComponent(counterpartyId)}`,
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(`Failed to fetch counterparty exposure: ${response.status} ${response.statusText}`)
  }
  return response.json()
}

export async function fetchCounterpartyExposureHistory(
  counterpartyId: string,
  limit = 90,
): Promise<CounterpartyExposureDto[]> {
  const response = await authFetchWithTimeout(
    `/api/v1/counterparty-risk/${encodeURIComponent(counterpartyId)}/history?limit=${limit}`,
  )
  if (!response.ok) {
    throw new Error(`Failed to fetch counterparty history: ${response.status} ${response.statusText}`)
  }
  return response.json()
}

export async function triggerPFEComputation(
  counterpartyId: string,
): Promise<CounterpartyExposureDto> {
  const response = await authFetch(
    `/api/v1/counterparty-risk/${encodeURIComponent(counterpartyId)}/pfe`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ positions: [] }),
    },
  )
  if (!response.ok) {
    let message: string
    try {
      const body = await response.json()
      message = body.message || `${response.status} ${response.statusText}`
    } catch {
      message = `${response.status} ${response.statusText}`
    }
    throw new Error(message)
  }
  return response.json()
}

export async function triggerCVAComputation(
  counterpartyId: string,
): Promise<CounterpartyExposureDto | null> {
  const response = await authFetch(
    `/api/v1/counterparty-risk/${encodeURIComponent(counterpartyId)}/cva`,
    { method: 'POST' },
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(`Failed to compute CVA: ${response.status} ${response.statusText}`)
  }
  return response.json()
}
