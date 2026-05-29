import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { PositionDto } from '../types'
import { PositionGrid } from './PositionGrid'
import { TapeReplayIndicator } from './TapeReplayIndicator'

// The Positions toolbar (Details / Columns chips) only renders when there is at
// least one position to show — otherwise the grid renders an empty state.
const samplePosition: PositionDto = {
  bookId: 'book-1',
  instrumentId: 'AAPL',
  assetClass: 'EQUITY',
  quantity: '100',
  averageCost: { amount: '150.00', currency: 'USD' },
  marketPrice: { amount: '155.00', currency: 'USD' },
  marketValue: { amount: '15500.00', currency: 'USD' },
  unrealizedPnl: { amount: '500.00', currency: 'USD' },
}

// Plan items P3 #29 / #31: the Positions toolbar "Details" and "Columns" chips
// had no obvious distinction, and the "Frozen" header pill was unlabelled
// ("Frozen what?"). These tests pin descriptive title/aria-label tooltips onto
// each chip so a user (and assistive tech) can tell them apart on hover.

describe('HeaderChips tooltips', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  describe('Positions toolbar — Details chip', () => {
    it('exposes a descriptive aria-label distinguishing it from Columns', () => {
      render(<PositionGrid positions={[samplePosition]} />)

      const details = screen.getByTestId('position-details-toggle')
      const ariaLabel = details.getAttribute('aria-label') ?? ''

      // Must be more than the bare word "Details".
      expect(ariaLabel.toLowerCase()).not.toBe('details')
      expect(ariaLabel.length).toBeGreaterThan('details'.length)
      // Explains what the chip reveals.
      expect(ariaLabel.toLowerCase()).toContain('column')
    })

    it('keeps an explanatory title tooltip', () => {
      render(<PositionGrid positions={[samplePosition]} />)

      const details = screen.getByTestId('position-details-toggle')
      expect(details.getAttribute('title')?.toLowerCase()).toContain('column')
    })
  })

  describe('Positions toolbar — Columns chip', () => {
    it('exposes a descriptive title and aria-label distinct from Details', () => {
      render(<PositionGrid positions={[samplePosition]} />)

      const columns = screen.getByTestId('column-settings-button')
      const title = columns.getAttribute('title') ?? ''
      const ariaLabel = columns.getAttribute('aria-label') ?? ''

      expect(title.length).toBeGreaterThan(0)
      expect(ariaLabel.toLowerCase()).not.toBe('columns')
      expect(ariaLabel.length).toBeGreaterThan('columns'.length)
      // Should mention choosing/showing/hiding columns.
      expect(`${title} ${ariaLabel}`.toLowerCase()).toContain('column')
    })

    it('reads differently from the Details chip so the two are distinguishable', () => {
      render(<PositionGrid positions={[samplePosition]} />)

      const columns = screen.getByTestId('column-settings-button')
      const details = screen.getByTestId('position-details-toggle')

      expect(columns.getAttribute('aria-label')).not.toBe(
        details.getAttribute('aria-label'),
      )
      expect(columns.getAttribute('title')).not.toBe(
        details.getAttribute('title'),
      )
    })
  })

  describe('Frozen header pill', () => {
    it('answers "frozen what?" with a descriptive aria-label', () => {
      render(<TapeReplayIndicator status="FROZEN" loading={false} />)

      const pill = screen.getByTestId('tape-replay-indicator')
      const ariaLabel = pill.getAttribute('aria-label') ?? ''

      // Previously just "Frozen" — the reviewer asked "Frozen what?".
      expect(ariaLabel.toLowerCase()).not.toBe('frozen')
      expect(ariaLabel.length).toBeGreaterThan('frozen'.length)
      expect(ariaLabel.toLowerCase()).toContain('frozen')
    })

    it('retains its explanatory title tooltip', () => {
      render(<TapeReplayIndicator status="FROZEN" loading={false} />)

      const pill = screen.getByTestId('tape-replay-indicator')
      const title = pill.getAttribute('title') ?? ''
      expect(title.length).toBeGreaterThan(0)
      expect(title.toLowerCase()).toContain('static')
    })
  })
})
