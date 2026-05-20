import { describe, expect, it } from 'vitest'
import {
  buildScenarioExplainContext,
  topStressedPositions,
} from './buildScenarioExplainContext'
import { makeStressResult, makePositionImpact } from '../test-utils/stressMocks'

describe('buildScenarioExplainContext', () => {
  it('stamps page = scenarios and carries scenario name + stressed P&L', () => {
    const result = makeStressResult({
      scenarioName: 'GFC_2008',
      pnlImpact: '-500000.00',
      stressedVar: '300000.00',
    })

    const ctx = buildScenarioExplainContext({ page: 'unknown' }, result)

    expect(ctx.page).toBe('scenarios')
    expect(ctx.scenario_name).toBe('GFC_2008')
    expect(ctx.stressed_pnl).toBe('-500000.00')
    expect(ctx.stressed_var).toBe('300000.00')
  })

  it('preserves the ambient copilot context keys', () => {
    const result = makeStressResult()

    const ctx = buildScenarioExplainContext(
      { page: 'unknown', book_id: 'book-7' },
      result,
    )

    expect(ctx.book_id).toBe('book-7')
  })

  it('attaches the top stressed positions ranked by absolute P&L impact', () => {
    const result = makeStressResult({
      positionImpacts: [
        makePositionImpact({ instrumentId: 'AAPL', pnlImpact: '-50000.00' }),
        makePositionImpact({ instrumentId: 'BIG', pnlImpact: '-900000.00' }),
        makePositionImpact({ instrumentId: 'TINY', pnlImpact: '-100.00' }),
      ],
    })

    const ctx = buildScenarioExplainContext({ page: 'scenarios' }, result)
    const top = ctx.top_stressed_positions as { instrumentId: string }[]

    expect(top.map((p) => p.instrumentId)).toEqual(['BIG', 'AAPL', 'TINY'])
  })
})

describe('topStressedPositions', () => {
  it('caps the result at five positions', () => {
    const result = makeStressResult({
      positionImpacts: Array.from({ length: 8 }, (_, i) =>
        makePositionImpact({
          instrumentId: `INST_${i}`,
          pnlImpact: `${-(i + 1) * 1000}.00`,
        }),
      ),
    })

    expect(topStressedPositions(result)).toHaveLength(5)
  })

  it('returns an empty array when the result carries no position impacts', () => {
    const result = makeStressResult({ positionImpacts: [] })

    expect(topStressedPositions(result)).toEqual([])
  })
})
