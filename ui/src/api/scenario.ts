import { authFetch } from '../auth/authFetch'

export interface ActiveScenarioDto {
  scenario: string
}

export async function fetchActiveScenario(): Promise<ActiveScenarioDto> {
  const response = await authFetch('/api/v1/demo/scenario')
  if (!response.ok) {
    throw new Error(`Failed to fetch active scenario: ${response.status} ${response.statusText}`)
  }
  return response.json()
}
