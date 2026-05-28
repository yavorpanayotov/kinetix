import { render, screen, fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LiquidityRiskPanel, formatLvar } from './LiquidityRiskPanel'
import type { LiquidityRiskResultDto } from '../types'

const sampleResult: LiquidityRiskResultDto = {
  bookId: 'BOOK-1',
  portfolioLvar: 316227.76,
  dataCompleteness: 0.85,
  portfolioConcentrationStatus: 'OK',
  calculatedAt: '2026-03-24T10:00:00Z',
  positionRisks: [
    {
      instrumentId: 'AAPL',
      assetClass: 'EQUITY',
      marketValue: 17000,
      tier: 'HIGH_LIQUID',
      horizonDays: 1,
      adv: 10000000,
      advMissing: false,
      advStale: false,
      lvarContribution: 316227.76,
      stressedLiquidationValue: 16500,
      concentrationStatus: 'OK',
    },
  ],
}

describe('LiquidityRiskPanel', () => {
  it('renders portfolio LVaR value', () => {
    render(
      <LiquidityRiskPanel result={sampleResult} loading={false} onRefresh={vi.fn()} />,
    )

    expect(screen.getByTestId('portfolio-lvar')).toBeDefined()
    expect(screen.getByTestId('portfolio-lvar').textContent).toContain('316')
  })

  it('renders data completeness as a percentage', () => {
    render(
      <LiquidityRiskPanel result={sampleResult} loading={false} onRefresh={vi.fn()} />,
    )

    expect(screen.getByTestId('data-completeness').textContent).toContain('85')
  })

  it('renders portfolio concentration status', () => {
    render(
      <LiquidityRiskPanel result={sampleResult} loading={false} onRefresh={vi.fn()} />,
    )

    expect(screen.getByTestId('concentration-status').textContent).toContain(
      'OK',
    )
  })

  it('renders a row for each position risk', () => {
    render(
      <LiquidityRiskPanel result={sampleResult} loading={false} onRefresh={vi.fn()} />,
    )

    expect(screen.getByTestId('position-row-AAPL')).toBeDefined()
  })

  it('shows loading spinner when loading', () => {
    render(
      <LiquidityRiskPanel result={null} loading={true} onRefresh={vi.fn()} />,
    )

    expect(screen.getByTestId('liquidity-loading')).toBeDefined()
  })

  it('shows empty state when no result and not loading', () => {
    render(
      <LiquidityRiskPanel result={null} loading={false} onRefresh={vi.fn()} />,
    )

    expect(screen.getByTestId('liquidity-empty')).toBeDefined()
  })

  it('calls onRefresh when refresh button is clicked', () => {
    const onRefresh = vi.fn()
    render(
      <LiquidityRiskPanel result={sampleResult} loading={false} onRefresh={onRefresh} />,
    )

    fireEvent.click(screen.getByTestId('liquidity-refresh'))
    expect(onRefresh).toHaveBeenCalledOnce()
  })

  it('shows BREACHED concentration status in red', () => {
    const breachedResult: LiquidityRiskResultDto = {
      ...sampleResult,
      portfolioConcentrationStatus: 'BREACHED',
    }
    render(
      <LiquidityRiskPanel result={breachedResult} loading={false} onRefresh={vi.fn()} />,
    )

    const status = screen.getByTestId('concentration-status')
    expect(status.getAttribute('data-status')).toBe('BREACHED')
  })

  it('shows ADV missing warning for positions without ADV data', () => {
    const noAdvResult: LiquidityRiskResultDto = {
      ...sampleResult,
      positionRisks: [
        {
          ...sampleResult.positionRisks[0],
          instrumentId: 'UNKNOWN-BOND',
          advMissing: true,
          tier: 'ILLIQUID',
        },
      ],
    }
    render(
      <LiquidityRiskPanel result={noAdvResult} loading={false} onRefresh={vi.fn()} />,
    )

    expect(screen.getByTestId('adv-missing-warning')).toBeDefined()
  })

  describe('staleness banner (trader-review P0 #7)', () => {
    beforeEach(() => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-05-28T10:00:00Z'))
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('surfaces a staleness warning when the Calculated timestamp is older than 1 day', () => {
      const staleResult: LiquidityRiskResultDto = {
        ...sampleResult,
        calculatedAt: '2026-04-07T16:02:50Z', // 50 days old
      }
      render(
        <LiquidityRiskPanel result={staleResult} loading={false} onRefresh={vi.fn()} />,
      )

      const banner = screen.getByTestId('liquidity-staleness-banner')
      expect(banner).toBeDefined()
      // Trader needs the age at a glance.
      expect(banner.textContent).toMatch(/50d/)
    })

    it('does NOT surface a staleness warning when the Calculated timestamp is fresh', () => {
      const freshResult: LiquidityRiskResultDto = {
        ...sampleResult,
        calculatedAt: '2026-05-28T05:00:00Z', // 5 hours old
      }
      render(
        <LiquidityRiskPanel result={freshResult} loading={false} onRefresh={vi.fn()} />,
      )

      expect(screen.queryByTestId('liquidity-staleness-banner')).toBeNull()
    })
  })
})

// Trader-review P0 #7 — the dashboard rendered LVaR contributions in single
// dollars (`$0.7`, `$1.4`) while every other column used $K / $M. A trader
// scanning the column missed the order-of-magnitude difference. Pin the
// formatter so LVaR always uses the same compact notation as the rest of
// the dashboard (matches `Stressed Liq $13.3K` etc.).
//
// Choice (documented in the commit body): keep compact notation for
// consistency with `Stressed Liq`, but force at least a `$X.XX` two-decimal
// representation for sub-dollar values so the magnitude is unambiguous.
describe('formatLvar', () => {
  it('renders $K for thousand-dollar magnitudes (matches Stressed Liq)', () => {
    expect(formatLvar(13_300)).toBe('$13.3K')
  })

  it('renders $M for million-dollar magnitudes (matches Portfolio LVaR for an institutional book)', () => {
    expect(formatLvar(1_400_000)).toBe('$1.4M')
  })

  it('renders sub-dollar magnitudes with two decimals (not a misleading "$0.7")', () => {
    expect(formatLvar(0.7)).toBe('$0.70')
  })

  it('renders single-digit dollar magnitudes with two decimals', () => {
    expect(formatLvar(1.4)).toBe('$1.40')
  })

  it('renders exact zero as "$0.00"', () => {
    expect(formatLvar(0)).toBe('$0.00')
  })

  it('renders sub-thousand-dollar magnitudes plainly (no awkward compact $0.7K)', () => {
    expect(formatLvar(700)).toBe('$700.00')
  })
})
