import { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'
import type { PositionNoteDto } from '../types'
import { formatRelativeTime } from '../utils/format'

interface PositionNotePopoverProps {
  instrumentId: string
  notes: PositionNoteDto[]
  onAdd: (text: string) => Promise<unknown> | void
  onDelete: (id: string) => Promise<unknown> | void
  onClose: () => void
}

/**
 * Anchored popover showing existing notes for an instrument and a small form
 * to add a new one. Used by `PositionGrid` per-row note icon.
 *
 * Closes on Escape; the parent handles outside-click via a ref.
 */
export function PositionNotePopover({
  instrumentId,
  notes,
  onAdd,
  onDelete,
  onClose,
}: PositionNotePopoverProps) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKey)
    return () => document.removeEventListener('keydown', handleKey)
  }, [onClose])

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const submit = async () => {
    const trimmed = text.trim()
    if (!trimmed || busy) return
    setBusy(true)
    try {
      await onAdd(trimmed)
      setText('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      data-testid={`position-note-popover-${instrumentId}`}
      role="dialog"
      aria-label={`Notes for ${instrumentId}`}
      className="absolute right-0 top-full z-20 mt-1 w-80 rounded-lg border border-slate-200 dark:border-surface-700 bg-white dark:bg-surface-800 shadow-xl p-3"
    >
      <div className="text-xs font-semibold text-slate-700 dark:text-slate-200 mb-2">
        Notes for {instrumentId}
      </div>
      <div
        data-testid={`position-note-list-${instrumentId}`}
        className="space-y-2 max-h-48 overflow-y-auto mb-3"
      >
        {notes.length === 0 ? (
          <div className="text-xs text-slate-500 dark:text-slate-400 italic">
            No notes yet.
          </div>
        ) : (
          notes.map((n) => (
            <div
              key={n.id}
              data-testid={`position-note-item-${n.id}`}
              className="text-xs bg-slate-50 dark:bg-surface-700 rounded px-2 py-1.5 flex items-start gap-2"
            >
              <div className="flex-1 min-w-0">
                <div className="text-slate-800 dark:text-slate-100 whitespace-pre-wrap break-words">
                  {n.note}
                </div>
                <div className="text-slate-500 dark:text-slate-400 mt-0.5">
                  <span>{n.author}</span>
                  <span className="mx-1">·</span>
                  <span>{formatRelativeTime(n.createdAt)}</span>
                </div>
              </div>
              <button
                type="button"
                data-testid={`position-note-delete-${n.id}`}
                onClick={() => onDelete(n.id)}
                aria-label={`Delete note ${n.id}`}
                className="text-slate-400 hover:text-red-600 transition-colors p-0.5"
              >
                <X className="h-3 w-3" />
              </button>
            </div>
          ))
        )}
      </div>
      <div className="space-y-2">
        <textarea
          ref={inputRef}
          data-testid={`position-note-input-${instrumentId}`}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Add a note…"
          rows={2}
          className="w-full text-xs border border-slate-300 dark:border-surface-600 rounded px-2 py-1.5 bg-white dark:bg-surface-700 dark:text-slate-200 focus:ring-2 focus:ring-primary-500 focus:border-primary-500 resize-none"
        />
        <div className="flex justify-end gap-2">
          <button
            type="button"
            data-testid={`position-note-cancel-${instrumentId}`}
            onClick={onClose}
            className="text-xs px-2 py-1 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-surface-700 rounded transition-colors"
          >
            Close
          </button>
          <button
            type="button"
            data-testid={`position-note-submit-${instrumentId}`}
            onClick={submit}
            disabled={busy || !text.trim()}
            className="text-xs px-2.5 py-1 bg-primary-600 text-white rounded hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            Add note
          </button>
        </div>
      </div>
    </div>
  )
}
