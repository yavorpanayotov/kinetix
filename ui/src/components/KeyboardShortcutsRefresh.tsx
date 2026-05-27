import { useEffect, useRef, type ReactNode } from 'react'

// "Refresh the active risk tab" keyboard shortcut — Ctrl+R or Cmd+R (kx-u26x).
//
// Risk dashboards have a refresh button on the toolbar that re-runs the data
// fetch. Reaching for the mouse to click it on every refresh — across deeply
// scrolled tables — is tedious. Ctrl+R is the universal "reload" shortcut,
// and traders already press it expecting a refresh. We intercept it *only*
// while a refresh target is registered: if no risk tab has mounted a handler,
// the keystroke falls through to the browser and reloads the page as usual.
//
// State is a LIFO stack of handler functions (mirroring KeyboardShortcutsSearch).
// The topmost handler wins so the most-recently mounted risk surface — a
// dialog, a focused panel — takes the shortcut over an outer page-level one.

type RefreshHandler = () => void

let handlerStack: RefreshHandler[] = []
let listenerInstalled = false

function windowKeyDown(event: KeyboardEvent): void {
  if (event.key !== 'r' && event.key !== 'R') return
  if (!(event.ctrlKey || event.metaKey)) return
  const top = handlerStack[handlerStack.length - 1]
  if (!top) return
  // preventDefault stops the browser's full-page reload — we're handling
  // the refresh in-app so a reload would discard cached state and feel
  // jarring to the user.
  event.preventDefault()
  top()
}

/** Install the singleton window keydown listener (idempotent). */
// eslint-disable-next-line react-refresh/only-export-components
export function ensureRefreshListener(): void {
  if (listenerInstalled) return
  if (typeof window === 'undefined') return
  window.addEventListener('keydown', windowKeyDown)
  listenerInstalled = true
}

/**
 * Register a refresh handler as the current target. The most-recently
 * registered handler is invoked on Ctrl+R / Cmd+R. Returns an unregister
 * function — call it on cleanup so the stack does not grow unbounded.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function registerRefreshTarget(handler: RefreshHandler): () => void {
  ensureRefreshListener()
  handlerStack.push(handler)
  return () => {
    const idx = handlerStack.lastIndexOf(handler)
    if (idx !== -1) handlerStack.splice(idx, 1)
  }
}

/**
 * Hook: while mounted, the given callback is invoked whenever the user
 * presses Ctrl+R (or Cmd+R). The latest callback always wins — changing the
 * function reference between renders does not need to re-register, because
 * the indirection through a ref means the listener always reads the current
 * value.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useRefreshShortcut(handler: RefreshHandler): void {
  const handlerRef = useRef(handler)

  useEffect(() => {
    handlerRef.current = handler
  })

  useEffect(() => {
    const trampoline = () => handlerRef.current()
    const unregister = registerRefreshTarget(trampoline)
    return unregister
  }, [])
}

/**
 * Test-only helper: wipes the handler stack between cases so one test does
 * not bleed into the next. Production code should never call this.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function __resetRefreshStackForTests(): void {
  handlerStack = []
}

interface KeyboardShortcutsRefreshProviderProps {
  children: ReactNode
}

/**
 * Mounts the global Ctrl+R listener as a side-effect of rendering. The
 * provider owns no React state — `useRefreshShortcut` pushes to the
 * module-level stack — but the explicit wrapper makes the dependency
 * obvious in the component tree.
 */
export function KeyboardShortcutsRefreshProvider({
  children,
}: KeyboardShortcutsRefreshProviderProps) {
  useEffect(() => {
    ensureRefreshListener()
  }, [])
  return <>{children}</>
}
