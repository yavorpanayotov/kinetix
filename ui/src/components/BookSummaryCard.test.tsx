import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { BookAggregationDto } from '../types'
import { BookSummaryCard } from './BookSummaryCard'

const summary: BookAggregationDto = {
  bookId: 'book-1',
  baseCurrency: 'USD',
  totalNav: { amount: '38050.00', currency: 'USD' },
  totalUnrealizedPnl: { amount: '1500.00', currency: 'USD' },
  currencyBreakdown: [
    {
      currency: 'USD',
      localValue: { amount: '21500.00', currency: 'USD' },
      baseValue: { amount: '21500.00', currency: 'USD' },
      fxRate: '1',
    },
    {
      currency: 'EUR',
      localValue: { amount: '18000.00', currency: 'EUR' },
      baseValue: { amount: '16550.00', currency: 'USD' },
      fxRate: '1.10',
    },
  ],
}

describe('BookSummaryCard', () => {
  it('renders total NAV', () => {
    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('total-nav')).toBeInTheDocument()
    expect(screen.getByTestId('total-nav')).toHaveTextContent('38,050.00')
  })

  it('renders total unrealized P&L', () => {
    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('total-unrealized-pnl')).toBeInTheDocument()
    expect(screen.getByTestId('total-unrealized-pnl')).toHaveTextContent('1,500.00')
  })

  it('renders currency breakdown rows', () => {
    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('currency-row-USD')).toBeInTheDocument()
    expect(screen.getByTestId('currency-row-EUR')).toBeInTheDocument()
  })

  it('shows base currency selector', () => {
    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
      />,
    )

    const selector = screen.getByTestId('base-currency-selector')
    expect(selector).toBeInTheDocument()
    expect(selector).toHaveValue('USD')
  })

  it('calls onBaseCurrencyChange when currency is changed', async () => {
    const user = userEvent.setup()
    const onBaseCurrencyChange = vi.fn()

    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={onBaseCurrencyChange}
      />,
    )

    await user.selectOptions(screen.getByTestId('base-currency-selector'), 'EUR')
    expect(onBaseCurrencyChange).toHaveBeenCalledWith('EUR')
  })

  it('applies green colour to positive P&L', () => {
    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('total-unrealized-pnl').className).toContain('text-green-600')
  })

  it('prefixes positive P&L with a + sign', () => {
    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('total-unrealized-pnl')).toHaveTextContent('+$1,500.00')
  })

  it('applies red colour to negative P&L', () => {
    const negativeSummary: BookAggregationDto = {
      ...summary,
      totalUnrealizedPnl: { amount: '-500.00', currency: 'USD' },
    }

    render(
      <BookSummaryCard
        summary={negativeSummary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('total-unrealized-pnl').className).toContain('text-red-600')
  })

  it('renders the title via the canonical SectionHeading primitive', () => {
    render(
      <BookSummaryCard
        summary={summary}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
        title="Book Summary"
      />,
    )

    const heading = screen.getByRole('heading', { name: /book summary/i })
    expect(heading.className).toContain('text-base')
    expect(heading.className).toContain('font-semibold')
  })

  it('renders loading state when summary is null', () => {
    render(
      <BookSummaryCard
        summary={null}
        baseCurrency="USD"
        onBaseCurrencyChange={vi.fn()}
        loading={true}
      />,
    )

    expect(screen.getByTestId('book-summary-loading')).toBeInTheDocument()
  })
})
