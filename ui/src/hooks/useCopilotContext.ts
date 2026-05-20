/**
 * `page_context` plumbing for the v2 Copilot surfaces.
 *
 * The Copilot endpoints (`POST /api/v1/insights/chat`, the inline
 * explainers) take a free-form `page_context` dict that tells the model
 * *where* the user is and *what* they have selected — see
 * `ui/src/api/copilot.ts` (`ChatRequest.page_context`). This module
 * centralises the construction of that dict so both `<ExplainButton>`
 * and `<CommandPalette>` derive it identically, without prop-drilling
 * the active tab and selection state through every intermediate
 * component.
 *
 * The app does not use react-router — `App.tsx` tracks the current
 * "route" as a `Tab` string. `App.tsx` owns the tab + selection state
 * and feeds a {@link CopilotContextInput} snapshot into
 * `CopilotContextProvider` (see `CopilotContextProvider.tsx`);
 * consumers call {@link useCopilotContext} to read the serialised,
 * model-ready {@link CopilotPageContext}.
 *
 * The provider component lives in a sibling file
 * (`CopilotContextProvider.tsx`) so this module exports only the
 * context object, hook, types, and pure serialiser — keeping
 * react-refresh's "components only" constraint satisfied while still
 * letting consumers import the hook from one place.
 *
 * Scope note (plan checkbox 5.7): this checkbox ships the hook,
 * provider, pure serialiser, and their unit tests. Wiring
 * `<CopilotContextProvider>` into `App.tsx` and consuming the hook
 * inside `<ExplainButton>` / `<CommandPalette>` is deferred to the
 * inline-explainer rollout (PR 9) — the acceptance for 5.7 is purely
 * `npm run test -- useCopilotContext`. The hook degrades gracefully
 * (`{ page: 'unknown' }`) when no provider is mounted, so it is safe to
 * adopt incrementally.
 */

import { createContext, useContext, useMemo } from 'react'

/** Sentinel `bookId` meaning "all books" — mirrors `useBookSelector`. */
const ALL_BOOKS = '__ALL__'

/**
 * The raw inputs the app feeds into the copilot-context provider.
 *
 * `App.tsx` owns this state (active tab, book selection, etc.) and
 * passes a snapshot down; the provider memoises the serialised
 * `page_context` so consumers re-render only when the snapshot changes.
 */
export interface CopilotContextInput {
  /** Active tab — the app's notion of "route". */
  route: string
  /** Selected book id, or null when none / all-books. */
  bookId?: string | null
  /** Active scenario name/id when on the scenarios tab. */
  scenario?: string | null
  /**
   * Identifier of the currently-displayed VaR result (e.g. a jobId
   * or 'latest'), when the risk tab has one rendered.
   */
  varResultId?: string | null
  /**
   * Free-form extra selections a surface wants to attach (e.g. a
   * focused instrument id). Merged verbatim into page_context.
   */
  extra?: Record<string, unknown>
}

/**
 * The serialised page_context the copilot endpoints consume. Always
 * carries `page`; other keys appear only when the matching selection
 * is present so the model isn't fed null noise.
 */
export type CopilotPageContext = Record<string, unknown> & { page: string }

/**
 * The React context carrying the raw app snapshot. Consumed by
 * {@link useCopilotContext} and populated by `CopilotContextProvider`.
 */
export const CopilotContext = createContext<CopilotContextInput | null>(null)

/**
 * Returns the serialised `page_context` for the current surface.
 *
 * When called outside a provider, returns a minimal context of
 * `{ page: 'unknown' }` rather than throwing — the copilot is a
 * progressive enhancement and a missing provider should degrade,
 * not crash. The result reference is stable while the provider's
 * input snapshot is unchanged.
 */
export function useCopilotContext(): CopilotPageContext {
  const input = useContext(CopilotContext)
  return useMemo(() => serialiseCopilotContext(input), [input])
}

/**
 * Pure serialiser — exported for direct unit testing.
 *
 * Route-agnostic by design: the per-route variety in the resulting
 * shape comes from the inputs the app naturally supplies on each tab,
 * not from branching on the route string here.
 */
export function serialiseCopilotContext(
  input: CopilotContextInput | null,
): CopilotPageContext {
  if (input === null) {
    return { page: 'unknown' }
  }

  const context: CopilotPageContext = { page: input.route }

  if (typeof input.bookId === 'string' && input.bookId.length > 0) {
    if (input.bookId === ALL_BOOKS) {
      // The user is looking across every book — tell the model the
      // scope rather than a (non-existent) concrete book id.
      context.book_scope = 'all'
    } else {
      context.book_id = input.bookId
    }
  }

  if (typeof input.scenario === 'string' && input.scenario.length > 0) {
    context.scenario = input.scenario
  }

  if (typeof input.varResultId === 'string' && input.varResultId.length > 0) {
    context.var_result_id = input.varResultId
  }

  // Merge surface-supplied extras last so a surface can override a
  // derived key. `undefined` extra is a no-op.
  if (input.extra !== undefined) {
    Object.assign(context, input.extra)
  }

  return context
}
