import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ExportableTable } from './KeyboardShortcutsExport'

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

describe('ExportableTable Ctrl+E shortcut', () => {
  let clip: ClipboardMock

  beforeEach(() => {
    clip = installClipboard()
  })

  it('exports visible rows as CSV when Ctrl+E is pressed', () => {
    render(<ExportableTable rows={rows} />)

    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e', ctrlKey: true })

    expect(clip.writeText).toHaveBeenCalledTimes(1)
    const csv = clip.writeText.mock.calls[0][0] as string
    expect(csv).toContain('symbol,value')
    expect(csv).toContain('AAPL,123.45')
    expect(csv).toContain('MSFT,456.78')
  })

  it('exports via Meta+E on macOS too', () => {
    render(<ExportableTable rows={rows} />)

    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e', metaKey: true })

    expect(clip.writeText).toHaveBeenCalledTimes(1)
  })

  it('accepts both upper and lower case E', () => {
    render(<ExportableTable rows={rows} />)
    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'E', ctrlKey: true })
    expect(clip.writeText).toHaveBeenCalledTimes(1)
  })

  it('does not export on plain E without a modifier', () => {
    render(<ExportableTable rows={rows} />)
    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e' })
    expect(clip.writeText).not.toHaveBeenCalled()
  })

  it('does not export on Ctrl+E with extra Alt or Shift modifiers', () => {
    render(<ExportableTable rows={rows} />)
    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e', ctrlKey: true, altKey: true })
    fireEvent.keyDown(table, { key: 'e', ctrlKey: true, shiftKey: true })
    expect(clip.writeText).not.toHaveBeenCalled()
  })

  it('quotes CSV cells that contain commas, quotes, or newlines', () => {
    const tricky = [
      { id: '1', symbol: 'A,B', value: 'plain' },
      { id: '2', symbol: 'has "quote"', value: 'line\nbreak' },
    ]
    render(<ExportableTable rows={tricky} />)
    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e', ctrlKey: true })
    const csv = clip.writeText.mock.calls[0][0] as string
    expect(csv).toContain('"A,B",plain')
    expect(csv).toContain('"has ""quote""","line\nbreak"')
  })

  it('announces the export via aria-live for screen readers', () => {
    render(<ExportableTable rows={rows} />)
    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e', ctrlKey: true })
    expect(screen.getByRole('status')).toHaveTextContent(/exported 2 rows/i)
  })

  it('reports the actual visible row count in the announcement', () => {
    render(<ExportableTable rows={rows.slice(0, 1)} />)
    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e', ctrlKey: true })
    expect(screen.getByRole('status')).toHaveTextContent(/exported 1 row\b/i)
  })

  it('does nothing when there are no rows to export', () => {
    render(<ExportableTable rows={[]} />)
    const table = screen.getByRole('table')
    fireEvent.keyDown(table, { key: 'e', ctrlKey: true })
    expect(clip.writeText).not.toHaveBeenCalled()
  })
})
