import { useCallback, useState } from 'react'

const STORAGE_KEY = 'kinetix:workspace'

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

function loadPreferences(): WorkspacePreferences {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (!stored) return DEFAULT_PREFERENCES
    const parsed = JSON.parse(stored)
    return {
      ...DEFAULT_PREFERENCES,
      ...parsed,
      // Merge nested objects so partial saved state falls back to defaults
      chartPreferences: {
        ...DEFAULT_PREFERENCES.chartPreferences,
        ...(parsed.chartPreferences ?? {}),
      },
      riskDashboardSections: {
        ...DEFAULT_PREFERENCES.riskDashboardSections,
        ...(parsed.riskDashboardSections ?? {}),
      },
    }
  } catch {
    return DEFAULT_PREFERENCES
  }
}

function savePreferences(prefs: WorkspacePreferences) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs))
}

export interface UseWorkspaceResult {
  preferences: WorkspacePreferences
  updatePreference: <K extends keyof WorkspacePreferences>(key: K, value: WorkspacePreferences[K]) => void
  resetPreferences: () => void
}

export function useWorkspace(): UseWorkspaceResult {
  const [preferences, setPreferences] = useState<WorkspacePreferences>(loadPreferences)

  const updatePreference = useCallback(<K extends keyof WorkspacePreferences>(
    key: K,
    value: WorkspacePreferences[K],
  ) => {
    setPreferences((prev) => {
      const next = { ...prev, [key]: value }
      savePreferences(next)
      return next
    })
  }, [])

  const resetPreferences = useCallback(() => {
    setPreferences(DEFAULT_PREFERENCES)
    savePreferences(DEFAULT_PREFERENCES)
  }, [])

  return { preferences, updatePreference, resetPreferences }
}
