// Tests for the VaR utilisation colour helper (kx-z2wf).
//
// Risk officers watch utilisation columns in the limit blotter — current
// exposure divided by the trader's VaR limit. Green up to 80% is "comfort
// zone"; orange between 80% and 100% is "approaching breach, slow down";
// red at or above 100% is "limit breached, stop and call". The helper
// turns a utilisation ratio into the tone, Tailwind class, and aria-label
// so every utilisation cell on every dashboard signals the same triad.

import { describe, it, expect } from 'vitest'

import { varUtilisationColor } from './varUtilisationColor'

describe('varUtilisationColor', () => {
  it('returns the comfort tone (green) below 80%', () => {
    expect(varUtilisationColor(0.5)).toEqual({
      tone: 'comfort',
      className: 'text-emerald-700 dark:text-emerald-300',
      ariaLabel: 'Within limit: 50% utilisation',
    })
  })

  it('returns the warning tone (orange) at exactly 80%', () => {
    expect(varUtilisationColor(0.8).tone).toBe('warning')
  })

  it('returns the warning tone between 80% and 100%', () => {
    expect(varUtilisationColor(0.95).tone).toBe('warning')
  })

  it('returns the breach tone (red) at exactly 100%', () => {
    expect(varUtilisationColor(1)).toEqual({
      tone: 'breach',
      className: 'text-red-700 dark:text-red-300',
      ariaLabel: 'Limit breached: 100% utilisation',
    })
  })

  it('returns the breach tone above 100%', () => {
    expect(varUtilisationColor(1.25).tone).toBe('breach')
  })

  it('clamps negative ratios to comfort tone (defensive — shouldn’t happen)', () => {
    expect(varUtilisationColor(-0.1).tone).toBe('comfort')
  })

  it('returns the unknown tone for null', () => {
    expect(varUtilisationColor(null)).toEqual({
      tone: 'unknown',
      className: 'text-slate-500 dark:text-slate-400',
      ariaLabel: 'Utilisation unavailable',
    })
  })

  it('returns the unknown tone for NaN / Infinity (defensive)', () => {
    expect(varUtilisationColor(NaN).tone).toBe('unknown')
    expect(varUtilisationColor(Infinity).tone).toBe('unknown')
  })

  it('formats the percentage in the aria-label without trailing zeros', () => {
    expect(varUtilisationColor(0.7).ariaLabel).toBe('Within limit: 70% utilisation')
    expect(varUtilisationColor(0.825).ariaLabel).toBe(
      'Approaching breach: 82.5% utilisation',
    )
  })
})
