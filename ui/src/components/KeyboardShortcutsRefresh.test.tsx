// Tests for the Ctrl+R / Cmd+R "refresh risk tab" shortcut (kx-u26x).
//
// Risk dashboards have a refresh button that re-runs the data fetch. Tabbing
// from a deeply scrolled table to the toolbar to click that button is a chore;
// power users expect Ctrl+R / Cmd+R to do it directly. We override the
// browser's full-page reload only while a refresh target is registered —
// otherwise the user's expectation of "reload the page" still works.

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { useEffect } from 'react'

import {
  KeyboardShortcutsRefreshProvider,
  useRefreshShortcut,
  registerRefreshTarget,
  __resetRefreshStackForTests,
} from './KeyboardShortcutsRefresh'

interface HarnessProps {
  onRefresh: () => void
}

function RefreshableHarness({ onRefresh }: HarnessProps) {
  useRefreshShortcut(onRefresh)
  return <button type="button">Refresh</button>
}

describe('KeyboardShortcutsRefresh', () => {
  beforeEach(() => {
    __resetRefreshStackForTests()
  })

  it('invokes the registered refresh handler when Ctrl+R is pressed', () => {
    const onRefresh = vi.fn()
    render(
      <KeyboardShortcutsRefreshProvider>
        <RefreshableHarness onRefresh={onRefresh} />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })

    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('also responds to Cmd+R for Mac operators', () => {
    const onRefresh = vi.fn()
    render(
      <KeyboardShortcutsRefreshProvider>
        <RefreshableHarness onRefresh={onRefresh} />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'r', metaKey: true })

    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('handles uppercase R the same as lowercase r', () => {
    const onRefresh = vi.fn()
    render(
      <KeyboardShortcutsRefreshProvider>
        <RefreshableHarness onRefresh={onRefresh} />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'R', ctrlKey: true })

    expect(onRefresh).toHaveBeenCalledTimes(1)
  })

  it('only invokes the most-recently mounted refresh handler', () => {
    const outer = vi.fn()
    const inner = vi.fn()
    render(
      <KeyboardShortcutsRefreshProvider>
        <RefreshableHarness onRefresh={outer} />
        <RefreshableHarness onRefresh={inner} />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })

    expect(inner).toHaveBeenCalledTimes(1)
    expect(outer).not.toHaveBeenCalled()
  })

  it('prevents the default browser reload so the page does not refetch', () => {
    const onRefresh = vi.fn()
    render(
      <KeyboardShortcutsRefreshProvider>
        <RefreshableHarness onRefresh={onRefresh} />
      </KeyboardShortcutsRefreshProvider>,
    )

    const event = new KeyboardEvent('keydown', {
      key: 'r',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    })
    const notPrevented = window.dispatchEvent(event)
    expect(notPrevented).toBe(false)
  })

  it('does nothing when no target is registered so plain Ctrl+R still reloads', () => {
    // Stack is empty by virtue of __resetRefreshStackForTests in beforeEach.
    const event = new KeyboardEvent('keydown', {
      key: 'r',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    })
    const notPrevented = window.dispatchEvent(event)
    expect(notPrevented).toBe(true)
  })

  it('ignores plain "r" presses with no modifier', () => {
    const onRefresh = vi.fn()
    render(
      <KeyboardShortcutsRefreshProvider>
        <RefreshableHarness onRefresh={onRefresh} />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'r' })

    expect(onRefresh).not.toHaveBeenCalled()
  })

  it('unregisters its handler on unmount', () => {
    const onRefresh = vi.fn()
    const { unmount } = render(
      <KeyboardShortcutsRefreshProvider>
        <RefreshableHarness onRefresh={onRefresh} />
      </KeyboardShortcutsRefreshProvider>,
    )
    unmount()

    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })

    expect(onRefresh).not.toHaveBeenCalled()
  })

  it('registerRefreshTarget returns an unregister function that pops the stack', () => {
    const a = vi.fn()
    const b = vi.fn()
    const unregisterA = registerRefreshTarget(a)
    const unregisterB = registerRefreshTarget(b)

    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })
    expect(b).toHaveBeenCalledTimes(1)
    expect(a).not.toHaveBeenCalled()

    unregisterB()
    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })
    expect(a).toHaveBeenCalledTimes(1)

    unregisterA()
  })

  it('updates the captured handler when the hook re-renders with a new callback', () => {
    function Switching({ handler }: { handler: () => void }) {
      useRefreshShortcut(handler)
      return null
    }
    const first = vi.fn()
    const second = vi.fn()
    const { rerender } = render(
      <KeyboardShortcutsRefreshProvider>
        <Switching handler={first} />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })
    expect(first).toHaveBeenCalledTimes(1)

    rerender(
      <KeyboardShortcutsRefreshProvider>
        <Switching handler={second} />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })
    expect(second).toHaveBeenCalledTimes(1)
    expect(first).toHaveBeenCalledTimes(1)
  })

  it('matches the search-shortcut "topmost wins" semantics when handlers mount in effect order', () => {
    const handlerCalls: string[] = []
    function Ordered({ id }: { id: string }) {
      useEffect(() => {
        return registerRefreshTarget(() => handlerCalls.push(id))
      }, [id])
      return null
    }
    render(
      <KeyboardShortcutsRefreshProvider>
        <Ordered id="outer" />
        <Ordered id="inner" />
      </KeyboardShortcutsRefreshProvider>,
    )

    fireEvent.keyDown(window, { key: 'r', ctrlKey: true })

    expect(handlerCalls).toEqual(['inner'])
  })
})
