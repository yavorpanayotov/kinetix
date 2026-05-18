import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PositionNoteDto } from '../types'

vi.mock('../api/positionNotes')

import {
  createPositionNote,
  deletePositionNote,
  listPositionNotes,
} from '../api/positionNotes'
import { usePositionNotes } from './usePositionNotes'

const mockList = vi.mocked(listPositionNotes)
const mockCreate = vi.mocked(createPositionNote)
const mockDelete = vi.mocked(deletePositionNote)

const note = (overrides: Partial<PositionNoteDto> = {}): PositionNoteDto => ({
  id: 'note-1',
  bookId: 'port-1',
  instrumentId: 'AAPL',
  note: 'Watching earnings',
  author: 'alice',
  createdAt: '2026-05-18T10:00:00Z',
  ...overrides,
})

describe('usePositionNotes', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('does not fetch when bookId is null', () => {
    renderHook(() => usePositionNotes(null))
    expect(mockList).not.toHaveBeenCalled()
  })

  it('fetches notes for the book on mount', async () => {
    mockList.mockResolvedValue([note()])

    const { result } = renderHook(() => usePositionNotes('port-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(mockList).toHaveBeenCalledWith('port-1')
    expect(result.current.notes).toEqual([note()])
    expect(result.current.error).toBeNull()
  })

  it('groups notes by instrumentId via notesByInstrument', async () => {
    mockList.mockResolvedValue([
      note({ id: 'a', instrumentId: 'AAPL' }),
      note({ id: 'b', instrumentId: 'AAPL' }),
      note({ id: 'c', instrumentId: 'GOOGL' }),
    ])

    const { result } = renderHook(() => usePositionNotes('port-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.notesByInstrument.get('AAPL')).toHaveLength(2)
    expect(result.current.notesByInstrument.get('GOOGL')).toHaveLength(1)
  })

  it('createNote calls API and appends the returned note', async () => {
    mockList.mockResolvedValue([])
    const newNote = note({ id: 'new-1' })
    mockCreate.mockResolvedValue(newNote)

    const { result } = renderHook(() => usePositionNotes('port-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    let returned: PositionNoteDto | undefined
    await act(async () => {
      returned = await result.current.createNote('AAPL', 'A note')
    })

    expect(returned).toEqual(newNote)
    expect(mockCreate).toHaveBeenCalledWith('port-1', {
      instrumentId: 'AAPL',
      note: 'A note',
    })
    expect(result.current.notes).toEqual([newNote])
    expect(result.current.notesByInstrument.get('AAPL')).toEqual([newNote])
  })

  it('createNote throws when bookId is null', async () => {
    const { result } = renderHook(() => usePositionNotes(null))

    await expect(
      result.current.createNote('AAPL', 'note'),
    ).rejects.toThrow('Cannot create note: no book selected')
  })

  it('deleteNote calls API and removes the note from state', async () => {
    mockList.mockResolvedValue([note({ id: 'a' }), note({ id: 'b' })])
    mockDelete.mockResolvedValue()

    const { result } = renderHook(() => usePositionNotes('port-1'))

    await waitFor(() => {
      expect(result.current.notes).toHaveLength(2)
    })

    await act(async () => {
      await result.current.deleteNote('a')
    })

    expect(mockDelete).toHaveBeenCalledWith('a')
    expect(result.current.notes.map((n) => n.id)).toEqual(['b'])
  })

  it('sets error state on fetch failure', async () => {
    mockList.mockRejectedValue(new Error('boom'))

    const { result } = renderHook(() => usePositionNotes('port-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe('boom')
    expect(result.current.notes).toEqual([])
  })

  it('refresh re-fetches notes', async () => {
    mockList.mockResolvedValue([note({ id: 'a' })])

    const { result } = renderHook(() => usePositionNotes('port-1'))

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })
    expect(mockList).toHaveBeenCalledTimes(1)

    mockList.mockResolvedValue([note({ id: 'a' }), note({ id: 'b' })])

    await act(async () => {
      await result.current.refresh()
    })

    expect(mockList).toHaveBeenCalledTimes(2)
    expect(result.current.notes).toHaveLength(2)
  })

  it('refetches when bookId changes', async () => {
    mockList.mockResolvedValue([note({ bookId: 'port-1' })])

    const { rerender } = renderHook(
      ({ bookId }: { bookId: string | null }) => usePositionNotes(bookId),
      { initialProps: { bookId: 'port-1' as string | null } },
    )

    await waitFor(() => expect(mockList).toHaveBeenCalledWith('port-1'))

    mockList.mockResolvedValue([note({ bookId: 'port-2' })])
    rerender({ bookId: 'port-2' })

    await waitFor(() => expect(mockList).toHaveBeenCalledWith('port-2'))
  })
})
