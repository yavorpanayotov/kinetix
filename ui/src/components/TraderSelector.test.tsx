import { render, screen, fireEvent } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { TraderSelector } from './TraderSelector'
import type { TraderDto } from '../api/traders'

const TRADERS: TraderDto[] = [
  {
    trader_id: 't-1',
    name: 'Alice Chen',
    deskId: 'equity-growth',
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
  },
  {
    trader_id: 't-2',
    name: 'Brian Park',
    deskId: 'equity-growth',
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
  },
  {
    trader_id: 't-3',
    name: 'Chiara Romano',
    deskId: 'rates-trading',
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
  },
]

describe('TraderSelector', () => {
  it('renders "All traders" as the default empty selection', () => {
    render(
      <TraderSelector
        traders={TRADERS}
        selectedTraderId={null}
        onChange={vi.fn()}
        loading={false}
      />,
    )
    const select = screen.getByTestId('trader-selector-input') as HTMLSelectElement
    expect(select.value).toBe('')
    expect(screen.getByRole('option', { name: 'All traders' })).toBeInTheDocument()
  })

  it('renders every trader as an option', () => {
    render(
      <TraderSelector
        traders={TRADERS}
        selectedTraderId={null}
        onChange={vi.fn()}
        loading={false}
      />,
    )
    expect(screen.getByRole('option', { name: 'Alice Chen' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Brian Park' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Chiara Romano' })).toBeInTheDocument()
  })

  it('emits the selected trader id and null on "All traders"', () => {
    const onChange = vi.fn()
    render(
      <TraderSelector
        traders={TRADERS}
        selectedTraderId={null}
        onChange={onChange}
        loading={false}
      />,
    )
    const select = screen.getByTestId('trader-selector-input')

    fireEvent.change(select, { target: { value: 't-2' } })
    expect(onChange).toHaveBeenLastCalledWith('t-2')

    fireEvent.change(select, { target: { value: '' } })
    expect(onChange).toHaveBeenLastCalledWith(null)
  })

  it('reflects the controlled selectedTraderId prop', () => {
    render(
      <TraderSelector
        traders={TRADERS}
        selectedTraderId="t-3"
        onChange={vi.fn()}
        loading={false}
      />,
    )
    const select = screen.getByTestId('trader-selector-input') as HTMLSelectElement
    expect(select.value).toBe('t-3')
  })

  it('disables the input while loading', () => {
    render(
      <TraderSelector
        traders={[]}
        selectedTraderId={null}
        onChange={vi.fn()}
        loading={true}
      />,
    )
    expect(screen.getByTestId('trader-selector-input')).toBeDisabled()
  })

  it('filters the dropdown when filterByDeskId is supplied', () => {
    render(
      <TraderSelector
        traders={TRADERS}
        selectedTraderId={null}
        onChange={vi.fn()}
        loading={false}
        filterByDeskId="equity-growth"
      />,
    )
    expect(screen.getByRole('option', { name: 'Alice Chen' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Brian Park' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Chiara Romano' })).not.toBeInTheDocument()
  })
})
