// Tests for the URL-persistent table (kx-449t).

import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

import {
  UrlPersistentTable,
  parseTableStateFromSearch,
  serialiseTableStateToSearch,
  type ColumnDef,
  type FilterDef,
} from './UrlPersistentTable'

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

function basicColumns(): Array<ColumnDef<Row>> {
  return [
    { key: 'symbol', label: 'Symbol', render: r => r.symbol, sortValue: r => r.symbol },
    { key: 'desk', label: 'Desk', render: r => r.desk, sortValue: r => r.desk },
    { key: 'notional', label: 'Notional', render: r => String(r.notional), sortValue: r => r.notional },
  ]
}

function resetUrl(): void {
  // The component reads window.location; jsdom honours pushState.
  window.history.replaceState({}, '', '/')
}

beforeEach(() => {
  resetUrl()
})

describe('parseTableStateFromSearch', () => {
  it('returns the default filter values and an unsorted state when the search is empty', () => {
    const state = parseTableStateFromSearch('', ['symbol', 'desk'])
    expect(state.filters).toEqual({ symbol: '', desk: '' })
    expect(state.sort).toBeNull()
  })

  it('extracts filter values using the f.<key>= prefix', () => {
    const state = parseTableStateFromSearch('?f.symbol=AAPL&f.desk=EQ', ['symbol', 'desk'])
    expect(state.filters).toEqual({ symbol: 'AAPL', desk: 'EQ' })
  })

  it('extracts the sort column and direction', () => {
    const state = parseTableStateFromSearch('?sort=notional&dir=desc', ['symbol'])
    expect(state.sort).toEqual({ key: 'notional', direction: 'desc' })
  })

  it('ignores unknown sort directions and falls back to no sort', () => {
    const state = parseTableStateFromSearch('?sort=notional&dir=sideways', ['symbol'])
    expect(state.sort).toBeNull()
  })

  it('ignores filter keys that the caller did not declare', () => {
    const state = parseTableStateFromSearch(
      '?f.symbol=AAPL&f.bogus=hi',
      ['symbol'],
    )
    expect(state.filters).toEqual({ symbol: 'AAPL' })
  })
})

describe('serialiseTableStateToSearch', () => {
  it('emits an empty search when all filters sit at default and nothing is sorted', () => {
    expect(
      serialiseTableStateToSearch({
        filters: { symbol: '', desk: '' },
        sort: null,
        defaults: { symbol: '', desk: '' },
      }),
    ).toBe('')
  })

  it('only emits filters that differ from their defaults', () => {
    expect(
      serialiseTableStateToSearch({
        filters: { symbol: 'AAPL', desk: '' },
        sort: null,
        defaults: { symbol: '', desk: '' },
      }),
    ).toBe('f.symbol=AAPL')
  })

  it('emits sort key and direction when present', () => {
    expect(
      serialiseTableStateToSearch({
        filters: { symbol: '' },
        sort: { key: 'notional', direction: 'desc' },
        defaults: { symbol: '' },
      }),
    ).toBe('sort=notional&dir=desc')
  })
})

describe('UrlPersistentTable', () => {
  it('restores filter state from the URL on first render', () => {
    window.history.replaceState({}, '', '/?f.symbol=msft')

    render(
      <UrlPersistentTable
        rows={rows}
        rowKey={r => r.id}
        filters={basicFilters()}
        columns={basicColumns()}
      />,
    )

    expect((screen.getByLabelText('Symbol') as HTMLInputElement).value).toBe('msft')
    expect(screen.queryByRole('row', { name: /AAPL/ })).toBeNull()
    expect(screen.getByRole('row', { name: /MSFT/ })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: /XAU/ })).toBeNull()
  })

  it('writes filter changes back to the URL', () => {
    render(
      <UrlPersistentTable
        rows={rows}
        rowKey={r => r.id}
        filters={basicFilters()}
        columns={basicColumns()}
      />,
    )

    fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'AAPL' } })

    expect(window.location.search).toContain('f.symbol=AAPL')
  })

  it('restores sort state from the URL on first render', () => {
    window.history.replaceState({}, '', '/?sort=notional&dir=desc')

    render(
      <UrlPersistentTable
        rows={rows}
        rowKey={r => r.id}
        filters={basicFilters()}
        columns={basicColumns()}
      />,
    )

    const rendered = screen.getAllByRole('row').slice(1).map(r => r.textContent ?? '')
    expect(rendered[0]).toContain('XAU')
    expect(rendered[1]).toContain('MSFT')
    expect(rendered[2]).toContain('AAPL')
  })

  it('updates the URL when the user toggles a sort column', () => {
    render(
      <UrlPersistentTable
        rows={rows}
        rowKey={r => r.id}
        filters={basicFilters()}
        columns={basicColumns()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /sort by notional/i }))

    expect(window.location.search).toContain('sort=notional')
    expect(window.location.search).toContain('dir=asc')
  })

  it('clears defaults from the URL — defaults must not leak into the link', () => {
    render(
      <UrlPersistentTable
        rows={rows}
        rowKey={r => r.id}
        filters={basicFilters()}
        columns={basicColumns()}
      />,
    )

    fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: 'AAPL' } })
    fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: '' } })

    expect(window.location.search).toBe('')
  })
})
