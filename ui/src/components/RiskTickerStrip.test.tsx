import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type {
  BookAggregationDto,
  GreeksResultDto,
  IntradayPnlSnapshotDto,
  VaRResultDto,
} from '../types'
import { RiskTickerStrip } from './RiskTickerStrip'

const sampleBookSummary = (overrides: Partial<BookAggregationDto> = {}): BookAggregationDto => ({
  bookId: 'book-1',
  baseCurrency: 'USD',
  totalNav: { amount: '1000000.00', currency: 'USD' },
  totalUnrealizedPnl: { amount: '50000.00', currency: 'USD' },
  currencyBreakdown: [],
  ...overrides,
})

const sampleIntraday = (overrides: Partial<IntradayPnlSnapshotDto> = {}): IntradayPnlSnapshotDto => ({
  snapshotAt: '2026-03-24T09:30:00Z',
  baseCurrency: 'USD',
  trigger: 'price_update',
  totalPnl: '1500.00',
  realisedPnl: '500.00',
  unrealisedPnl: '1000.00',
  deltaPnl: '1200.00',
  gammaPnl: '80.00',
  vegaPnl: '40.00',
  thetaPnl: '-15.00',
  rhoPnl: '7.00',
  unexplainedPnl: '188.00',
  highWaterMark: '1800.00',
  ...overrides,
})

const sampleVaR = (overrides: Partial<VaRResultDto> = {}): VaRResultDto => ({
  bookId: 'book-1',
  calculationType: 'PARAMETRIC',
  confidenceLevel: 'CL_95',
  varValue: '75000.00',
  expectedShortfall: '90000.00',
  componentBreakdown: [],
  calculatedAt: '2026-03-24T09:31:00Z',
  ...overrides,
})

const sampleGreeks = (overrides: Partial<GreeksResultDto> = {}): GreeksResultDto => ({
  bookId: 'book-1',
  assetClassGreeks: [
    { assetClass: 'EQUITY', delta: '1234.56', gamma: '12.5', vega: '320.00' },
    { assetClass: 'FX', delta: '100.00', gamma: '0', vega: '0' },
  ],
  theta: '-45.00',
  rho: '15.00',
  calculatedAt: '2026-03-24T09:31:00Z',
  ...overrides,
})

