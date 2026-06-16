import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { MobileFreshnessBanner } from './MobileFreshnessBanner'

function minutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString()
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
  })
})
