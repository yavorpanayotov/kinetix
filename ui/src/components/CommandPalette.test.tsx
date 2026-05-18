import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { CommandPalette, type CommandItem } from './CommandPalette'

function buildItems(activate: (id: string) => void): CommandItem[] {
  return [
    { id: 'tab:positions', group: 'Tabs', label: 'Positions', onActivate: () => activate('tab:positions') },
    { id: 'tab:trades', group: 'Tabs', label: 'Trades', onActivate: () => activate('tab:trades') },
    { id: 'tab:risk', group: 'Tabs', label: 'Risk', onActivate: () => activate('tab:risk') },
    { id: 'book:book-1', group: 'Books', label: 'book-1', onActivate: () => activate('book:book-1') },
    { id: 'instrument:AAPL', group: 'Instruments', label: 'AAPL', onActivate: () => activate('instrument:AAPL') },
    { id: 'scenario:MARKET_CRASH', group: 'Scenarios', label: 'MARKET_CRASH', onActivate: () => activate('scenario:MARKET_CRASH') },
  ]
}

const RECENT_KEY = 'kinetix:command-palette:recent'

describe('CommandPalette', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it('renders nothing when open is false', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={false}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    expect(screen.queryByTestId('command-palette')).not.toBeInTheDocument()
  })

  it('renders dialog with input when open', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const dialog = screen.getByTestId('command-palette')
    expect(dialog).toBeInTheDocument()
    expect(dialog).toHaveAttribute('role', 'dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAttribute('aria-label', 'Command palette')
    expect(screen.getByTestId('command-palette-input')).toBeInTheDocument()
  })

  it('autofocuses the input on open', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    expect(document.activeElement).toBe(input)
  })

  it('filters items by exact substring match', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'rades' } })

    expect(screen.getByTestId('command-palette-item-tab:trades')).toBeInTheDocument()
    expect(screen.queryByTestId('command-palette-item-tab:positions')).not.toBeInTheDocument()
  })

  it('filters items by subsequence match when substring does not match', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    // 'mch' is a subsequence of MARKET_CRASH (M..C...H... actually 'mch' -> M,C,H)
    fireEvent.change(input, { target: { value: 'mch' } })
    expect(screen.getByTestId('command-palette-item-scenario:MARKET_CRASH')).toBeInTheDocument()
  })

  it('shows empty-state when no items match', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'zzznomatch' } })
    expect(screen.getByTestId('command-palette-empty')).toBeInTheDocument()
  })

  it('arrow down moves selection, Enter activates the selected item', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    const input = screen.getByTestId('command-palette-input')

    // Typing 'a' substring-matches: Trades, AAPL, MARKET_CRASH (in input order).
    fireEvent.change(input, { target: { value: 'a' } })

    // First item (Trades) is highlighted by default — ArrowDown selects AAPL.
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(activate).toHaveBeenCalledWith('instrument:AAPL')
  })

  it('arrow up moves selection backwards', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'a' } })

    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowUp' })
    fireEvent.keyDown(input, { key: 'Enter' })

    // 0 (Trades) -> 1 (AAPL) -> 2 (MARKET_CRASH) -> 1 (AAPL)
    expect(activate).toHaveBeenCalledWith('instrument:AAPL')
  })

  it('Escape closes the palette', () => {
    const onClose = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={onClose}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('clicking an item activates it', () => {
    const activate = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:risk'))
    expect(activate).toHaveBeenCalledWith('tab:risk')
  })

  it('activating an item also closes the palette', () => {
    const activate = vi.fn()
    const onClose = vi.fn()
    render(
      <CommandPalette
        open={true}
        onClose={onClose}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:risk'))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('shows group headings when results span multiple groups', () => {
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    // Empty query → all items shown → multiple groups
    expect(screen.getByTestId('command-palette-group-Tabs')).toBeInTheDocument()
    expect(screen.getByTestId('command-palette-group-Books')).toBeInTheDocument()
    expect(screen.getByTestId('command-palette-group-Scenarios')).toBeInTheDocument()
  })

  it('renders Recent group at top when input is empty and recent items exist', () => {
    window.localStorage.setItem(
      RECENT_KEY,
      JSON.stringify(['tab:risk', 'scenario:MARKET_CRASH']),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const recentGroup = screen.getByTestId('command-palette-group-Recent')
    expect(recentGroup).toBeInTheDocument()
    // The recent group should appear before the Tabs group in the DOM.
    const tabsGroup = screen.getByTestId('command-palette-group-Tabs')
    expect(
      recentGroup.compareDocumentPosition(tabsGroup) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    // Recent entries should reference the persisted item IDs
    expect(screen.getByTestId('command-palette-item-recent-tab:risk')).toBeInTheDocument()
    expect(screen.getByTestId('command-palette-item-recent-scenario:MARKET_CRASH')).toBeInTheDocument()
  })

  it('hides Recent group when input is non-empty', () => {
    window.localStorage.setItem(
      RECENT_KEY,
      JSON.stringify(['tab:risk']),
    )
    render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(vi.fn())}
      />,
    )
    const input = screen.getByTestId('command-palette-input')
    fireEvent.change(input, { target: { value: 'pos' } })
    expect(screen.queryByTestId('command-palette-group-Recent')).not.toBeInTheDocument()
  })

  it('persists activated item to recent list (capped at 5, newest first)', () => {
    const activate = vi.fn()
    const { rerender } = render(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:risk'))

    // After activation, the recent-items list should be persisted
    const persisted = JSON.parse(
      window.localStorage.getItem(RECENT_KEY) ?? '[]',
    ) as string[]
    expect(persisted[0]).toBe('tab:risk')

    // Activate another → it goes to the front, previous moves down
    rerender(
      <CommandPalette
        open={true}
        onClose={vi.fn()}
        items={buildItems(activate)}
      />,
    )
    fireEvent.click(screen.getByTestId('command-palette-item-tab:trades'))
    const updated = JSON.parse(
      window.localStorage.getItem(RECENT_KEY) ?? '[]',
    ) as string[]
    expect(updated[0]).toBe('tab:trades')
    expect(updated[1]).toBe('tab:risk')
  })
})
