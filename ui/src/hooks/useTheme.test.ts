import { renderHook, act } from '@testing-library/react'
import { describe, expect, it, beforeEach } from 'vitest'
import { useTheme } from './useTheme'

describe('useTheme', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  it('defaults to dark mode when no preference is stored', () => {
    const { result } = renderHook(() => useTheme())

    expect(result.current.isDark).toBe(true)
  })

  it('respects an explicit light preference from localStorage', () => {
    localStorage.setItem('kinetix:theme', 'light')

    const { result } = renderHook(() => useTheme())

    expect(result.current.isDark).toBe(false)
  })

  it('loads an explicit dark preference from localStorage', () => {
    localStorage.setItem('kinetix:theme', 'dark')

    const { result } = renderHook(() => useTheme())

    expect(result.current.isDark).toBe(true)
  })

  it('toggles from the default dark mode to light on first click', () => {
    const { result } = renderHook(() => useTheme())

    expect(result.current.isDark).toBe(true)

    act(() => {
      result.current.toggle()
    })

    expect(result.current.isDark).toBe(false)
  })

  it('persists the user preference in localStorage on each toggle', () => {
    const { result } = renderHook(() => useTheme())

    // Default dark is written on first mount so the preference survives reloads
    // even before the user touches the toggle.
    expect(localStorage.getItem('kinetix:theme')).toBe('dark')

    act(() => {
      result.current.toggle()
    })

    expect(localStorage.getItem('kinetix:theme')).toBe('light')

    act(() => {
      result.current.toggle()
    })

    expect(localStorage.getItem('kinetix:theme')).toBe('dark')
  })

  it('applies the dark class to the html element', () => {
    const { result } = renderHook(() => useTheme())

    expect(document.documentElement.classList.contains('dark')).toBe(true)

    act(() => {
      result.current.toggle()
    })

    expect(document.documentElement.classList.contains('dark')).toBe(false)

    act(() => {
      result.current.toggle()
    })

    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })
})
