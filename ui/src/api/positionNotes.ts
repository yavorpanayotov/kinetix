import { authFetch } from '../auth/authFetch'
import type { CreatePositionNoteRequest, PositionNoteDto } from '../types'

/**
 * Lists position notes for a book, optionally filtered to a single instrument.
 *
 * Backend: gateway proxy to position-service.
 * Plan ref: docs/plans/ui-overhaul.md §7.3.3.
 */
export async function listPositionNotes(
  bookId: string,
  instrumentId?: string,
): Promise<PositionNoteDto[]> {
  const base = `/api/v1/positions/${encodeURIComponent(bookId)}/notes`
  const url = instrumentId
    ? `${base}?instrumentId=${encodeURIComponent(instrumentId)}`
    : base
  const response = await authFetch(url)
  if (!response.ok) {
    throw new Error(
      `Failed to list position notes: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function createPositionNote(
  bookId: string,
  request: CreatePositionNoteRequest,
): Promise<PositionNoteDto> {
  const response = await authFetch(
    `/api/v1/positions/${encodeURIComponent(bookId)}/notes`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to create position note: ${response.status} ${response.statusText}`,
    )
  }
  return response.json()
}

export async function deletePositionNote(id: string): Promise<void> {
  const response = await authFetch(
    `/api/v1/positions/notes/${encodeURIComponent(id)}`,
    { method: 'DELETE' },
  )
  if (!response.ok) {
    throw new Error(
      `Failed to delete position note: ${response.status} ${response.statusText}`,
    )
  }
}
