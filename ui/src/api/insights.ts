import { authFetch } from '../auth/authFetch'

export type InsightMode = 'live' | 'canned'

export interface InsightResponse {
  narrative: string
  bullets: string[]
  model: string
  mode: InsightMode
}

export interface VarContributor {
  instrument: string
  contribution_pct: number
}

export interface ExplainVarRequest {
  method: string
  confidence: number
  horizon_days: number
  value_usd: number
  top_contributors: VarContributor[]
  regime: string
}

export interface ReportDriver {
  name: string
  contribution_usd: number
}

export interface ExplainReportRequest {
  template_id: string
  report_date: string
  summary_metrics: Record<string, number>
  top_drivers: ReportDriver[]
  breaches: string[]
}

export async function explainVar(
  payload: ExplainVarRequest,
): Promise<InsightResponse> {
  const response = await authFetch('/api/v1/insights/explain/var', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    throw new Error(
      `Failed to explain VaR: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function explainReport(
  payload: ExplainReportRequest,
): Promise<InsightResponse> {
  const response = await authFetch('/api/v1/insights/explain/report', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    throw new Error(
      `Failed to explain report: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}
