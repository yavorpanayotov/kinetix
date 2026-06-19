import { cleanup, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { MobileFreshnessBanner } from './MobileFreshnessBanner'

function minutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString()
}

function hoursAgo(hours: number): string {
  return new Date(Date.now() - hours * 60 * 60_000).toISOString()
}

describe('MobileFreshnessBanner', () => {
  it('renders nothing when no timestamp is provided', () => {
    const { container } = render(<MobileFreshnessBanner dataAsOf={null} />)

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByTestId('mobile-freshness-banner')).not.toBeInTheDocument()
  })

  describe('under 5 minutes (neutral)', () => {
    it('renders a neutral banner without the verify warning', () => {
      render(<MobileFreshnessBanner dataAsOf={minutesAgo(2)} />)

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner).toBeInTheDocument()
      expect(banner.className).toContain('slate')
      expect(banner.className).not.toContain('amber')
      expect(banner.className).not.toContain('red')
      expect(screen.queryByText(/VERIFY BEFORE ACTING/i)).not.toBeInTheDocument()
    })
  })

  describe('5 to 15 minutes (amber)', () => {
    it('renders an amber banner without the verify warning', () => {
      render(<MobileFreshnessBanner dataAsOf={minutesAgo(8)} />)

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.className).toContain('amber')
      expect(banner.className).not.toContain('red')
      expect(screen.queryByText(/VERIFY BEFORE ACTING/i)).not.toBeInTheDocument()
    })
  })

  describe('15 minutes or older (red)', () => {
    it('renders a red banner with the verify warning', () => {
      render(<MobileFreshnessBanner dataAsOf={minutesAgo(20)} />)

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.className).toContain('red')
      expect(screen.getByText(/VERIFY BEFORE ACTING/i)).toBeInTheDocument()
    })

    it('renders the red strip with heavier padding than the neutral state', () => {
      render(<MobileFreshnessBanner dataAsOf={minutesAgo(20)} />)
      const red = screen.getByTestId('mobile-freshness-banner')
      expect(red.className).toContain('py-3')

      cleanup()

      render(<MobileFreshnessBanner dataAsOf={minutesAgo(2)} />)
      const neutral = screen.getByTestId('mobile-freshness-banner')
      expect(neutral.className).not.toContain('py-3')
      expect(neutral.className).toContain('py-2')
    })

    it('uses a stronger dark-mode background than amber for clear separation', () => {
      render(<MobileFreshnessBanner dataAsOf={minutesAgo(20)} />)
      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.className).toContain('dark:bg-red-900/70')
    })
  })

  describe('relative-time grammar', () => {
    it('uses singular "1 minute ago" at the one-minute boundary', () => {
      render(<MobileFreshnessBanner dataAsOf={minutesAgo(1)} />)

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.textContent).toContain('Data as of 1 minute ago')
      expect(banner.textContent).not.toContain('1 minutes ago')
    })

    it('uses singular "1 hour ago" at the one-hour boundary', () => {
      render(<MobileFreshnessBanner dataAsOf={minutesAgo(60)} />)

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.textContent).toContain('Data as of 1 hour ago')
      expect(banner.textContent).not.toContain('1 hours ago')
    })

    it('uses plural "2 hours ago" for a multi-hour gap under a day', () => {
      render(<MobileFreshnessBanner dataAsOf={hoursAgo(2)} />)

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.textContent).toContain('Data as of 2 hours ago')
    })
  })

  describe('24 hours or older renders an absolute date', () => {
    it('renders an absolute date, not a relative "hours ago" string', () => {
      const timestamp = hoursAgo(30)
      render(<MobileFreshnessBanner dataAsOf={timestamp} />)

      const expected = new Date(timestamp).toLocaleDateString(undefined, {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
      })

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.textContent).toContain(`Data as of ${expected}`)
      expect(banner.textContent).not.toMatch(/hours ago/)
    })

    it('does not render an absurd gap as a raw "N hours ago" string', () => {
      // ~12481 hours ago — the seed-data case from the finding.
      const timestamp = hoursAgo(12481)
      render(<MobileFreshnessBanner dataAsOf={timestamp} />)

      const banner = screen.getByTestId('mobile-freshness-banner')
      expect(banner.textContent).not.toMatch(/\d+ hours ago/)
      expect(banner.textContent).not.toContain('12481')

      const expected = new Date(timestamp).toLocaleDateString(undefined, {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
      })
      expect(banner.textContent).toContain(`Data as of ${expected}`)
    })
  })
})