describe('RiskTickerStrip', () => {
  describe('when no book is selected', () => {
    it('renders a hint instead of metric cells', () => {
      render(
        <RiskTickerStrip
          bookId={null}
          bookSummary={null}
          intradaySnapshot={null}
          varResult={null}
          greeksResult={null}
          varLimit={null}
          streamConnected={false}
        />,
      )

      expect(screen.getByTestId('risk-ticker-strip')).toBeInTheDocument()
      expect(screen.getByTestId('ticker-no-book-hint')).toBeInTheDocument()
      expect(screen.queryByTestId('ticker-nav')).not.toBeInTheDocument()
    })
  })

  describe('with full data', () => {
    const fullProps = {
      bookId: 'book-1',
      bookSummary: sampleBookSummary(),
      intradaySnapshot: sampleIntraday(),
      varResult: sampleVaR(),
      greeksResult: sampleGreeks(),
      varLimit: 100000,
      streamConnected: true,
    }

    it('renders the NAV cell using formatMoney', () => {
      render(<RiskTickerStrip {...fullProps} />)
      expect(screen.getByTestId('ticker-nav')).toHaveTextContent('$1,000,000.00')
    })

    it('renders Unrealised P&L using formatSignedMoney (positive prefixed with +)', () => {
      render(<RiskTickerStrip {...fullProps} />)
      expect(screen.getByTestId('ticker-unrealised-pnl')).toHaveTextContent('+$50,000.00')
    })

    it('renders Unrealised P&L percentage of NAV', () => {
      render(<RiskTickerStrip {...fullProps} />)
      // 50000 / 1000000 = 5.00%
      expect(screen.getByTestId('ticker-unrealised-pnl-pct')).toHaveTextContent('5.00%')
    })

    it('renders Intraday P&L using formatSignedMoney', () => {
      render(<RiskTickerStrip {...fullProps} />)
      expect(screen.getByTestId('ticker-intraday-pnl')).toHaveTextContent('+$1,500.00')
    })

    it('renders VaR value', () => {
      render(<RiskTickerStrip {...fullProps} />)
      expect(screen.getByTestId('ticker-var')).toHaveTextContent('$75,000.00')
    })

    it('renders VaR utilisation as a percentage of limit', () => {
      render(<RiskTickerStrip {...fullProps} />)
      // 75000 / 100000 = 75%
      expect(screen.getByTestId('ticker-var-utilisation')).toHaveTextContent('75.0% of limit')
    })

    it('renders Net Delta (sum of asset-class deltas)', () => {
      render(<RiskTickerStrip {...fullProps} />)
      // 1234.56 + 100.00 = 1334.56
      expect(screen.getByTestId('ticker-net-delta')).toHaveTextContent('1,334.56')
    })

    it('renders Net Vega (sum of asset-class vegas)', () => {
      render(<RiskTickerStrip {...fullProps} />)
      // 320 + 0 = 320
      expect(screen.getByTestId('ticker-net-vega')).toHaveTextContent('320.00')
    })

    it('renders Last calc time as HH:MM:SS', () => {
      render(<RiskTickerStrip {...fullProps} />)
      expect(screen.getByTestId('ticker-last-calc')).toBeInTheDocument()
    })
  })

  describe('VaR limit breach colour-coding', () => {
    it('does NOT colour VaR cell red when utilisation is at or below 80% of limit', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={sampleBookSummary()}
          intradaySnapshot={null}
          varResult={sampleVaR({ varValue: '80000.00' })}
          greeksResult={null}
          varLimit={100000}
          streamConnected={true}
        />,
      )

      expect(screen.getByTestId('ticker-var').className).not.toMatch(/text-red-600/)
      expect(screen.queryByTestId('ticker-var-breach-icon')).not.toBeInTheDocument()
    })

    it('colours VaR cell red and shows breach icon when utilisation exceeds 80% of limit', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={sampleBookSummary()}
          intradaySnapshot={null}
          varResult={sampleVaR({ varValue: '85000.00' })}
          greeksResult={null}
          varLimit={100000}
          streamConnected={true}
        />,
      )

      expect(screen.getByTestId('ticker-var').className).toMatch(/text-red-600/)
      expect(screen.getByTestId('ticker-var-utilisation').className).toMatch(/text-red-600/)
      expect(screen.getByTestId('ticker-var-breach-icon')).toBeInTheDocument()
    })
  })

  describe('P&L colour-coding', () => {
    it('shows green Unrealised P&L on a positive value', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={sampleBookSummary({
            totalUnrealizedPnl: { amount: '50000.00', currency: 'USD' },
          })}
          intradaySnapshot={null}
          varResult={null}
          greeksResult={null}
          varLimit={null}
          streamConnected={true}
        />,
      )

      expect(screen.getByTestId('ticker-unrealised-pnl').className).toMatch(/text-green-600/)
    })

    it('shows red Intraday P&L on a negative value', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={null}
          intradaySnapshot={sampleIntraday({ totalPnl: '-1500.00' })}
          varResult={null}
          greeksResult={null}
          varLimit={null}
          streamConnected={true}
        />,
      )

      expect(screen.getByTestId('ticker-intraday-pnl').className).toMatch(/text-red-600/)
    })
  })

  describe('missing data handling', () => {
    it('renders em-dashes when data is unavailable', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={null}
          intradaySnapshot={null}
          varResult={null}
          greeksResult={null}
          varLimit={null}
          streamConnected={false}
        />,
      )

      expect(screen.getByTestId('ticker-nav')).toHaveTextContent('—')
      expect(screen.getByTestId('ticker-unrealised-pnl')).toHaveTextContent('—')
      expect(screen.getByTestId('ticker-intraday-pnl')).toHaveTextContent('—')
      expect(screen.getByTestId('ticker-var')).toHaveTextContent('—')
      expect(screen.getByTestId('ticker-net-delta')).toHaveTextContent('—')
      expect(screen.getByTestId('ticker-net-vega')).toHaveTextContent('—')
      expect(screen.getByTestId('ticker-last-calc')).toHaveTextContent('—')
    })

    it('omits VaR utilisation cell when no limit configured', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={null}
          intradaySnapshot={null}
          varResult={sampleVaR()}
          greeksResult={null}
          varLimit={null}
          streamConnected={true}
        />,
      )

      expect(screen.queryByTestId('ticker-var-utilisation')).not.toBeInTheDocument()
    })
  })

  describe('connection indicator', () => {
    it('shows a green dot when the stream is connected', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={null}
          intradaySnapshot={null}
          varResult={null}
          greeksResult={null}
          varLimit={null}
          streamConnected={true}
        />,
      )

      expect(screen.getByTestId('ticker-connection-status').className).toContain('bg-green-500')
    })

    it('shows a red dot when the stream is disconnected', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={null}
          intradaySnapshot={null}
          varResult={null}
          greeksResult={null}
          varLimit={null}
          streamConnected={false}
        />,
      )

      expect(screen.getByTestId('ticker-connection-status').className).toContain('bg-red-500')
    })
  })

  describe('Hedge CTA (plan §8.2)', () => {
    it('shows a "Need a hedge?" CTA when VaR utilisation exceeds 80% and onOpenHedgePanel is supplied', () => {
      const onOpenHedgePanel = vi.fn()
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={sampleBookSummary()}
          intradaySnapshot={null}
          varResult={sampleVaR({ varValue: '85000.00' })}
          greeksResult={null}
          varLimit={100000}
          streamConnected={true}
          onOpenHedgePanel={onOpenHedgePanel}
        />,
      )

      const cta = screen.getByTestId('ticker-hedge-cta')
      expect(cta).toBeInTheDocument()
      expect(cta).toHaveTextContent(/need a hedge/i)
    })

    it('invokes onOpenHedgePanel when the CTA is clicked', () => {
      const onOpenHedgePanel = vi.fn()
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={sampleBookSummary()}
          intradaySnapshot={null}
          varResult={sampleVaR({ varValue: '85000.00' })}
          greeksResult={null}
          varLimit={100000}
          streamConnected={true}
          onOpenHedgePanel={onOpenHedgePanel}
        />,
      )

      fireEvent.click(screen.getByTestId('ticker-hedge-cta'))
      expect(onOpenHedgePanel).toHaveBeenCalledTimes(1)
    })

    it('does not render the CTA when VaR utilisation is at or below 80%', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={sampleBookSummary()}
          intradaySnapshot={null}
          varResult={sampleVaR({ varValue: '70000.00' })}
          greeksResult={null}
          varLimit={100000}
          streamConnected={true}
          onOpenHedgePanel={vi.fn()}
        />,
      )

      expect(screen.queryByTestId('ticker-hedge-cta')).not.toBeInTheDocument()
    })

    it('does not render the CTA when no callback is supplied, even on breach', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={sampleBookSummary()}
          intradaySnapshot={null}
          varResult={sampleVaR({ varValue: '85000.00' })}
          greeksResult={null}
          varLimit={100000}
          streamConnected={true}
        />,
      )

      expect(screen.queryByTestId('ticker-hedge-cta')).not.toBeInTheDocument()
    })
  })

  describe('FX rate warning', () => {
    it('surfaces missing FX rates from the intraday snapshot', () => {
      render(
        <RiskTickerStrip
          bookId="book-1"
          bookSummary={null}
          intradaySnapshot={sampleIntraday({ missingFxRates: ['USD/JPY', 'EUR/GBP'] })}
          varResult={null}
          greeksResult={null}
          varLimit={null}
          streamConnected={true}
        />,
      )

      const warning = screen.getByTestId('ticker-missing-fx-rates')
      expect(warning).toHaveTextContent('USD/JPY')
      expect(warning).toHaveTextContent('EUR/GBP')
    })
  })
})
