import { useEffect, type ReactNode } from 'react'

// Global Escape key handler that closes the topmost open layer (kx-s19a).
//
// Modals, popovers, and command palettes each want to be dismissed by the
// user pressing Escape. Wiring a keydown listener per component leads to
// confusing duplicates: pressing Escape with two modals open should close
// only the most-recently-opened one, not both at once. The companion
// `escapeStack` module owns the LIFO handler stack and the single
// window-level listener; this file hosts the React surface — a provider
// component that makes the dependency visible in the tree.

import { ensureEscapeListener } from './escapeStack'

interface EscapeProviderProps {
  children: ReactNode
}

/**
 * Mounts the global Escape listener as a side-effect of rendering. The
 * provider itself owns no React state — `useEscapeDismiss` pushes to the
 * module-level stack — but having an explicit wrapper makes the dependency
 * obvious in the component tree.
 */
export function EscapeProvider({ children }: EscapeProviderProps) {
  useEffect(() => {
    ensureEscapeListener()
  }, [])
  return <>{children}</>
}
