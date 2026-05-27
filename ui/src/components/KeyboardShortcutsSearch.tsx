import { useEffect, type ReactNode, type RefObject } from 'react'

// "Focus the table search" keyboard shortcut — Ctrl+/ or Cmd+/ (kx-bht6).
//
// Long table pages are a chore to navigate without keyboard support: the user
// has to reach for the mouse to find the filter input, click in, then start
// typing. Standard practice in dense web apps is to bind the search box to
// Ctrl+/ (or Cmd+/ on Mac), echoing the pattern Slack and GitHub popularised.
// We bind the same shortcut globally, route it to the most-recently mounted
// search input, and call `preventDefault` so Firefox's quick-find toolbar
// does not steal the keystroke.
//
// State is owned by the `KeyboardShortcutsSearch` module: a LIFO stack of
// HTMLInputElement targets registered by the React hook. Multiple searchable
// surfaces can coexist on a page (e.g. an outer table plus a drawer's own
// list); the topmost wins, matching how users expect the most-recent layer
// to receive the focus.

type SearchTarget = HTMLInputElement | HTMLTextAreaElement

let targetStack: SearchTarget[] = []
let listenerInstalled = false

function focusTopTarget(event: KeyboardEvent): void {
  const top = targetStack[targetStack.length - 1]
  if (!top) return
  // Preventing default keeps Firefox's quick-find toolbar from intercepting.
  event.preventDefault()
  top.focus()
  // If the target is an <input> or <textarea>, leave the caret at the end so
  // re-pressing the shortcut while focused does not select existing text.
  if ('setSelectionRange' in top && typeof top.value === 'string') {
    const len = top.value.length
    try {
      top.setSelectionRange(len, len)
    } catch {
      // Some input types (e.g. "number") reject setSelectionRange — harmless.
    }
  }
}

function windowKeyDown(event: KeyboardEvent): void {
  if (event.key !== '/') return
  if (!(event.ctrlKey || event.metaKey)) return
  focusTopTarget(event)
}

/** Install the singleton window keydown listener (idempotent). */
// eslint-disable-next-line react-refresh/only-export-components
export function ensureSearchFocusListener(): void {
  if (listenerInstalled) return
  if (typeof window === 'undefined') return
  window.addEventListener('keydown', windowKeyDown)
  listenerInstalled = true
}

/**
 * Register an input as the current search-focus target. The most-recently
 * registered target receives focus on Ctrl+/. Returns an unregister function
 * — call it on cleanup so the stack does not grow unbounded.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function registerSearchFocusTarget(target: SearchTarget): () => void {
  ensureSearchFocusListener()
  targetStack.push(target)
  return () => {
    const idx = targetStack.lastIndexOf(target)
    if (idx !== -1) targetStack.splice(idx, 1)
  }
}

/**
 * Hook: while mounted and pointing at an attached element, the ref'd input
 * receives focus whenever the user presses Ctrl+/ (or Cmd+/). Pass a ref
 * produced by `useRef<HTMLInputElement | null>(null)`.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useSearchFocusShortcut(
  ref: RefObject<SearchTarget | null>,
): void {
  useEffect(() => {
    const node = ref.current
    if (!node) return
    const unregister = registerSearchFocusTarget(node)
    return unregister
  }, [ref])
}

/**
 * Test-only helper: wipes the target stack between cases so one test does
 * not bleed into the next. Production code should never call this.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function __resetSearchFocusStackForTests(): void {
  targetStack = []
}

interface KeyboardShortcutsSearchProviderProps {
  children: ReactNode
}

/**
 * Mounts the global Ctrl+/ listener as a side-effect of rendering. The
 * provider owns no React state — `useSearchFocusShortcut` pushes to the
 * module-level stack — but the explicit wrapper makes the dependency
 * obvious in the component tree.
 */
export function KeyboardShortcutsSearchProvider({
  children,
}: KeyboardShortcutsSearchProviderProps) {
  useEffect(() => {
    ensureSearchFocusListener()
  }, [])
  return <>{children}</>
}
