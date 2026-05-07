import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { VenueRoutingStatusDot } from './VenueRoutingStatusDot'
import * as systemApi from '../api/system'

vi.mock('../api/system')

describe('VenueRoutingStatusDot', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders an "up" dot when fix-gateway is READY', async () => {
    vi.spyOn(systemApi, 'fetchSystemHealth').mockResolvedValue({
      status: 'UP',
      services: { 'fix-gateway': { status: 'READY' } },
    })

    render(<VenueRoutingStatusDot />)

    await waitFor(() => {
      expect(screen.getByTestId('venue-routing-status-dot')).toHaveAttribute('data-state', 'up')
    })
    expect(screen.getByTestId('venue-routing-status-dot')).toHaveAttribute(
      'aria-label',
      'Venue routing healthy',
    )
  })

  it('renders a "degraded" amber dot when fix-gateway is DOWN', async () => {
    vi.spyOn(systemApi, 'fetchSystemHealth').mockResolvedValue({
      status: 'DEGRADED',
      services: { 'fix-gateway': { status: 'DOWN' } },
    })

    render(<VenueRoutingStatusDot />)

    await waitFor(() => {
      expect(screen.getByTestId('venue-routing-status-dot')).toHaveAttribute('data-state', 'degraded')
    })
    expect(screen.getByTestId('venue-routing-status-dot')).toHaveAttribute(
      'aria-label',
      'Cancel confirmation unavailable — call venue directly to confirm cancel',
    )
  })

  it('renders an "unreachable" red dot when the health endpoint throws', async () => {
    vi.spyOn(systemApi, 'fetchSystemHealth').mockRejectedValue(new Error('boom'))

    render(<VenueRoutingStatusDot />)

    await waitFor(() => {
      expect(screen.getByTestId('venue-routing-status-dot')).toHaveAttribute(
        'data-state',
        'unreachable',
      )
    })
  })

  it('treats absent fix-gateway entry as "up" (fail-open)', async () => {
    vi.spyOn(systemApi, 'fetchSystemHealth').mockResolvedValue({
      status: 'UP',
      services: {},
    })

    render(<VenueRoutingStatusDot />)

    await waitFor(() => {
      expect(screen.getByTestId('venue-routing-status-dot')).toHaveAttribute('data-state', 'up')
    })
  })
})
