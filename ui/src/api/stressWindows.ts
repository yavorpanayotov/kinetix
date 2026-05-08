import { authFetch } from '../auth/authFetch'

export interface StressWindowDto {
  label: string
  start: string
  end: string
}

interface StressWindowsResponse {
  windows: StressWindowDto[]
}

export async function fetchStressWindows(): Promise<StressWindowDto[]> {
  const response = await authFetch('/api/v1/demo/stress-windows')
  if (!response.ok) {
    throw new Error(`Failed to fetch stress windows: ${response.status} ${response.statusText}`)
  }
  const body = (await response.json()) as StressWindowsResponse
  return body.windows
}
