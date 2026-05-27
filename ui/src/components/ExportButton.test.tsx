import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ExportButton } from './ExportButton'

describe('ExportButton', () => {
  it('renders enabled when there is at least one row', () => {
    render(<ExportButton rowCount={3} onExport={() => {}} />)
    const btn = screen.getByRole('button', { name: /export/i })
    expect(btn).toBeEnabled()
    expect(btn).toHaveAttribute('aria-disabled', 'false')
  })

  it('calls onExport when clicked with at least one row', () => {
    const onExport = vi.fn()
    render(<ExportButton rowCount={2} onExport={onExport} />)
    fireEvent.click(screen.getByRole('button', { name: /export/i }))
    expect(onExport).toHaveBeenCalledTimes(1)
  })

  it('is disabled when rowCount is zero', () => {
    render(<ExportButton rowCount={0} onExport={() => {}} />)
    const btn = screen.getByRole('button', { name: /export/i })
    expect(btn).toBeDisabled()
    expect(btn).toHaveAttribute('aria-disabled', 'true')
  })

  it('does not call onExport when clicked while disabled', () => {
    const onExport = vi.fn()
    render(<ExportButton rowCount={0} onExport={onExport} />)
    fireEvent.click(screen.getByRole('button', { name: /export/i }))
    expect(onExport).not.toHaveBeenCalled()
  })

  it('exposes a tooltip explaining why it is disabled', () => {
    render(<ExportButton rowCount={0} onExport={() => {}} />)
    const btn = screen.getByRole('button', { name: /export/i })
    // The button is described by its tooltip so screen readers surface the
    // explanation even when no mouse is in use.
    const describedBy = btn.getAttribute('aria-describedby')
    expect(describedBy).toBeTruthy()
    const tooltip = document.getElementById(describedBy as string)
    expect(tooltip).not.toBeNull()
    expect(tooltip).toHaveTextContent(/no rows to export/i)
    expect(tooltip).toHaveAttribute('role', 'tooltip')
  })

  it('does not render the tooltip when the button is enabled', () => {
    render(<ExportButton rowCount={5} onExport={() => {}} />)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('applies a visibly-disabled style class when there are no rows', () => {
    render(<ExportButton rowCount={0} onExport={() => {}} />)
    const btn = screen.getByRole('button', { name: /export/i })
    // The disabled state must be communicated visually too, not by aria
    // alone — Tailwind opacity-50 + cursor-not-allowed is the project's
    // standard pair for "looks dimmed".
    expect(btn.className).toMatch(/opacity-50/)
    expect(btn.className).toMatch(/cursor-not-allowed/)
  })
})
