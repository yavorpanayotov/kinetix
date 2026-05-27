import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ClearFiltersTable, type FilterDef } from './ClearFiltersTable'

interface Row {
  id: string
  symbol: string
  desk: string
  notional: number
}

const rows: Row[] = [
  { id: '1', symbol: 'AAPL', desk: 'EQ', notional: 100 },
  { id: '2', symbol: 'MSFT', desk: 'EQ', notional: 200 },
  { id: '3', symbol: 'XAU', desk: 'CMD', notional: 300 },
]

function basicFilters(): Array<FilterDef<Row>> {
  return [
    {
      key: 'symbol',
      label: 'Symbol',
      defaultValue: '',
      match: (row, value) =>
        value === '' || row.symbol.toLowerCase().includes(String(value).toLowerCase()),
    },
    {
      key: 'desk',
      label: 'Desk',
      defaultValue: '',
      match: (row, value) => value === '' || row.desk === value,
    },
  ]
}

describe('ClearFiltersTable', () => {
  it('renders every row when no filters are active', () => {
    render(<ClearFiltersTable rows={rows} rowKey={r => r.id} filters={basicFilters()} columns={[
      { key: 'symbol', label: 'Symbol', render: r => r.symbol },
      { key: 'desk', label: 'Desk', render: r => r.desk },
    ]} />)

    expect(screen.getByRole('row', { name: /AAPL/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /MSFT/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /XAU/ })).toBeInTheDocument()
  })

  it('hides the Clear all filters button while every filter sits at its default', () => {
    render(<ClearFiltersTable rows={rows} rowKey={r => r.id} filters={basicFilters()} columns={[
      { key: 'symbol', label: 'Symbol', render: r => r.symbol },
    ]} />)

    expect(screen.queryByRole('button', { name: /clear all filters/i })).toBeNull()
  })

  it('shows the Clear all filters button once any filter departs from its default', () => {
    render(<ClearFiltersTable rows={rows} rowKey={r => r.id} filters={basicFilters()} columns={[
      { key: 'symbol', label: 'Symbol', render: r => r.symbol },
    ]} />)

    const symbolInput = screen.getByLabelText('Symbol')
    fireEvent.change(symbolInput, { target: { value: 'AAPL' } })

    expect(screen.getByRole('button', { name: /clear all filters/i })).toBeEnabled()
  })

  it('narrows the rendered rows when a filter is applied', () => {
    render(<ClearFiltersTable rows={rows} rowKey={r => r.id} filters={basicFilters()} columns={[
      { key: 'symbol', label: 'Symbol', render: r => r.symbol },
      { key: 'desk', label: 'Desk', render: r => r.desk },
    ]} />)

    fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'msft' } })

    expect(screen.queryByRole('row', { name: /AAPL/ })).toBeNull()
    expect(screen.getByRole('row', { name: /MSFT/ })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: /XAU/ })).toBeNull()
  })

  it('resets every active filter when Clear all filters is clicked', () => {
    render(<ClearFiltersTable rows={rows} rowKey={r => r.id} filters={basicFilters()} columns={[
      { key: 'symbol', label: 'Symbol', render: r => r.symbol },
      { key: 'desk', label: 'Desk', render: r => r.desk },
    ]} />)

    fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'msft' } })
    fireEvent.change(screen.getByLabelText('Desk'), { target: { value: 'EQ' } })
    expect(screen.queryByRole('row', { name: /XAU/ })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /clear all filters/i }))

    // Inputs return to their default values.
    expect((screen.getByLabelText('Symbol') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('Desk') as HTMLInputElement).value).toBe('')

    // Every row is visible again.
    expect(screen.getByRole('row', { name: /AAPL/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /MSFT/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /XAU/ })).toBeInTheDocument()

    // And the button hides itself again since nothing is active.
    expect(screen.queryByRole('button', { name: /clear all filters/i })).toBeNull()
  })

  it('reports the number of active filters in the button label', () => {
    render(<ClearFiltersTable rows={rows} rowKey={r => r.id} filters={basicFilters()} columns={[
      { key: 'symbol', label: 'Symbol', render: r => r.symbol },
      { key: 'desk', label: 'Desk', render: r => r.desk },
    ]} />)

    fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'AAPL' } })
    expect(screen.getByRole('button', { name: /clear all filters \(1\)/i })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Desk'), { target: { value: 'EQ' } })
    expect(screen.getByRole('button', { name: /clear all filters \(2\)/i })).toBeInTheDocument()
  })
})
