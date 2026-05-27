import { describe, expect, it } from 'vitest'
import { correlationOutlierColor } from './correlationOutlierColor'

describe('correlationOutlierColor', () => {
  const norm = [0.1, 0.12, 0.08, 0.11, 0.09, 0.1, 0.13, 0.07]

  it('returns null when the value is within 2σ of the mean', () => {
    expect(correlationOutlierColor(0.1, norm)).toBeNull()
  })

  it('returns a highlight color when the value is more than 2σ above the mean', () => {
    expect(correlationOutlierColor(0.95, norm)).not.toBeNull()
  })

  it('returns a highlight color when the value is more than 2σ below the mean', () => {
    expect(correlationOutlierColor(-0.95, norm)).not.toBeNull()
  })

  it('uses a warm color for positive outliers', () => {
    expect(correlationOutlierColor(0.95, norm)).toBe('#ef4444')
  })

  it('uses a cool color for negative outliers', () => {
    expect(correlationOutlierColor(-0.95, norm)).toBe('#3b82f6')
  })

  it('returns null for non-finite values', () => {
    expect(correlationOutlierColor(NaN, norm)).toBeNull()
    expect(correlationOutlierColor(Infinity, norm)).toBeNull()
  })

  it('returns null when the population is empty', () => {
    expect(correlationOutlierColor(0.5, [])).toBeNull()
  })

  it('returns null when the population has zero variance and the value matches', () => {
    expect(correlationOutlierColor(0.3, [0.3, 0.3, 0.3])).toBeNull()
  })

  it('returns a highlight when the population has zero variance and the value differs', () => {
    expect(correlationOutlierColor(0.9, [0.3, 0.3, 0.3])).toBe('#ef4444')
  })

  it('treats exactly 2σ as inside the band (not an outlier)', () => {
    // Mean 0, sample std (Bessel) of [-1,1,-1,1] = sqrt(4/3); value = 2*std
    const pop = [-1, 1, -1, 1]
    const std = Math.sqrt(((-1) ** 2 + 1 ** 2 + (-1) ** 2 + 1 ** 2) / (pop.length - 1))
    expect(correlationOutlierColor(2 * std, pop)).toBeNull()
  })

  it('flags values just beyond 2σ as outliers', () => {
    const pop = [-1, 1, -1, 1]
    const std = Math.sqrt(((-1) ** 2 + 1 ** 2 + (-1) ** 2 + 1 ** 2) / (pop.length - 1))
    expect(correlationOutlierColor(2 * std + 0.01, pop)).toBe('#ef4444')
  })
})
