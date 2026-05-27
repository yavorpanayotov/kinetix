// Tests for the Ctrl+/ "focus table search" shortcut (kx-bht6).

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { useRef } from 'react'

import {
  KeyboardShortcutsSearchProvider,
  useSearchFocusShortcut,
  registerSearchFocusTarget,
  __resetSearchFocusStackForTests,
} from './KeyboardShortcutsSearch'

interface SearchableHarnessProps {
  inputId: string
}

function SearchableHarness({ inputId }: SearchableHarnessProps) {
  const ref = useRef<HTMLInputElement | null>(null)
  useSearchFocusShortcut(ref)
  return (
    <input
      ref={ref}
      id={inputId}
      type="search"
      aria-label={`${inputId} search`}
      placeholder="Search…"
    />
  )
}

describe('KeyboardShortcutsSearch', () => {
  beforeEach(() => {
    __resetSearchFocusStackForTests()
  })

  it('focuses the registered search input when Ctrl+/ is pressed', () => {
    render(
      <KeyboardShortcutsSearchProvider>
        <SearchableHarness inputId="positions" />
      </KeyboardShortcutsSearchProvider>,
    )

    const input = screen.getByRole('searchbox', { name: 'positions search' })
    expect(document.activeElement).not.toBe(input)

    fireEvent.keyDown(window, { key: '/', ctrlKey: true })

    expect(document.activeElement).toBe(input)
  })

  it('also responds to Cmd+/ for Mac operators', () => {
    render(
      <KeyboardShortcutsSearchProvider>
        <SearchableHarness inputId="trades" />
      </KeyboardShortcutsSearchProvider>,
    )

    const input = screen.getByRole('searchbox', { name: 'trades search' })

    fireEvent.keyDown(window, { key: '/', metaKey: true })

    expect(document.activeElement).toBe(input)
  })

  it('focuses the most-recently mounted searchable when several are stacked', () => {
    function Page() {
      return (
        <KeyboardShortcutsSearchProvider>
          <SearchableHarness inputId="outer" />
          <SearchableHarness inputId="inner" />
        </KeyboardShortcutsSearchProvider>
      )
    }
    render(<Page />)

    fireEvent.keyDown(window, { key: '/', ctrlKey: true })

    expect(document.activeElement).toBe(
      screen.getByRole('searchbox', { name: 'inner search' }),
    )
  })

  it('prevents the default browser behaviour so Firefox quick-find does not open', () => {
    render(
      <KeyboardShortcutsSearchProvider>
        <SearchableHarness inputId="audit" />
      </KeyboardShortcutsSearchProvider>,
    )

    const event = new KeyboardEvent('keydown', {
      key: '/',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    })
    const prevented = !window.dispatchEvent(event)
    expect(prevented).toBe(true)
  })

  it('ignores plain "/" presses with no modifier so typing in a textbox still works', () => {
    const onSlash = vi.fn()
    render(
      <KeyboardShortcutsSearchProvider>
        <SearchableHarness inputId="risk" />
      </KeyboardShortcutsSearchProvider>,
    )

    const input = screen.getByRole('searchbox', { name: 'risk search' })
    input.addEventListener('keydown', () => onSlash())
    fireEvent.keyDown(input, { key: '/' })

    // Plain "/" doesn't move focus around (input was unfocused; still is).
    expect(document.activeElement).not.toBe(input)
    expect(onSlash).toHaveBeenCalled()
  })

  it('unregisters its target on unmount so future Ctrl+/ does nothing', () => {
    const { unmount } = render(
      <KeyboardShortcutsSearchProvider>
        <SearchableHarness inputId="ephemeral" />
      </KeyboardShortcutsSearchProvider>,
    )
    unmount()

    // Nothing to focus; should not throw.
    expect(() =>
      fireEvent.keyDown(window, { key: '/', ctrlKey: true }),
    ).not.toThrow()
  })

  it('registerSearchFocusTarget returns an unregister function that pops the stack', () => {
    const aInput = document.createElement('input')
    const bInput = document.createElement('input')
    document.body.append(aInput, bInput)

    const unregisterA = registerSearchFocusTarget(aInput)
    const unregisterB = registerSearchFocusTarget(bInput)

    fireEvent.keyDown(window, { key: '/', ctrlKey: true })
    expect(document.activeElement).toBe(bInput)

    unregisterB()
    bInput.blur()
    fireEvent.keyDown(window, { key: '/', ctrlKey: true })
    expect(document.activeElement).toBe(aInput)

    unregisterA()
    aInput.remove()
    bInput.remove()
  })
})
