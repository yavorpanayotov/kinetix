import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { TradeHistoryDto } from '../types'
import { CounterpartyExposureTile } from './CounterpartyExposureTile'

function trade(overrides: Partial<TradeHistoryDto>): TradeHistoryDto {
  return {
    tradeId: overrides.tradeId ?? `t-${Math.random()}`,
    bookId: 'book-1',
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '100',
    price: { amount: '150.00', currency: 'USD' },
    tradedAt: '2026-05-26T12:00:00Z',
    ...overrides,
  }
}

describe('CounterpartyExposureTile', () => {
  it('renders an empty state when no trades have a counterparty', () => {
    render(
      <CounterpartyExposureTile
        trades={[
          trade({ tradeId: 't1', counterpartyId: null }),
          trade({ tradeId: 't2' }), // no counterpartyId at all
        ]}
      />,
    )

    expect(screen.getByTestId('counterparty-exposure-empty')).toBeInTheDocument()
  })

  it('aggregates BUY trades into a positive net notional per counterparty', () => {
    render(
      <CounterpartyExposureTile
        trades={[
          trade({ tradeId: 't1', counterpartyId: 'CP-GS', quantity: '100', price: { amount: '150', currency: 'USD' } }),
          trade({ tradeId: 't2', counterpartyId: 'CP-GS', quantity: '50', price: { amount: '200', currency: 'USD' } }),
        ]}
      />,
    )

    const row = screen.getByTestId('counterparty-exposure-row-CP-GS')
    expect(row).toBeInTheDocument()
    // 100×150 + 50×200 = 25_000
    expect(
      within(row).getByTestId('counterparty-exposure-notional-CP-GS'),
    ).toHaveTextContent('25,000')
    expect(
      within(row).getByTestId('counterparty-exposure-trade-count-CP-GS'),
    ).toHaveTextContent('2 tr')
  })

  it('flips the sign on SELL trades — a SELL only book shows a negative net notional', () => {
    render(
      <CounterpartyExposureTile
        trades={[
          trade({ tradeId: 't1', counterpartyId: 'CP-JPM', side: 'SELL', quantity: '100', price: { amount: '100', currency: 'USD' } }),
        ]}
      />,
    )

    const row = screen.getByTestId('counterparty-exposure-row-CP-JPM')
    // formatCurrency yields "-$10,000.00" via Intl.NumberFormat — the leading
    // minus is what flags this as a short net exposure.
    expect(within(row).getByTestId('counterparty-exposure-notional-CP-JPM').textContent).toMatch(/^-/)
    expect(within(row).getByTestId('counterparty-exposure-notional-CP-JPM').textContent).toContain('10,000')
    // Bar uses the rose tint instead of indigo when net notional is negative.
    expect(within(row).getByTestId('counterparty-exposure-bar-CP-JPM').className).toMatch(/rose/)
  })

  it('sorts top-N counterparties by absolute net notional, descending', () => {
    render(
      <CounterpartyExposureTile
        trades={[
          // CP-A: net notional 1,000
          trade({ tradeId: 'a1', counterpartyId: 'CP-A', quantity: '10', price: { amount: '100', currency: 'USD' } }),
          // CP-B: net notional 5,000 (largest)
          trade({ tradeId: 'b1', counterpartyId: 'CP-B', quantity: '50', price: { amount: '100', currency: 'USD' } }),
          // CP-C: net notional 2,000
          trade({ tradeId: 'c1', counterpartyId: 'CP-C', quantity: '20', price: { amount: '100', currency: 'USD' } }),
        ]}
      />,
    )

    const list = screen.getByRole('list', { name: /counterparty exposure top list/i })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent('CP-B')
    expect(items[1]).toHaveTextContent('CP-C')
    expect(items[2]).toHaveTextContent('CP-A')
  })

  it('caps the visible rows to the configured topN', () => {
    const trades: TradeHistoryDto[] = []
    for (let i = 0; i < 15; i += 1) {
      trades.push(
        trade({
          tradeId: `t-${i}`,
          counterpartyId: `CP-${i}`,
          quantity: String(i + 1),
          price: { amount: '100', currency: 'USD' },
        }),
      )
    }

    render(<CounterpartyExposureTile trades={trades} topN={5} />)

    const list = screen.getByRole('list', { name: /counterparty exposure top list/i })
    expect(within(list).getAllByRole('listitem')).toHaveLength(5)
  })

  it('renders the demo set of 6 G-SIB counterparties when seeded by the simulator (kx-i72)', () => {
    const cps = ['CP-GS', 'CP-JPM', 'CP-BARC', 'CP-DB', 'CP-UBS', 'CP-CITI']
    const trades = cps.flatMap((cp, idx) => [
      trade({ tradeId: `${cp}-buy`, counterpartyId: cp, side: 'BUY', quantity: String(10 * (idx + 1)), price: { amount: '100', currency: 'USD' } }),
      trade({ tradeId: `${cp}-sell`, counterpartyId: cp, side: 'SELL', quantity: '5', price: { amount: '100', currency: 'USD' } }),
    ])

    render(<CounterpartyExposureTile trades={trades} />)

    for (const cp of cps) {
      expect(screen.getByTestId(`counterparty-exposure-row-${cp}`)).toBeInTheDocument()
    }
  })
})
