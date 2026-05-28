import { describe, expect, it } from 'vitest'
import {
  formatCurrencyPrefix,
  formatNumeric,
  formatPercent,
  formatRhoTooltip,
  formatVegaTooltip,
  formatZeroPadded,
} from './format'

describe('formatNumeric', () => {
  it('renders an em-dash for null', () => {
    expect(formatNumeric(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatNumeric(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatNumeric(NaN)).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatNumeric(Infinity)).toBe('—')
    expect(formatNumeric(-Infinity)).toBe('—')
  })

  it('formats a finite number with default 2 decimal places', () => {
    expect(formatNumeric(1234.5678)).toBe('1234.57')
  })

  it('honours the supplied fractionDigits', () => {
    expect(formatNumeric(1.23456, { fractionDigits: 4 })).toBe('1.2346')
  })

  it('formats zero without falling through to the em-dash branch', () => {
    expect(formatNumeric(0)).toBe('0.00')
  })

  it('formats negative numbers', () => {
    expect(formatNumeric(-42.5)).toBe('-42.50')
  })

  it('uses the supplied placeholder when given', () => {
    expect(formatNumeric(null, { placeholder: 'n/a' })).toBe('n/a')
  })
})

describe('formatRhoTooltip', () => {
  it('renders an em-dash for null', () => {
    expect(formatRhoTooltip(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatRhoTooltip(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatRhoTooltip(NaN)).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatRhoTooltip(Infinity)).toBe('—')
    expect(formatRhoTooltip(-Infinity)).toBe('—')
  })

  it('appends the $/bp IR unit suffix to a finite value', () => {
    expect(formatRhoTooltip(1234.5678)).toBe('1234.57 $/bp IR')
  })

  it('honours custom fractionDigits', () => {
    expect(formatRhoTooltip(1.23456, { fractionDigits: 4 })).toBe('1.2346 $/bp IR')
  })

  it('formats zero with the unit suffix', () => {
    expect(formatRhoTooltip(0)).toBe('0.00 $/bp IR')
  })

  it('formats negative values with the unit suffix', () => {
    expect(formatRhoTooltip(-42.5)).toBe('-42.50 $/bp IR')
  })
})

describe('formatVegaTooltip', () => {
  it('renders an em-dash for null', () => {
    expect(formatVegaTooltip(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatVegaTooltip(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatVegaTooltip(NaN)).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatVegaTooltip(Infinity)).toBe('—')
    expect(formatVegaTooltip(-Infinity)).toBe('—')
  })

  it('appends the %/1pp vol unit suffix to a finite value', () => {
    expect(formatVegaTooltip(1234.5678)).toBe('1234.57 %/1pp vol')
  })

  it('honours custom fractionDigits', () => {
    expect(formatVegaTooltip(1.23456, { fractionDigits: 4 })).toBe(
      '1.2346 %/1pp vol',
    )
  })

  it('formats zero with the unit suffix', () => {
    expect(formatVegaTooltip(0)).toBe('0.00 %/1pp vol')
  })

  it('formats negative values with the unit suffix', () => {
    expect(formatVegaTooltip(-42.5)).toBe('-42.50 %/1pp vol')
  })
})

describe('formatZeroPadded', () => {
  const FIGURE_SPACE = ' '
  const MINUS = '−'

  it('renders an em-dash for null', () => {
    expect(formatZeroPadded(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatZeroPadded(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatZeroPadded(NaN)).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatZeroPadded(Infinity)).toBe('—')
    expect(formatZeroPadded(-Infinity)).toBe('—')
  })

  it('pads the integer part to the default 4-digit width with figure spaces', () => {
    expect(formatZeroPadded(3.14)).toBe(`${FIGURE_SPACE}${FIGURE_SPACE}${FIGURE_SPACE}${FIGURE_SPACE}3.14`)
  })

  it('does not pad when the integer part already meets the width', () => {
    expect(formatZeroPadded(1234.5)).toBe(`${FIGURE_SPACE}1234.50`)
  })

  it('renders the sign slot for positive values (figure space) and negative values (minus)', () => {
    const positive = formatZeroPadded(3.14)
    const negative = formatZeroPadded(-3.14)
    // Both strings start with the sign slot, followed by the same padded body.
    expect(positive[0]).toBe(FIGURE_SPACE)
    expect(negative[0]).toBe(MINUS)
    expect(positive.slice(1)).toBe(negative.slice(1))
  })

  it('honours a custom integerWidth', () => {
    expect(formatZeroPadded(3.14, { integerWidth: 6 })).toBe(
      `${FIGURE_SPACE}${FIGURE_SPACE.repeat(5)}3.14`,
    )
  })

  it('honours custom fractionDigits', () => {
    expect(formatZeroPadded(3.14159, { fractionDigits: 4 })).toBe(
      `${FIGURE_SPACE}${FIGURE_SPACE.repeat(3)}3.1416`,
    )
  })

  it('handles zero with the same padding so columns of zeros still align', () => {
    expect(formatZeroPadded(0)).toBe(`${FIGURE_SPACE}${FIGURE_SPACE.repeat(3)}0.00`)
  })

  it('omits the decimal point when fractionDigits is 0', () => {
    expect(formatZeroPadded(42, { fractionDigits: 0 })).toBe(
      `${FIGURE_SPACE}${FIGURE_SPACE.repeat(2)}42`,
    )
  })
})

describe('formatCurrencyPrefix', () => {
  it('renders an em-dash for null', () => {
    expect(formatCurrencyPrefix(null, { symbol: '$' })).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatCurrencyPrefix(undefined, { symbol: '$' })).toBe('—')
  })

  it('renders an em-dash for NaN', () => {
    expect(formatCurrencyPrefix(NaN, { symbol: '$' })).toBe('—')
  })

  it('renders an em-dash for Infinity', () => {
    expect(formatCurrencyPrefix(Infinity, { symbol: '$' })).toBe('—')
    expect(formatCurrencyPrefix(-Infinity, { symbol: '$' })).toBe('—')
  })

  it('prefixes the symbol immediately before the value', () => {
    expect(formatCurrencyPrefix(1234.5, { symbol: '$' })).toBe('$1234.50')
  })

  it('supports multi-character symbols (kr, R$, etc.)', () => {
    expect(formatCurrencyPrefix(42, { symbol: 'kr' })).toBe('kr42.00')
  })

  it('supports non-ASCII symbols (€, £, ¥)', () => {
    expect(formatCurrencyPrefix(7.5, { symbol: '€' })).toBe('€7.50')
    expect(formatCurrencyPrefix(7.5, { symbol: '£' })).toBe('£7.50')
    expect(formatCurrencyPrefix(7.5, { symbol: '¥' })).toBe('¥7.50')
  })

  it('renders negatives with sign before the symbol (accounting convention)', () => {
    expect(formatCurrencyPrefix(-1234.5, { symbol: '$' })).toBe('-$1234.50')
  })

  it('honours custom fractionDigits', () => {
    expect(formatCurrencyPrefix(7.123456, { symbol: '$', fractionDigits: 4 })).toBe(
      '$7.1235',
    )
  })

  it('honours custom placeholder', () => {
    expect(
      formatCurrencyPrefix(null, { symbol: '$', placeholder: 'n/a' }),
    ).toBe('n/a')
  })

  it('renders zero with full precision', () => {
    expect(formatCurrencyPrefix(0, { symbol: '$' })).toBe('$0.00')
  })
})

describe('formatPercent', () => {
  it('renders an em-dash for null', () => {
    expect(formatPercent(null)).toBe('—')
  })

  it('renders an em-dash for undefined', () => {
    expect(formatPercent(undefined)).toBe('—')
  })

  it('renders an em-dash for NaN / Infinity', () => {
    expect(formatPercent(NaN)).toBe('—')
    expect(formatPercent(Infinity)).toBe('—')
  })

  it('renders 5% as "5%" (trims trailing zeros)', () => {
    expect(formatPercent(0.05)).toBe('5%')
  })

  it('renders 5.25% as "5.25%"', () => {
    expect(formatPercent(0.0525)).toBe('5.25%')
  })

  it('rounds to two fraction digits by default (5.2500 -> 5.25)', () => {
    expect(formatPercent(0.05250000)).toBe('5.25%')
  })

  it('trims trailing zeros so 5.5000% reads as "5.5%"', () => {
    expect(formatPercent(0.055)).toBe('5.5%')
  })

  it('honours custom fractionDigits for basis-point precision', () => {
    expect(formatPercent(0.012345, { fractionDigits: 4 })).toBe('1.2345%')
  })

  it('renders zero as "0%"', () => {
    expect(formatPercent(0)).toBe('0%')
  })

  it('renders negative ratios', () => {
    expect(formatPercent(-0.025)).toBe('-2.5%')
  })

  it('rounds half to even by default toFixed semantics', () => {
    // 5.005 * 100 = 500.49999... in IEEE-754, so toFixed(2) gives 5.00 — trims to 5%
    expect(formatPercent(0.05005)).toBe('5%')
  })
})
