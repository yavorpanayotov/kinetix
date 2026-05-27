import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { ColumnVisibilityTable } from './ColumnVisibilityTable'
import type { GreeksColumnKey } from './ColumnVisibilityTable'

interface Row {
  symbol: string
  delta: number
  gamma: number
  vega: number
  theta: number
}

const rows: Row[] = [
  { symbol: 'AAPL', delta: 0.52, gamma: 0.014, vega: 0.21, theta: -0.04 },
  { symbol: 'MSFT', delta: 0.41, gamma: 0.011, vega: 0.18, theta: -0.03 },
]

const columns: Array<{
  key: GreeksColumnKey
  label: string
  optional: boolean
  render: (r: Row) => string
}> = [
  { key: 'symbol', label: 'Symbol', optional: false, render: r => r.symbol },
  { key: 'delta', label: 'Delta', optional: false, render: r => r.delta.toFixed(2) },
  { key: 'gamma', label: 'Gamma', optional: true, render: r => r.gamma.toFixed(3) },
  { key: 'vega', label: 'Vega', optional: true, render: r => r.vega.toFixed(2) },
  { key: 'theta', label: 'Theta', optional: true, render: r => r.theta.toFixed(2) },
]

describe('ColumnVisibilityTable', () => {
  it('renders every column when no columns have been hidden', () => {
    render(<ColumnVisibilityTable rows={rows} columns={columns} rowKey={r => r.symbol} />)
    expect(screen.getByRole('columnheader', { name: 'Symbol' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Delta' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Gamma' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Vega' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Theta' })).toBeInTheDocument()
  })

  it('exposes a toolbar button that opens the column visibility menu', () => {
    render(<ColumnVisibilityTable rows={rows} columns={columns} rowKey={r => r.symbol} />)

    const toggle = screen.getByRole('button', { name: /columns/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    const menu = screen.getByRole('menu', { name: /columns/i })
    // Only optional columns appear as toggle items — required columns cannot
    // be hidden, so we don't surface a toggle that would mislead the user.
    const items = within(menu).getAllByRole('menuitemcheckbox')
    expect(items.map(i => i.textContent)).toEqual(['Gamma', 'Vega', 'Theta'])
    items.forEach(item => expect(item).toHaveAttribute('aria-checked', 'true'))
  })

  it('hides an optional column when its menu toggle is clicked', () => {
    render(<ColumnVisibilityTable rows={rows} columns={columns} rowKey={r => r.symbol} />)
    fireEvent.click(screen.getByRole('button', { name: /columns/i }))
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: 'Vega' }))

    expect(screen.queryByRole('columnheader', { name: 'Vega' })).not.toBeInTheDocument()
    // Other columns stay visible.
    expect(screen.getByRole('columnheader', { name: 'Gamma' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Theta' })).toBeInTheDocument()
    // Toggle is now off.
    expect(screen.getByRole('menuitemcheckbox', { name: 'Vega' })).toHaveAttribute(
      'aria-checked',
      'false',
    )
  })

  it('restores a hidden column when its menu toggle is clicked again', () => {
    render(<ColumnVisibilityTable rows={rows} columns={columns} rowKey={r => r.symbol} />)
    fireEvent.click(screen.getByRole('button', { name: /columns/i }))
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: 'Theta' }))
    expect(screen.queryByRole('columnheader', { name: 'Theta' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: 'Theta' }))
    expect(screen.getByRole('columnheader', { name: 'Theta' })).toBeInTheDocument()
  })

  it('does not render a toggle when there are no optional columns', () => {
    const requiredOnly = columns.filter(c => !c.optional)
    render(
      <ColumnVisibilityTable rows={rows} columns={requiredOnly} rowKey={r => r.symbol} />,
    )
    expect(screen.queryByRole('button', { name: /columns/i })).not.toBeInTheDocument()
  })

  it('hides cells in hidden columns, not just headers', () => {
    render(<ColumnVisibilityTable rows={rows} columns={columns} rowKey={r => r.symbol} />)
    fireEvent.click(screen.getByRole('button', { name: /columns/i }))
    fireEvent.click(screen.getByRole('menuitemcheckbox', { name: 'Gamma' }))

    // Gamma values 0.014 / 0.011 must no longer be in the document.
    expect(screen.queryByText('0.014')).not.toBeInTheDocument()
    expect(screen.queryByText('0.011')).not.toBeInTheDocument()
  })
})
