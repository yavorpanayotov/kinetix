import { useEffect } from 'react'

// Escape-key handler stack (kx-s19a).
//
// The stack is a LIFO of handlers; pressing Escape runs the most-recently
// pushed one. Modals, popovers, and command palettes register their close
// callback while open; the top of the stack always wins so pressing Escape
// twice dismisses the front-most layer then the one underneath, rather than
// collapsing every overlay at once.

export type EscapeHandler = () => void

let handlerStack: EscapeHandler[] = []
let listenerInstalled = false

function windowKeyDown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  const top = handlerStack[handlerStack.length - 1]
  if (!top) return
  top()
}

/** Install the singleton window keydown listener (idempotent). */
export function ensureEscapeListener(): void {
  if (listenerInstalled) return
  if (typeof window === 'undefined') return
  window.addEventListener('keydown', windowKeyDown)
  listenerInstalled = true
}

/**
 * Register a handler to run when Escape is pressed. The most-recently
 * registered handler runs first; subsequent presses run the next one down
 * the stack. Returns a function that unregisters the handler — call it on
 * cleanup so the stack does not grow unbounded.
 */
export function registerEscapeHandler(handler: EscapeHandler): () => void {
  ensureEscapeListener()
  handlerStack.push(handler)
  return () => {
    const idx = handlerStack.lastIndexOf(handler)
    if (idx !== -1) handlerStack.splice(idx, 1)
  }
}

/**
 * Hook: while `active` is true, push `onClose` onto the Escape stack. When
 * the component unmounts (or `active` flips false) the handler is removed.
 */
export function useEscapeDismiss(active: boolean, onClose: EscapeHandler): void {
  useEffect(() => {
    if (!active) return
    const unregister = registerEscapeHandler(onClose)
    return unregister
  }, [active, onClose])
}

/**
 * Test-only helper: wipes the handler stack between cases so one test does
 * not bleed into the next. Production code should never call this.
 */
export function __resetEscapeStackForTests(): void {
  handlerStack = []
}
