import { useCallback, useState } from 'react'

const STORAGE_KEY = 'kinetix:workspace'
/** Current envelope version. v1 (implicit) was a flat `WorkspacePreferences`. */
const STORAGE_VERSION = 2
const DEFAULT_VIEW_NAME = 'Default'

export interface RiskDashboardSectionsState {
  /** Market Risk group (VaR dashboard + correlation heatmap). */
  marketRisk: boolean
  /** Position & Factor Risk group (positions, KRD, factor decomposition + history). */
  positionFactor: boolean
  /** P&L, Stress & Liquidity group (P&L summary, stress summary, liquidity, margin). */
  pnlStressLiquidity: boolean
  /** Limits & Jobs group (limits panel, job history). */
  limitsJobs: boolean
}

export interface WorkspacePreferences {
  defaultTab: string
  defaultBook: string | null
  timeRange: string
  chartPreferences: {
    showGrid: boolean
    showLegend: boolean
  }
  /** Per-section open/closed state for the Risk-tab Dashboard. */
  riskDashboardSections: RiskDashboardSectionsState
  /**
   * Whether the PositionGrid reveals the Details columns (Quantity / Avg Cost /
   * Market Price). A risk-first system defaults this to `false` so the trader's
   * eye lands on risk numbers first.
   */
  showPositionDetails: boolean
}

export const DEFAULT_RISK_DASHBOARD_SECTIONS: RiskDashboardSectionsState = {
  marketRisk: true,
  positionFactor: true,
  pnlStressLiquidity: true,
  limitsJobs: true,
}

export const DEFAULT_PREFERENCES: WorkspacePreferences = {
  defaultTab: 'positions',
  defaultBook: null,
  timeRange: '1d',
  chartPreferences: {
    showGrid: true,
    showLegend: true,
  },
  riskDashboardSections: DEFAULT_RISK_DASHBOARD_SECTIONS,
  showPositionDetails: false,
}

/**
 * Plan §2.3 — Saved views. A workspace consists of one or more named views;
 * each view captures the full `WorkspacePreferences` shape. The active view's
 * prefs are the source of truth for the rest of the app — every consumer of
 * `useWorkspace` reads `preferences` (the active view's prefs) just like before.
 */
export interface SavedView {
  id: string
  name: string
  prefs: WorkspacePreferences
}

interface StoredWorkspaceV2 {
  version: 2
  activeViewId: string
  views: SavedView[]
}

/** Merge partial prefs with defaults, including nested defaults. */
function mergePreferences(partial: unknown): WorkspacePreferences {
  const obj = (partial ?? {}) as Partial<WorkspacePreferences> & {
    chartPreferences?: Partial<WorkspacePreferences['chartPreferences']>
    riskDashboardSections?: Partial<RiskDashboardSectionsState>
  }
  return {
    ...DEFAULT_PREFERENCES,
    ...obj,
    chartPreferences: {
      ...DEFAULT_PREFERENCES.chartPreferences,
      ...(obj.chartPreferences ?? {}),
    },
    riskDashboardSections: {
      ...DEFAULT_PREFERENCES.riskDashboardSections,
      ...(obj.riskDashboardSections ?? {}),
    },
  }
}

