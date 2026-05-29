import type { FrtbResultDto, ReportResultDto } from '../types'
import { authFetch } from '../auth/authFetch'

export async function fetchFrtb(
  bookId: string,
): Promise<FrtbResultDto | null> {
  const response = await authFetch(
    `/api/v1/regulatory/frtb/${encodeURIComponent(bookId)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    },
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to fetch FRTB: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function fetchFrtbLatest(
  bookId: string,
): Promise<FrtbResultDto | null> {
  const response = await authFetch(
    `/api/v1/regulatory/frtb/${encodeURIComponent(bookId)}/latest`,
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to fetch latest FRTB: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function generateReport(
  bookId: string,
  format: string,
): Promise<ReportResultDto | null> {
  const response = await authFetch(
    `/api/v1/regulatory/report/${encodeURIComponent(bookId)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ format }),
    },
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to generate report: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}
