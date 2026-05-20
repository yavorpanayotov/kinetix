/**
 * Provider component for the Copilot `page_context` plumbing.
 *
 * Split from `useCopilotContext.ts` so each file satisfies
 * react-refresh's "only export components" constraint — the hook,
 * types, and serialiser live there; this file exports only the
 * provider component. See `useCopilotContext.ts` for the design
 * rationale and the checkbox-5.7 scope note.
 */

import type { ReactNode } from 'react'
import { CopilotContext, type CopilotContextInput } from './useCopilotContext'

/**
 * Provides the current page/selection snapshot to descendant Copilot
 * surfaces. Mount this near the app root, passing the live tab +
 * selection state as `value`.
 */
export function CopilotContextProvider({
  value,
  children,
}: {
  value: CopilotContextInput
  children: ReactNode
}) {
  return <CopilotContext.Provider value={value}>{children}</CopilotContext.Provider>
}
