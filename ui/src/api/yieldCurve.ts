import { authFetch } from '../auth/authFetch'

export interface YieldCurvePoint {
  label: string
  days: number
  rate: string
  interpolated: boolean
}

export interface YieldCurve {
  curveId: string
  currency: string
  asOfDate: string
  source: string
  points: YieldCurvePoint[]
}

export async function fetchYieldCurve(currency: string): Promise<YieldCurve | null> {
  const response = await authFetch(`/api/v1/rates/yield-curves/${encodeURIComponent(currency)}`)
  if (response.status === 404) return null
  if (!response.ok) {
    throw new Error(`Failed to fetch yield curve: ${response.status} ${response.statusText}`)
  }
  return response.json()
}
