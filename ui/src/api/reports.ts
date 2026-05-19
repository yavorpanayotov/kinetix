import { authFetch } from '../auth/authFetch'

export interface ReportTemplate {
  templateId: string
  name: string
  templateType: string
  ownerUserId: string
  description: string
  source: string
}

export interface ReportOutput {
  outputId: string
  templateId: string
  generatedAt: string
  outputFormat: string
  rowCount: number
}

export interface GenerateReportRequest {
  templateId: string
  bookId: string
  date?: string
  format?: string
}

export async function fetchReportTemplates(): Promise<ReportTemplate[]> {
  const response = await authFetch('/api/v1/reports/templates')
  if (!response.ok) {
    throw new Error(
      `Failed to fetch report templates: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function generateReport(request: GenerateReportRequest): Promise<ReportOutput> {
  const response = await authFetch('/api/v1/reports/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!response.ok) {
    // Plan §4.3 — surface the upstream gateway `{error, message}` body
    // so the ReportsTab toast can render the actual cause (e.g. the
    // exact "Report generation failed" upstream message) rather than
    // the opaque "500 Internal Server Error" status text. Falls back
    // to the HTTP status if the body isn't a parseable ErrorResponse.
    const upstream = await readErrorMessage(response)
    throw new Error(`Failed to generate report: ${upstream}`)
  }
  return response.json()
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.clone().json()) as { message?: unknown }
    if (typeof body?.message === 'string' && body.message.length > 0) {
      return body.message
    }
  } catch {
    // Fall through to the status text on JSON parse failure.
  }
  return `${response.status} ${response.statusText}`
}

export async function fetchReportOutput(outputId: string): Promise<ReportOutput | null> {
  const response = await authFetch(`/api/v1/reports/${encodeURIComponent(outputId)}`)
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to fetch report output: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function downloadReportCsv(outputId: string): Promise<string | null> {
  const response = await authFetch(`/api/v1/reports/${encodeURIComponent(outputId)}/csv`)
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to download report CSV: ${response.status} ${response.statusText}`,
    )
  }
  return response.text()
}
