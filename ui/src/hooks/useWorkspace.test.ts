import { renderHook, act } from '@testing-library/react'
import { describe, expect, it, beforeEach } from 'vitest'
import { useWorkspace, DEFAULT_PREFERENCES } from './useWorkspace'

const STORAGE_KEY = 'kinetix:workspace'

/** Read the saved-views envelope from localStorage. */
function readStored(): { activeViewId: string; views: Array<{ id: string; name: string; prefs: typeof DEFAULT_PREFERENCES }> } {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) throw new Error('nothing stored')
  return JSON.parse(raw)
}

/** Convenience: prefs of the currently active view. */
function readActivePrefs() {
  const stored = readStored()
  return stored.views.find((v) => v.id === stored.activeViewId)!.prefs
}

describe('useWorkspace', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('provides default preferences when none saved', () => {
    const { result } = renderHook(() => useWorkspace())

    expect(result.current.preferences).toEqual(DEFAULT_PREFERENCES)
  })

  it('saves workspace preferences to localStorage', () => {
    const { result } = renderHook(() => useWorkspace())

    act(() => {
      result.current.updatePreference('defaultTab', 'risk')
    })

    expect(readActivePrefs().defaultTab).toBe('risk')
  })

  it('loads workspace preferences on mount', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      ...DEFAULT_PREFERENCES,
      defaultTab: 'pnl',
      defaultBook: 'book-2',
    }))

    const { result } = renderHook(() => useWorkspace())

    expect(result.current.preferences.defaultTab).toBe('pnl')
    expect(result.current.preferences.defaultBook).toBe('book-2')
  })

  it('resets preferences to defaults', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      ...DEFAULT_PREFERENCES,
      defaultTab: 'risk',
    }))

    const { result } = renderHook(() => useWorkspace())

    expect(result.current.preferences.defaultTab).toBe('risk')

    act(() => {
      result.current.resetPreferences()
    })

    expect(result.current.preferences).toEqual(DEFAULT_PREFERENCES)
    expect(readActivePrefs()).toEqual(DEFAULT_PREFERENCES)
  })

  it('updates individual preference without affecting others', () => {
    const { result } = renderHook(() => useWorkspace())

    act(() => {
      result.current.updatePreference('defaultTab', 'scenarios')
    })

    expect(result.current.preferences.defaultTab).toBe('scenarios')
    expect(result.current.preferences.defaultBook).toBe(DEFAULT_PREFERENCES.defaultBook)
  })

  it('handles partial saved preferences gracefully', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ defaultTab: 'risk' }))

    const { result } = renderHook(() => useWorkspace())

    expect(result.current.preferences.defaultTab).toBe('risk')
    expect(result.current.preferences.defaultBook).toBe(DEFAULT_PREFERENCES.defaultBook)
  })

  it('defaults the Risk-dashboard sections to all open', () => {
    const { result } = renderHook(() => useWorkspace())

    expect(result.current.preferences.riskDashboardSections).toEqual({
      marketRisk: true,
      positionFactor: true,
      pnlStressLiquidity: true,
      limitsJobs: true,
    })
  })

  it('persists Risk-dashboard section collapse state to localStorage', () => {
    const { result } = renderHook(() => useWorkspace())

    act(() => {
      result.current.updatePreference('riskDashboardSections', {
        marketRisk: false,
        positionFactor: true,
        pnlStressLiquidity: true,
        limitsJobs: false,
      })
    })

    const saved = readActivePrefs()
    expect(saved.riskDashboardSections.marketRisk).toBe(false)
    expect(saved.riskDashboardSections.limitsJobs).toBe(false)
  })

  it('merges saved Risk-dashboard sections with defaults when partial', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ riskDashboardSections: { marketRisk: false } }),
    )

    const { result } = renderHook(() => useWorkspace())

    // Saved field overrides default
    expect(result.current.preferences.riskDashboardSections.marketRisk).toBe(false)
    // Missing nested keys fall back to defaults
    expect(result.current.preferences.riskDashboardSections.positionFactor).toBe(true)
    expect(result.current.preferences.riskDashboardSections.pnlStressLiquidity).toBe(true)
    expect(result.current.preferences.riskDashboardSections.limitsJobs).toBe(true)
  })

  it('defaults showPositionDetails to false (risk-first PositionGrid)', () => {
    const { result } = renderHook(() => useWorkspace())

    expect(result.current.preferences.showPositionDetails).toBe(false)
  })

  it('persists showPositionDetails to localStorage', () => {
    const { result } = renderHook(() => useWorkspace())

    act(() => {
      result.current.updatePreference('showPositionDetails', true)
    })

    expect(readActivePrefs().showPositionDetails).toBe(true)
  })

  describe('named saved views (plan §2.3)', () => {
    it('migrates a legacy single-workspace shape into a "Default" view', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        ...DEFAULT_PREFERENCES,
        defaultTab: 'risk',
        defaultBook: 'book-9',
      }))

      const { result } = renderHook(() => useWorkspace())

      expect(result.current.views).toHaveLength(1)
      expect(result.current.views[0].name).toBe('Default')
      expect(result.current.views[0].prefs.defaultTab).toBe('risk')
      expect(result.current.views[0].prefs.defaultBook).toBe('book-9')
      expect(result.current.activeViewId).toBe(result.current.views[0].id)
    })

    it('exposes a single "Default" view when storage is empty', () => {
      const { result } = renderHook(() => useWorkspace())

      expect(result.current.views).toHaveLength(1)
      expect(result.current.views[0].name).toBe('Default')
      expect(result.current.activeViewId).toBe(result.current.views[0].id)
      expect(result.current.preferences).toEqual(DEFAULT_PREFERENCES)
    })

    it('snapshots an explicit prefs object into a new view without touching the existing active view', () => {
      const { result } = renderHook(() => useWorkspace())

      // Customise the active view first so we can detect side-effects.
      act(() => {
        result.current.updatePreference('defaultTab', 'positions')
      })
      const defaultId = result.current.activeViewId!

      let newId: string | undefined
      act(() => {
        newId = result.current.saveAsNewView('Risk morning', {
          ...DEFAULT_PREFERENCES,
          defaultTab: 'risk',
        })
      })

      // The new view holds the explicit snapshot.
      const created = result.current.views.find((v) => v.id === newId)
      expect(created?.prefs.defaultTab).toBe('risk')
      // The old view is untouched.
      const original = result.current.views.find((v) => v.id === defaultId)
      expect(original?.prefs.defaultTab).toBe('positions')
    })

    it('saves the current prefs as a new named view and activates it', () => {
      const { result } = renderHook(() => useWorkspace())

      act(() => {
        result.current.updatePreference('defaultTab', 'risk')
      })

      let createdId: string | undefined
      act(() => {
        createdId = result.current.saveAsNewView('Equities morning check')
      })

      expect(result.current.views).toHaveLength(2)
      const created = result.current.views.find((v) => v.id === createdId)
      expect(created?.name).toBe('Equities morning check')
      expect(created?.prefs.defaultTab).toBe('risk')
      expect(result.current.activeViewId).toBe(createdId)

      const stored = readStored()
      expect(stored.activeViewId).toBe(createdId)
      expect(stored.views.find((v) => v.id === createdId)?.name).toBe('Equities morning check')
    })

    it('switching the active view loads its prefs', () => {
      const { result } = renderHook(() => useWorkspace())

      // Customise current ("Default") view, then save a new one with different prefs.
      act(() => {
        result.current.updatePreference('defaultTab', 'positions')
      })
      const defaultId = result.current.activeViewId!

      let newId: string | undefined
      act(() => {
        newId = result.current.saveAsNewView('Credit stress monitor')
      })
      act(() => {
        result.current.updatePreference('defaultTab', 'risk')
        result.current.updatePreference('defaultBook', 'book-7')
      })

      // Switch back to the original "Default" view.
      act(() => {
        result.current.switchView(defaultId)
      })

      expect(result.current.activeViewId).toBe(defaultId)
      expect(result.current.preferences.defaultTab).toBe('positions')
      expect(result.current.preferences.defaultBook).toBe(DEFAULT_PREFERENCES.defaultBook)

      // And back to the saved view.
      act(() => {
        result.current.switchView(newId!)
      })
      expect(result.current.preferences.defaultTab).toBe('risk')
      expect(result.current.preferences.defaultBook).toBe('book-7')
    })

    it('updateActiveView overwrites the active view\'s prefs with the current ones', () => {
      const { result } = renderHook(() => useWorkspace())

      let newId: string | undefined
      act(() => {
        newId = result.current.saveAsNewView('Morning')
      })

      // Change prefs while "Morning" is active — updatePreference saves into the active view.
      act(() => {
        result.current.updatePreference('defaultTab', 'pnl')
      })

      const stored = readStored()
      expect(stored.views.find((v) => v.id === newId)?.prefs.defaultTab).toBe('pnl')

      // Explicit no-op call should leave the prefs unchanged but still work.
      act(() => {
        result.current.updateActiveView()
      })
      expect(readStored().views.find((v) => v.id === newId)?.prefs.defaultTab).toBe('pnl')
    })

    it('deleting the active view falls back to the first remaining view', () => {
      const { result } = renderHook(() => useWorkspace())

      const defaultId = result.current.activeViewId!
      let secondId: string | undefined
      act(() => {
        secondId = result.current.saveAsNewView('Second')
      })

      // Active is currently "Second" — deleting it should fall back to "Default".
      act(() => {
        result.current.deleteView(secondId!)
      })

      expect(result.current.views).toHaveLength(1)
      expect(result.current.activeViewId).toBe(defaultId)
    })

    it('deleting a non-active view leaves the active view alone', () => {
      const { result } = renderHook(() => useWorkspace())

      const defaultId = result.current.activeViewId!
      let secondId: string | undefined
      act(() => {
        secondId = result.current.saveAsNewView('Second')
      })
      // Switch back to the original — "Second" is now inactive.
      act(() => {
        result.current.switchView(defaultId)
      })

      act(() => {
        result.current.deleteView(secondId!)
      })

      expect(result.current.views).toHaveLength(1)
      expect(result.current.activeViewId).toBe(defaultId)
    })

    it('refuses to delete the last remaining view', () => {
      const { result } = renderHook(() => useWorkspace())

      const onlyId = result.current.activeViewId!

      act(() => {
        result.current.deleteView(onlyId)
      })

      expect(result.current.views).toHaveLength(1)
      expect(result.current.activeViewId).toBe(onlyId)
    })

    it('renames a view', () => {
      const { result } = renderHook(() => useWorkspace())

      const id = result.current.activeViewId!
      act(() => {
        result.current.renameView(id, 'My morning routine')
      })

      expect(result.current.views[0].name).toBe('My morning routine')
      expect(readStored().views[0].name).toBe('My morning routine')
    })

    it('ignores rename with empty / whitespace name', () => {
      const { result } = renderHook(() => useWorkspace())

      const id = result.current.activeViewId!
      const originalName = result.current.views[0].name
      act(() => {
        result.current.renameView(id, '   ')
      })

      expect(result.current.views[0].name).toBe(originalName)
    })
  })
})
