import { describe, expect, it } from 'vitest'
import { dedupeAlerts } from './alertDedupe'
import type { AlertEventDto } from '../types'

function makeAlert(overrides: Partial<AlertEventDto> = {}): AlertEventDto {
  return {
    id: 'alert-1',
    ruleId: 'rule-1',
    ruleName: 'VaR Limit',
    type: 'VAR_BREACH',
    severity: 'CRITICAL',
    message: 'VaR breached',
    currentValue: 2300000,
    threshold: 2000000,
    bookId: 'book-1',
    triggeredAt: '2026-06-12T08:00:00Z',
    status: 'TRIGGERED',
    ...overrides,
  }
}

describe('dedupeAlerts', () => {
  it('keeps only the highest severity when the same book+type fires at two severities', () => {
    // The live macro-hedge case: one VaR condition tripped both the $750K
    // WARNING rule and the $1M CRITICAL rule.
    const result = dedupeAlerts([
      makeAlert({ id: 'crit', severity: 'CRITICAL', threshold: 1_000_000, bookId: 'macro-hedge' }),
      makeAlert({ id: 'warn', severity: 'WARNING', threshold: 750_000, bookId: 'macro-hedge' }),
    ])

    expect(result).toHaveLength(1)
    expect(result[0].id).toBe('crit')
  })

  it('keeps repeated firings at the same severity — rollup counting is the caller concern', () => {
    const result = dedupeAlerts([
      makeAlert({ id: 'older', triggeredAt: '2026-06-12T07:00:00Z' }),
      makeAlert({ id: 'newer', triggeredAt: '2026-06-12T09:00:00Z' }),
    ])

    expect(result.map((a) => a.id)).toEqual(['older', 'newer'])
  })

  it('does not merge different books or different alert types', () => {
    const result = dedupeAlerts([
      makeAlert({ id: 'a', bookId: 'book-a' }),
      makeAlert({ id: 'b', bookId: 'book-b' }),
      makeAlert({ id: 'c', bookId: 'book-a', type: 'PNL_THRESHOLD' }),
    ])

    expect(result.map((a) => a.id)).toEqual(['a', 'b', 'c'])
  })

  it('preserves first-appearance order', () => {
    const result = dedupeAlerts([
      makeAlert({ id: 'z', bookId: 'book-z' }),
      makeAlert({ id: 'a', bookId: 'book-a' }),
    ])

    expect(result.map((a) => a.id)).toEqual(['z', 'a'])
  })
})
