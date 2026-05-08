import { authFetch } from '../auth/authFetch'

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
  const response = await authFetch('/api/v1/counterparty-risk')
  if (!response.ok) {
    throw new Error(`Failed to fetch counterparty exposures: ${response.status} ${response.statusText}`)
  }
  return response.json()
}

export async function fetchCounterpartyExposure(
  counterpartyId: string,
): Promise<CounterpartyExposureDto | null> {
  const response = await authFetch(`/api/v1/counterparty-risk/${encodeURIComponent(counterpartyId)}`)
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
  const response = await authFetch(
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
