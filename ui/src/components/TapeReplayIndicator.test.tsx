import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TapeReplayIndicator } from './TapeReplayIndicator'

describe('TapeReplayIndicator', () => {
  it('renders nothing when status is null and not loading', () => {
    render(<TapeReplayIndicator status={null} loading={false} />)
    expect(screen.queryByTestId('tape-replay-indicator')).not.toBeInTheDocument()
    expect(screen.queryByTestId('tape-replay-indicator-loading')).not.toBeInTheDocument()
  })

  it('renders the loading placeholder before the first response lands', () => {
    render(<TapeReplayIndicator status={null} loading={true} />)
    expect(screen.getByTestId('tape-replay-indicator-loading')).toBeInTheDocument()
  })

  it('renders the ACTIVE variant with replay copy', () => {
    render(<TapeReplayIndicator status="ACTIVE" loading={false} />)
    const indicator = screen.getByTestId('tape-replay-indicator')
    expect(indicator).toHaveAttribute('data-status', 'ACTIVE')
    expect(indicator).toHaveAttribute('aria-label', 'Tape replay active')
    expect(indicator).toHaveTextContent('Tape Replay Active')
  })

  it('renders the FROZEN variant with static-screen copy', () => {
    render(<TapeReplayIndicator status="FROZEN" loading={false} />)
    const indicator = screen.getByTestId('tape-replay-indicator')
    expect(indicator).toHaveAttribute('data-status', 'FROZEN')
    // The pill answers "frozen what?" for assistive tech (plan P3 #31).
    expect(indicator).toHaveAttribute(
      'aria-label',
      'Frozen market data — screen is static between resets',
    )
  })

  it('renders the LIVE variant in production mode', () => {
    render(<TapeReplayIndicator status="LIVE" loading={false} />)
    const indicator = screen.getByTestId('tape-replay-indicator')
    expect(indicator).toHaveAttribute('data-status', 'LIVE')
    expect(indicator).toHaveAttribute('aria-label', 'Live market data')
  })
})
