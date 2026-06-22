import { describe, it, expect } from 'vitest'
import { aggregateGreeksHistory } from './aggregateGreeksHistory'
import type { ChartDataPoint } from '../api/jobHistory'

function pt(over: Partial<ChartDataPoint>): ChartDataPoint {
  return {
    bucket: '2026-06-15T08:00:00Z',
    varValue: 1000,
    expectedShortfall: 1250,
    confidenceLevel: 'CL_95',
    delta: null,
    gamma: null,
    vega: null,
    theta: null,
    rho: null,
    pvValue: null,
    jobCount: 1,
    completedCount: 1,
    failedCount: 0,
    runningCount: 0,
    ...over,
  }
}

describe('aggregateGreeksHistory', () => {
  it('sums delta/gamma/vega/theta across books per bucket', () => {
    const result = aggregateGreeksHistory([
      [pt({ bucket: 'T1', delta: 10, gamma: 1, vega: 2, theta: -1 })],
      [pt({ bucket: 'T1', delta: 5, gamma: 0.5, vega: 1, theta: -0.5 })],
    ])
    expect(result).toHaveLength(1)
    expect(result[0]).toMatchObject({ calculatedAt: 'T1', delta: 15, gamma: 1.5, vega: 3, theta: -1.5 })
  })

  it('omits buckets where no book reported greeks', () => {
    const result = aggregateGreeksHistory([
      [pt({ bucket: 'T1', delta: null, gamma: null, vega: null })],
    ])
    expect(result).toEqual([])
  })

  it('sorts emitted buckets chronologically', () => {
    const result = aggregateGreeksHistory([
      [
        pt({ bucket: '2026-06-15T12:00:00Z', delta: 1, gamma: 1, vega: 1 }),
        pt({ bucket: '2026-06-15T08:00:00Z', delta: 2, gamma: 2, vega: 2 }),
      ],
    ])
    expect(result.map((e) => e.calculatedAt)).toEqual([
      '2026-06-15T08:00:00Z',
      '2026-06-15T12:00:00Z',
    ])
  })

  it('leaves VaR/ES at zero (greeks-only series)', () => {
    const result = aggregateGreeksHistory([[pt({ bucket: 'T1', delta: 1, gamma: 1, vega: 1 })]])
    expect(result[0].varValue).toBe(0)
    expect(result[0].expectedShortfall).toBe(0)
  })
})
