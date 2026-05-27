import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { PinnedColumnsTable, type PinnedColumnDef } from './PinnedColumnsTable'

interface Row {
  id: string
  desk: string
  trader: string
  pnl: number
  delta: number
  vega: number
}

const rows: Row[] = [
  { id: 'r1', desk: 'Equity', trader: 'Alice', pnl: 1_200, delta: 0.42, vega: 12 },
  { id: 'r2', desk: 'FX', trader: 'Bob', pnl: -350, delta: -0.10, vega: 5 },
]

const columns: Array<PinnedColumnDef<Row>> = [
  { key: 'desk', label: 'Desk', render: r => r.desk },
  { key: 'trader', label: 'Trader', render: r => r.trader },
  { key: 'pnl', label: 'PnL', render: r => r.pnl.toString() },
  { key: 'delta', label: 'Delta', render: r => r.delta.toString() },
  { key: 'vega', label: 'Vega', render: r => r.vega.toString() },
]

describe('PinnedColumnsTable', () => {
  it('renders every column when there are five columns and three are pinned', () => {
    render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={3} />,
    )
    expect(screen.getByRole('columnheader', { name: 'Desk' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Trader' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'PnL' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Delta' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Vega' })).toBeInTheDocument()
  })

  it('marks the first three columns as pinned via data-pinned="true"', () => {
    render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={3} />,
    )
    expect(screen.getByRole('columnheader', { name: 'Desk' })).toHaveAttribute(
      'data-pinned',
      'true',
    )
    expect(screen.getByRole('columnheader', { name: 'Trader' })).toHaveAttribute(
      'data-pinned',
      'true',
    )
    expect(screen.getByRole('columnheader', { name: 'PnL' })).toHaveAttribute(
      'data-pinned',
      'true',
    )
  })

  it('leaves columns beyond the pinned count as data-pinned="false"', () => {
    render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={3} />,
    )
    expect(screen.getByRole('columnheader', { name: 'Delta' })).toHaveAttribute(
      'data-pinned',
      'false',
    )
    expect(screen.getByRole('columnheader', { name: 'Vega' })).toHaveAttribute(
      'data-pinned',
      'false',
    )
  })

  it('applies sticky positioning utilities to pinned header cells', () => {
    render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={3} />,
    )
    const pinned = screen.getByRole('columnheader', { name: 'Desk' })
    expect(pinned.className).toContain('sticky')
    expect(pinned.className).toContain('left-0')
  })

  it('applies sticky positioning to pinned body cells too', () => {
    const { container } = render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={3} />,
    )
    const firstRow = container.querySelectorAll('tbody tr')[0]
    const firstCell = within(firstRow as HTMLElement).getByText('Equity')
    expect(firstCell.className).toContain('sticky')
  })

  it('renders the row contents in the order columns were supplied', () => {
    const { container } = render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={3} />,
    )
    const firstRow = container.querySelectorAll('tbody tr')[0]
    const cells = firstRow!.querySelectorAll('td')
    expect(Array.from(cells).map(c => c.textContent)).toEqual([
      'Equity',
      'Alice',
      '1200',
      '0.42',
      '12',
    ])
  })

  it('pins zero columns when pinnedCount is 0', () => {
    render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={0} />,
    )
    expect(screen.getByRole('columnheader', { name: 'Desk' })).toHaveAttribute(
      'data-pinned',
      'false',
    )
  })

  it('caps pinnedCount at the available column count', () => {
    render(
      <PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} pinnedCount={99} />,
    )
    columns.forEach(c => {
      expect(screen.getByRole('columnheader', { name: c.label })).toHaveAttribute(
        'data-pinned',
        'true',
      )
    })
  })

  it('defaults pinnedCount to three when omitted', () => {
    render(<PinnedColumnsTable rows={rows} columns={columns} rowKey={r => r.id} />)
    expect(screen.getByRole('columnheader', { name: 'Desk' })).toHaveAttribute(
      'data-pinned',
      'true',
    )
    expect(screen.getByRole('columnheader', { name: 'PnL' })).toHaveAttribute(
      'data-pinned',
      'true',
    )
    expect(screen.getByRole('columnheader', { name: 'Delta' })).toHaveAttribute(
      'data-pinned',
      'false',
    )
  })
})
