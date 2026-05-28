// Tests for the Greeks sign visual-cue helper (kx-kbze).
//
// Traders scanning a Greeks row for risk drivers want negative cells to
// pop visually — a short-gamma book that just printed -8000 gamma is the
// most urgent number on the page. A subtle light-red background does the
// signalling without overwhelming the table the way a bold-red text style
// would. Positive cells stay neutral so the eye is drawn only where it
// needs to be.

import { describe, it, expect } from 'vitest'

import { greeksSignCue } from './greeksSignCue'

describe('greeksSignCue', () => {
  it('returns the negative cue (light-red background) for negative Greek values', () => {
    expect(greeksSignCue(-1234.5)).toEqual({
      sign: 'negative',
      className: 'bg-red-50 dark:bg-red-950/30',
    })
  })

  it('returns the neutral cue for positive Greek values', () => {
    expect(greeksSignCue(42)).toEqual({ sign: 'neutral', className: '' })
  })

  it('treats zero as neutral (no signal needed)', () => {
    expect(greeksSignCue(0).sign).toBe('neutral')
  })

  it('returns the neutral cue for null (no value to flag)', () => {
    expect(greeksSignCue(null)).toEqual({ sign: 'neutral', className: '' })
  })

  it('returns the neutral cue for undefined', () => {
    expect(greeksSignCue(undefined).sign).toBe('neutral')
  })

  it('returns the neutral cue for NaN (defensive against bad input)', () => {
    expect(greeksSignCue(NaN).sign).toBe('neutral')
  })

  it('returns the neutral cue for Infinity', () => {
    expect(greeksSignCue(Infinity).sign).toBe('neutral')
    expect(greeksSignCue(-Infinity).sign).toBe('neutral')
  })

  it('flags very small negative numbers (-0.0001 must still pop)', () => {
    expect(greeksSignCue(-0.0001).sign).toBe('negative')
  })
})