function generateViewId(): string {
  // crypto.randomUUID is widely available in modern browsers and jsdom.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // Fallback: timestamp + random suffix. Sufficient for view ids.
  return `view-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function makeDefaultWorkspace(): StoredWorkspaceV2 {
  const view: SavedView = {
    id: generateViewId(),
    name: DEFAULT_VIEW_NAME,
    prefs: DEFAULT_PREFERENCES,
  }
  return { version: STORAGE_VERSION, activeViewId: view.id, views: [view] }
}

/**
 * Load the workspace from localStorage. Handles three cases:
 *   1. No saved state → fresh single-"Default" view.
 *   2. Legacy v1 shape (flat `WorkspacePreferences`) → wrap as a single "Default" view.
 *   3. v2 envelope → return as-is (with prefs merged against defaults for safety).
 */
function loadWorkspace(): StoredWorkspaceV2 {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (!stored) return makeDefaultWorkspace()
    const parsed = JSON.parse(stored)

    // v2 envelope detection — has explicit `version: 2` and a `views` array.
    if (
      parsed
      && typeof parsed === 'object'
      && parsed.version === STORAGE_VERSION
      && Array.isArray(parsed.views)
      && parsed.views.length > 0
    ) {
      const views: SavedView[] = parsed.views.map((v: Partial<SavedView>) => ({
        id: typeof v.id === 'string' && v.id ? v.id : generateViewId(),
        name: typeof v.name === 'string' && v.name.trim() ? v.name : DEFAULT_VIEW_NAME,
        prefs: mergePreferences(v.prefs),
      }))
      const activeViewId = typeof parsed.activeViewId === 'string'
        && views.some((v) => v.id === parsed.activeViewId)
        ? parsed.activeViewId
        : views[0].id
      return { version: STORAGE_VERSION, activeViewId, views }
    }

    // Legacy v1 shape — wrap as a single "Default" view.
    const view: SavedView = {
      id: generateViewId(),
      name: DEFAULT_VIEW_NAME,
      prefs: mergePreferences(parsed),
    }
    return { version: STORAGE_VERSION, activeViewId: view.id, views: [view] }
  } catch {
    return makeDefaultWorkspace()
  }
}

function saveWorkspace(state: StoredWorkspaceV2) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
}

export interface UseWorkspaceResult {
  /** Active view's preferences — every existing consumer keeps reading this. */
  preferences: WorkspacePreferences
  /** Update a single field on the active view's prefs (and persist). */
  updatePreference: <K extends keyof WorkspacePreferences>(key: K, value: WorkspacePreferences[K]) => void
  /** Reset the active view's prefs back to defaults. */
  resetPreferences: () => void

  // -- saved views (plan §2.3) --

  /** All named views, in insertion order. The first is typically "Default". */
  views: SavedView[]
  /** Id of the currently active view. */
  activeViewId: string | null
  /** Make `id` the active view; loading its prefs into the app. */
  switchView: (id: string) => void
  /**
   * Persist a new named view and activate it. When `prefs` is omitted the
   * currently-active view's prefs are cloned; pass `prefs` explicitly when
   * the caller needs to snapshot ephemeral UI state (e.g. the current tab
   * picked from React local state) into the new view without disturbing the
   * existing active view. Returns the new view's id.
   */
  saveAsNewView: (name: string, prefs?: WorkspacePreferences) => string
  /**
   * Overwrite the active view's prefs with the current ones. Mostly a no-op
   * since `updatePreference` already writes through, but exposed so the
   * "Update view" UI action has something explicit to call.
   */
  updateActiveView: () => void
  /** Delete a view by id. If it was active, fall back to the first remaining view. */
  deleteView: (id: string) => void
  /** Rename a view. Whitespace-only names are ignored. */
  renameView: (id: string, name: string) => void
}

export function useWorkspace(): UseWorkspaceResult {
  const [state, setState] = useState<StoredWorkspaceV2>(loadWorkspace)

  const activeView = state.views.find((v) => v.id === state.activeViewId) ?? state.views[0]
  const preferences = activeView?.prefs ?? DEFAULT_PREFERENCES

  const updatePreference = useCallback(<K extends keyof WorkspacePreferences>(
    key: K,
    value: WorkspacePreferences[K],
  ) => {
    setState((prev) => {
      const views = prev.views.map((view) =>
        view.id === prev.activeViewId
          ? { ...view, prefs: { ...view.prefs, [key]: value } }
          : view,
      )
      const next: StoredWorkspaceV2 = { ...prev, views }
      saveWorkspace(next)
      return next
    })
  }, [])

  const resetPreferences = useCallback(() => {
    setState((prev) => {
      const views = prev.views.map((view) =>
        view.id === prev.activeViewId ? { ...view, prefs: DEFAULT_PREFERENCES } : view,
      )
      const next: StoredWorkspaceV2 = { ...prev, views }
      saveWorkspace(next)
      return next
    })
  }, [])

  const switchView = useCallback((id: string) => {
    setState((prev) => {
      if (!prev.views.some((v) => v.id === id)) return prev
      if (prev.activeViewId === id) return prev
      const next: StoredWorkspaceV2 = { ...prev, activeViewId: id }
      saveWorkspace(next)
      return next
    })
  }, [])

  const saveAsNewView = useCallback((name: string, prefs?: WorkspacePreferences): string => {
    const trimmed = name.trim() || DEFAULT_VIEW_NAME
    const newId = generateViewId()
    setState((prev) => {
      const snapshot = prefs
        ?? prev.views.find((v) => v.id === prev.activeViewId)?.prefs
        ?? DEFAULT_PREFERENCES
      const newView: SavedView = { id: newId, name: trimmed, prefs: snapshot }
      const next: StoredWorkspaceV2 = {
        ...prev,
        views: [...prev.views, newView],
        activeViewId: newId,
      }
      saveWorkspace(next)
      return next
    })
    return newId
  }, [])

  const updateActiveView = useCallback(() => {
    // updatePreference already writes through to the active view, so this is
    // mainly a hook for an explicit "Update view" UI action. We re-persist
    // the current state defensively (so the storage write is observable).
    setState((prev) => {
      saveWorkspace(prev)
      return prev
    })
  }, [])

  const deleteView = useCallback((id: string) => {
    setState((prev) => {
      if (prev.views.length <= 1) return prev
      const remaining = prev.views.filter((v) => v.id !== id)
      if (remaining.length === prev.views.length) return prev
      const activeViewId = prev.activeViewId === id ? remaining[0].id : prev.activeViewId
      const next: StoredWorkspaceV2 = { ...prev, views: remaining, activeViewId }
      saveWorkspace(next)
      return next
    })
  }, [])

  const renameView = useCallback((id: string, name: string) => {
    const trimmed = name.trim()
    if (!trimmed) return
    setState((prev) => {
      const views = prev.views.map((view) =>
        view.id === id ? { ...view, name: trimmed } : view,
      )
      const next: StoredWorkspaceV2 = { ...prev, views }
      saveWorkspace(next)
      return next
    })
  }, [])

  return {
    preferences,
    updatePreference,
    resetPreferences,
    views: state.views,
    activeViewId: state.activeViewId,
    switchView,
    saveAsNewView,
    updateActiveView,
    deleteView,
    renameView,
  }
}
