import type { CannedStressResultDto, StressTestResultDto } from '../types'
import { authFetch } from '../auth/authFetch'

export async function fetchScenarios(): Promise<string[]> {
  const response = await authFetch('/api/v1/risk/stress/scenarios')
  if (!response.ok) {
    throw new Error(
      `Failed to fetch scenarios: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function runStressTest(
  bookId: string,
  scenarioName: string,
  request: Record<string, unknown> = {},
): Promise<StressTestResultDto | null> {
  const response = await authFetch(
    `/api/v1/risk/stress/${encodeURIComponent(bookId)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scenarioName, ...request }),
    },
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to run stress test: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export interface RunAllParams {
  confidenceLevel?: string
  timeHorizonDays?: string
}

export interface BatchStressRunResult {
  results: StressTestResultDto[]
  failedScenarios: { scenarioName: string; error: string }[]
  worstScenarioName: string | null
  worstPnlImpact: string | null
}

export async function runAllStressTests(
  bookId: string,
  scenarioNames: string[],
  params: RunAllParams = {},
): Promise<StressTestResultDto[]> {
  const response = await authFetch(
    `/api/v1/risk/stress/${encodeURIComponent(bookId)}/batch`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        scenarioNames,
        ...params,
      }),
    },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to run batch stress tests: ${response.status} ${response.statusText}`,
    )
  }
  const body: BatchStressRunResult = await response.json()
  return body.results
}

/**
 * Fetch the most recent persisted batch stress result for [bookId]
 * (issue kx-kjse). Returns `null` when no batch has been run or seeded for the
 * book yet (HTTP 404) — the caller treats that as "no result yet" and shows
 * the empty CTA rather than logging an error, mirroring the regulatory
 * `/latest` fix. The demo-orchestrator's stress sweep populates this on
 * startup, so a reseed lets the Scenarios tab render on cold open.
 */
export async function getLatestStressBatch(
  bookId: string,
): Promise<BatchStressRunResult | null> {
  const response = await authFetch(
    `/api/v1/risk/stress/${encodeURIComponent(bookId)}/batch`,
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to fetch latest stress batch: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

/**
 * Fetch the canned stress-scenario tile result for [bookId] (issue kx-wxy).
 * Returns `null` when no canned scenario has been seeded for the book yet —
 * the demo-orchestrator's `StressScenarioSeedJob` populates this on startup
 * and at SOD (09:00 UTC).
 */
export async function fetchCannedStressScenario(
  bookId: string,
): Promise<CannedStressResultDto | null> {
  const response = await authFetch(
    `/api/v1/risk/stress/${encodeURIComponent(bookId)}/canned`,
  )
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    throw new Error(
      `Failed to fetch canned stress scenario: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}
