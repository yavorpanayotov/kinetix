import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CopyableCellTable } from './KeyboardShortcutsCopy'

interface ClipboardMock {
  writeText: ReturnType<typeof vi.fn>
}

function installClipboard(): ClipboardMock {
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  })
  return { writeText }
}

const rows = [
  { id: '1', symbol: 'AAPL', value: '123.45' },
  { id: '2', symbol: 'MSFT', value: '456.78' },
]

describe('CopyableCellTable Shift+C shortcut', () => {
  let clip: ClipboardMock

  beforeEach(() => {
    clip = installClipboard()
  })

  it('copies the focused cell value when Shift+C is pressed', () => {
    render(<CopyableCellTable rows={rows} />)

    const cell = screen.getByRole('gridcell', { name: '123.45' })
    cell.focus()
    expect(cell).toHaveFocus()

    fireEvent.keyDown(cell, { key: 'C', shiftKey: true })

    expect(clip.writeText).toHaveBeenCalledTimes(1)
    expect(clip.writeText).toHaveBeenCalledWith('123.45')
  })

  it('copies whichever cell is currently focused', () => {
    render(<CopyableCellTable rows={rows} />)

    const cell = screen.getByRole('gridcell', { name: 'MSFT' })
    cell.focus()
    fireEvent.keyDown(cell, { key: 'C', shiftKey: true })

    expect(clip.writeText).toHaveBeenCalledWith('MSFT')
  })

  it('does not copy on plain C without the Shift modifier', () => {
    render(<CopyableCellTable rows={rows} />)

    const cell = screen.getByRole('gridcell', { name: '123.45' })
    cell.focus()
    fireEvent.keyDown(cell, { key: 'c', shiftKey: false })

    expect(clip.writeText).not.toHaveBeenCalled()
  })

  it('does not copy when Shift+C is combined with Ctrl or Meta', () => {
    render(<CopyableCellTable rows={rows} />)

    const cell = screen.getByRole('gridcell', { name: '123.45' })
    cell.focus()
    fireEvent.keyDown(cell, { key: 'C', shiftKey: true, ctrlKey: true })
    fireEvent.keyDown(cell, { key: 'C', shiftKey: true, metaKey: true })

    expect(clip.writeText).not.toHaveBeenCalled()
  })

  it('renders an aria announcement after a successful copy', () => {
    render(<CopyableCellTable rows={rows} />)
    const cell = screen.getByRole('gridcell', { name: '456.78' })
    cell.focus()
    fireEvent.keyDown(cell, { key: 'C', shiftKey: true })

    const live = screen.getByRole('status')
    expect(live).toHaveTextContent('Copied 456.78 to clipboard')
  })
})
