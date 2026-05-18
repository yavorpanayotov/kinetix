import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PositionNoteDto } from '../types'
import {
  createPositionNote,
  deletePositionNote,
  listPositionNotes,
} from './positionNotes'

describe('positionNotes API', () => {
  const mockFetch = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('fetch', mockFetch)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const sampleNote: PositionNoteDto = {
    id: '11111111-1111-1111-1111-111111111111',
    bookId: 'port-1',
    instrumentId: 'AAPL',
    note: 'Watching earnings',
    author: 'alice',
    createdAt: '2026-05-18T10:00:00Z',
  }

  describe('listPositionNotes', () => {
    it('returns parsed array on 200 without instrumentId filter', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        json: () => Promise.resolve([sampleNote]),
      })

      const result = await listPositionNotes('port-1')

      expect(result).toEqual([sampleNote])
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/positions/port-1/notes',
      )
    })

    it('appends instrumentId as query param when provided', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        json: () => Promise.resolve([sampleNote]),
      })

      await listPositionNotes('port-1', 'AAPL')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/positions/port-1/notes?instrumentId=AAPL',
      )
    })

    it('URL-encodes bookId and instrumentId', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        json: () => Promise.resolve([]),
      })

      await listPositionNotes('book/1', 'EUR USD')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/positions/book%2F1/notes?instrumentId=EUR%20USD',
      )
    })

    it('throws on non-2xx response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
      })

      await expect(listPositionNotes('port-1')).rejects.toThrow(
        'Failed to list position notes: 500 Internal Server Error',
      )
    })
  })

  describe('createPositionNote', () => {
    it('posts JSON body and returns parsed item on 201', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        status: 201,
        json: () => Promise.resolve(sampleNote),
      })

      const result = await createPositionNote('port-1', {
        instrumentId: 'AAPL',
        note: 'Watching earnings',
      })

      expect(result).toEqual(sampleNote)
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/positions/port-1/notes',
        expect.objectContaining({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            instrumentId: 'AAPL',
            note: 'Watching earnings',
          }),
        }),
      )
    })

    it('throws on non-2xx response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
      })

      await expect(
        createPositionNote('port-1', { instrumentId: 'AAPL', note: '' }),
      ).rejects.toThrow('Failed to create position note: 400 Bad Request')
    })
  })

  describe('deletePositionNote', () => {
    it('issues DELETE to the per-note URL', async () => {
      mockFetch.mockResolvedValue({ ok: true, status: 204 })

      await deletePositionNote('abc-123')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/positions/notes/abc-123',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })

    it('throws on non-2xx response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
      })

      await expect(deletePositionNote('missing')).rejects.toThrow(
        'Failed to delete position note: 404 Not Found',
      )
    })
  })
})
