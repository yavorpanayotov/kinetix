import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createPositionNote,
  deletePositionNote,
  listPositionNotes,
} from '../api/positionNotes'
import type { PositionNoteDto } from '../types'

export interface UsePositionNotesResult {
  notes: PositionNoteDto[]
  notesByInstrument: Map<string, PositionNoteDto[]>
  loading: boolean
  error: string | null
  createNote: (instrumentId: string, text: string) => Promise<PositionNoteDto>
  deleteNote: (id: string) => Promise<void>
  refresh: () => Promise<void>
}

/**
 * Loads and manages free-text notes for a book's positions.
 *
 * Returns a flat `notes` array plus an instrument-indexed `notesByInstrument` map
 * for cheap per-row lookups in `PositionGrid`. See docs/plans/ui-overhaul.md §7.3.3.
 */
export function usePositionNotes(bookId: string | null): UsePositionNotesResult {
  const [notes, setNotes] = useState<PositionNoteDto[]>([])
  const [loading, setLoading] = useState<boolean>(bookId != null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (id: string) => {
    setLoading(true)
    setError(null)
    try {
      const fetched = await listPositionNotes(id)
      setNotes(fetched)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setNotes([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (bookId == null) {
      setNotes([])
      setLoading(false)
      setError(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    listPositionNotes(bookId)
      .then((fetched) => {
        if (!cancelled) {
          setNotes(fetched)
          setLoading(false)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err))
          setNotes([])
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [bookId])

  const refresh = useCallback(async () => {
    if (bookId == null) return
    await load(bookId)
  }, [bookId, load])

  const createNote = useCallback(
    async (instrumentId: string, text: string) => {
      if (bookId == null) {
        throw new Error('Cannot create note: no book selected')
      }
      const created = await createPositionNote(bookId, {
        instrumentId,
        note: text,
      })
      setNotes((prev) => [created, ...prev])
      return created
    },
    [bookId],
  )

  const deleteNote = useCallback(async (id: string) => {
    await deletePositionNote(id)
    setNotes((prev) => prev.filter((n) => n.id !== id))
  }, [])

  const notesByInstrument = useMemo(() => {
    const map = new Map<string, PositionNoteDto[]>()
    for (const n of notes) {
      const list = map.get(n.instrumentId) ?? []
      list.push(n)
      map.set(n.instrumentId, list)
    }
    return map
  }, [notes])

  return { notes, notesByInstrument, loading, error, createNote, deleteNote, refresh }
}
