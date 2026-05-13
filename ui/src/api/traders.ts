import { authFetch } from '../auth/authFetch'

export interface TraderDto {
  id: string
  name: string
  deskId: string
  email?: string | null
  notionalLimitUsd?: string | null
}

export async function fetchTraders(): Promise<TraderDto[]> {
  const response = await authFetch('/api/v1/traders')
  if (!response.ok) {
    throw new Error(`Failed to fetch traders: ${response.status} ${response.statusText}`)
  }
  return response.json()
}

export async function fetchTradersForDesk(deskId: string): Promise<TraderDto[]> {
  const response = await authFetch(`/api/v1/desks/${encodeURIComponent(deskId)}/traders`)
  if (!response.ok) {
    throw new Error(`Failed to fetch traders for desk ${deskId}: ${response.status} ${response.statusText}`)
  }
  return response.json()
}
