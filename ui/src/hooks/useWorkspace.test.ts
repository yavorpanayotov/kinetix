import { renderHook, act } from '@testing-library/react'
import { describe, expect, it, beforeEach } from 'vitest'
import { useWorkspace, DEFAULT_PREFERENCES } from './useWorkspace'

const STORAGE_KEY = 'kinetix:workspace'

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

    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY)!)
    expect(saved.defaultTab).toBe('risk')
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
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY)!)
    expect(saved).toEqual(DEFAULT_PREFERENCES)
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

    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY)!)
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
})
