import { describe, it, expect } from 'vitest'
import { aggregatePositionRisk } from './aggregatePositionRisk'
import type { PositionRiskDto } from '../types'

function row(over: Partial<PositionRiskDto>): PositionRiskDto {
  return {
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    marketValue: '0',
    delta: '0',
    gamma: '0',
    vega: '0',
    theta: '0',
    rho: '0',
    dv01: null,
    varContribution: '0',
    esContribution: '0',
    percentageOfTotal: '0',
    ...over,
  }
}

describe('aggregatePositionRisk', () => {
  it('sums greeks and market value across books for the same instrument', () => {
    const result = aggregatePositionRisk([
      [row({ instrumentId: 'AAPL', marketValue: '100', delta: '10', gamma: '1', vega: '2', theta: '-1', rho: '0.5', varContribution: '30' })],
      [row({ instrumentId: 'AAPL', marketValue: '50', delta: '5', gamma: '0.5', vega: '1', theta: '-0.5', rho: '0.25', varContribution: '10' })],
    ])

    expect(result).toHaveLength(1)
    const aapl = result[0]
    expect(Number(aapl.marketValue)).toBe(150)
    expect(Number(aapl.delta)).toBe(15)
    expect(Number(aapl.gamma)).toBe(1.5)
    expect(Number(aapl.vega)).toBe(3)
    expect(Number(aapl.theta)).toBe(-1.5)
    expect(Number(aapl.rho)).toBe(0.75)
    expect(Number(aapl.varContribution)).toBe(40)
  })

  it('keeps distinct instruments separate and recomputes percentageOfTotal', () => {
    const result = aggregatePositionRisk([
      [row({ instrumentId: 'AAPL', varContribution: '75', delta: '10' })],
      [row({ instrumentId: 'MSFT', varContribution: '25', delta: '4' })],
    ])

    expect(result).toHaveLength(2)
    const pct = Object.fromEntries(result.map((r) => [r.instrumentId, Number(r.percentageOfTotal)]))
    expect(pct.AAPL).toBeCloseTo(75)
    expect(pct.MSFT).toBeCloseTo(25)
  })

  it('does not mutate the input rows', () => {
    const original = row({ instrumentId: 'AAPL', delta: '10' })
    aggregatePositionRisk([[original], [row({ instrumentId: 'AAPL', delta: '5' })]])
    expect(original.delta).toBe('10')
  })

  it('returns an empty array when there are no books', () => {
    expect(aggregatePositionRisk([])).toEqual([])
  })
})
