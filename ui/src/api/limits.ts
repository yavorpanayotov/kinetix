import { authFetch } from '../auth/authFetch'

export type LimitLevel = 'FIRM' | 'DIVISION' | 'DESK' | 'BOOK' | 'TRADER' | 'COUNTERPARTY'

export type LimitTypeName =
  | 'POSITION'
  | 'NOTIONAL'
  | 'VAR'
  | 'CONCENTRATION'
  | 'ADV_CONCENTRATION'
  | 'VAR_BUDGET'

export interface LimitDefinitionDto {
  id: string
  level: LimitLevel
  entityId: string
  limitType: LimitTypeName
  limitValue: string
  intradayLimit: string | null
  overnightLimit: string | null
  active: boolean
  // Trader-review P0: server populates these so the Limits screen can
  // render "how close to the wall" rather than just the ceiling.
  // `current` is the consumed value in the same unit as `limitValue`
  // (dollars for NOTIONAL/VAR, share count for POSITION). `utilisationPct`
  // is a 0–100 number rounded to 2dp. Both are nullable: position-service
  // can't compute usage for VAR/CONCENTRATION (those live in
  // risk-orchestrator), or for DIVISION/TRADER/COUNTERPARTY scopes — those
  // rows return null/null and the UI renders an em-dash.
  current?: string | null
  utilisationPct?: number | null
}

export async function fetchLimits(): Promise<LimitDefinitionDto[]> {
  const response = await authFetch('/api/v1/limits')
  if (!response.ok) {
    throw new Error(`Failed to fetch limits: ${response.status} ${response.statusText}`)
  }
  return response.json()
}
