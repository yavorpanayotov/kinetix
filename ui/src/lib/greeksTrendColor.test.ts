import { describe, expect, it } from 'vitest'
import { greeksTrendColor } from './greeksTrendColor'

describe('greeksTrendColor', () => {
  it('returns an up chevron with green for a positive delta', () => {
    expect(greeksTrendColor(0.05)).toEqual({
      chevron: '▲',
      color: '#22c55e',
    })
  })

  it('returns a down chevron with red for a negative delta', () => {
    expect(greeksTrendColor(-0.05)).toEqual({
      chevron: '▼',
      color: '#ef4444',
    })
  })

  it('returns a flat dash with neutral color for zero', () => {
    expect(greeksTrendColor(0)).toEqual({
      chevron: '–',
      color: '#94a3b8',
    })
  })

  it('treats values within epsilon of zero as flat', () => {
    expect(greeksTrendColor(0.0000001)).toEqual({
      chevron: '–',
      color: '#94a3b8',
    })
    expect(greeksTrendColor(-0.0000001)).toEqual({
      chevron: '–',
      color: '#94a3b8',
    })
  })

  it('returns a flat indicator for non-finite values', () => {
    expect(greeksTrendColor(Number.NaN)).toEqual({
      chevron: '–',
      color: '#94a3b8',
    })
    expect(greeksTrendColor(Number.POSITIVE_INFINITY)).toEqual({
      chevron: '–',
      color: '#94a3b8',
    })
  })

  it('honours an explicit epsilon for the flat band', () => {
    expect(greeksTrendColor(0.01, { epsilon: 0.1 })).toEqual({
      chevron: '–',
      color: '#94a3b8',
    })
    expect(greeksTrendColor(0.2, { epsilon: 0.1 })).toEqual({
      chevron: '▲',
      color: '#22c55e',
    })
  })
})
