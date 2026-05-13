import { authFetch } from '../auth/authFetch'

export type TapeReplayStatus = 'LIVE' | 'ACTIVE' | 'FROZEN'

export interface TapeReplayStatusDto {
  status: TapeReplayStatus
}

export async function fetchTapeReplayStatus(): Promise<TapeReplayStatusDto> {
  const response = await authFetch('/api/v1/demo/replay-status')
  if (!response.ok) {
    throw new Error(`Failed to fetch tape replay status: ${response.status} ${response.statusText}`)
  }
  return response.json()
}
