import { useEffect, useRef } from 'react'
import { X, Keyboard } from 'lucide-react'

interface KeyboardShortcutsOverlayProps {
  open: boolean
  onClose: () => void
}

interface Shortcut {
  keys: string
  description: string
}

// Only list shortcuts that ACTUALLY exist in the app today.
// Cmd+/ is still future work.
const SHORTCUTS: Shortcut[] = [
  { keys: 'Cmd+K / Ctrl+K', description: 'Open command palette' },
  { keys: 'Shift+H', description: 'Suggest Hedge' },
  { keys: 'Arrow keys', description: 'Move focus between tabs (in tab bar)' },
  { keys: 'Home / End', description: 'Jump to first / last tab (in tab bar)' },
  { keys: 'Esc', description: 'Close dialog or dropdown' },
  { keys: '?', description: 'Show keyboard shortcuts' },
]

export function KeyboardShortcutsOverlay({ open, onClose }: KeyboardShortcutsOverlayProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null)

  // Escape closes the overlay.
  useEffect(() => {
    if (!open) return
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])

  // Move focus into the dialog when it opens.
  useEffect(() => {
    if (open) {
      dialogRef.current?.focus()
    }
  }, [open])

  if (!open) return null

  return (
    <>
      <div
        data-testid="keyboard-shortcuts-backdrop"
        className="fixed inset-0 z-40 bg-black/40"
        onClick={onClose}
      />
      <div
        ref={dialogRef}
        data-testid="keyboard-shortcuts-overlay"
        role="dialog"
        aria-modal="true"
        aria-label="Keyboard shortcuts"
        tabIndex={-1}
        className="fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2 w-[min(480px,90vw)] max-h-[80vh] overflow-y-auto bg-white dark:bg-surface-800 border border-slate-200 dark:border-surface-700 rounded-lg shadow-xl focus:outline-none focus:ring-2 focus:ring-primary-500"
      >
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-surface-700 bg-slate-50 dark:bg-surface-900 rounded-t-lg">
          <h2 className="text-sm font-bold text-slate-800 dark:text-slate-200 flex items-center gap-1.5">
            <Keyboard className="h-4 w-4" />
            Keyboard shortcuts
          </h2>
          <button
            data-testid="keyboard-shortcuts-close"
            aria-label="Close keyboard shortcuts"
            onClick={onClose}
            className="p-1 rounded hover:bg-slate-200 dark:hover:bg-surface-700 transition-colors"
          >
            <X className="h-4 w-4 text-slate-500 dark:text-slate-400" />
          </button>
        </div>
        <ul className="divide-y divide-slate-100 dark:divide-surface-700">
          {SHORTCUTS.map((shortcut) => (
            <li
              key={shortcut.keys}
              className="flex items-center justify-between px-4 py-2.5 text-sm"
            >
              <span className="text-slate-700 dark:text-slate-300">
                {shortcut.description}
              </span>
              <kbd className="px-2 py-0.5 text-xs font-mono font-medium text-slate-700 dark:text-slate-300 bg-slate-100 dark:bg-surface-900 border border-slate-300 dark:border-surface-600 rounded">
                {shortcut.keys}
              </kbd>
            </li>
          ))}
        </ul>
        <div className="px-4 py-2 border-t border-slate-200 dark:border-surface-700 bg-slate-50 dark:bg-surface-900 rounded-b-lg text-xs text-slate-500 dark:text-slate-400">
          Press <kbd className="px-1 py-0.5 font-mono bg-white dark:bg-surface-800 border border-slate-300 dark:border-surface-600 rounded">Esc</kbd> to close.
        </div>
      </div>
    </>
  )
}
