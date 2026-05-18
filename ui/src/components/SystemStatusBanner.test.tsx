import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SystemStatusBanner } from './SystemStatusBanner'

describe('SystemStatusBanner', () => {
  it('renders nothing when no condition is active', () => {
    const { container } = render(
      <SystemStatusBanner
        exhausted={false}
        reconnecting={false}
        maintenance={false}
        systemHealthStatus="UP"
        disconnectElapsed={0}
        onReconnect={vi.fn()}
      />,
    )

    expect(container.firstChild).toBeNull()
  })

  it('renders only the reconnecting banner when reconnecting is active', () => {
    render(
      <SystemStatusBanner
        exhausted={false}
        reconnecting={true}
        maintenance={false}
        systemHealthStatus="UP"
        disconnectElapsed={0}
        onReconnect={vi.fn()}
      />,
    )

    expect(screen.getByTestId('reconnecting-banner')).toBeInTheDocument()
    expect(screen.queryByTestId('connection-lost-banner')).not.toBeInTheDocument()
    expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
  })

  it('reconnecting banner exposes role=alert on the message only (elapsed counter is aria-live=off)', () => {
    render(
      <SystemStatusBanner
        exhausted={false}
        reconnecting={true}
        maintenance={false}
        systemHealthStatus="UP"
        disconnectElapsed={47}
        onReconnect={vi.fn()}
      />,
    )

    const banner = screen.getByTestId('reconnecting-banner')
    const alerts = banner.querySelectorAll('[role="alert"]')
    expect(alerts).toHaveLength(1)
    expect(alerts[0].textContent ?? '').not.toMatch(/\d+s/)

    const elapsed = screen.getByTestId('reconnecting-banner-elapsed')
    expect(elapsed).toHaveAttribute('aria-live', 'off')
    expect(elapsed.textContent).toContain('47s')
    expect(alerts[0].contains(elapsed)).toBe(false)
  })

  it('renders only the maintenance banner with role="status" when only maintenance is active', () => {
    render(
      <SystemStatusBanner
        exhausted={false}
        reconnecting={false}
        maintenance={true}
        systemHealthStatus="DEGRADED"
        disconnectElapsed={0}
        onReconnect={vi.fn()}
      />,
    )

    const banner = screen.getByTestId('maintenance-banner')
    expect(banner).toBeInTheDocument()
    expect(banner).toHaveAttribute('role', 'status')
    expect(banner).toHaveTextContent('Scheduled maintenance in progress')

    expect(screen.queryByTestId('reconnecting-banner')).not.toBeInTheDocument()
    expect(screen.queryByTestId('connection-lost-banner')).not.toBeInTheDocument()
  })

  it('renders only the exhausted banner with role="alert" and a Reconnect button when exhausted', () => {
    const onReconnect = vi.fn()
    render(
      <SystemStatusBanner
        exhausted={true}
        reconnecting={false}
        maintenance={false}
        systemHealthStatus="UP"
        disconnectElapsed={120}
        onReconnect={onReconnect}
      />,
    )

    const banner = screen.getByTestId('connection-lost-banner')
    expect(banner).toBeInTheDocument()
    expect(banner).toHaveAttribute('role', 'alert')
    expect(banner).toHaveTextContent('Connection lost')

    expect(screen.getByTestId('reconnect-button')).toBeInTheDocument()
    expect(screen.queryByTestId('reconnecting-banner')).not.toBeInTheDocument()
    expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
  })

  it('exhausted takes priority over both reconnecting and maintenance', () => {
    render(
      <SystemStatusBanner
        exhausted={true}
        reconnecting={true}
        maintenance={true}
        systemHealthStatus="DEGRADED"
        disconnectElapsed={42}
        onReconnect={vi.fn()}
      />,
    )

    expect(screen.getByTestId('connection-lost-banner')).toBeInTheDocument()
    expect(screen.queryByTestId('reconnecting-banner')).not.toBeInTheDocument()
    expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
  })

  it('reconnecting takes priority over maintenance', () => {
    render(
      <SystemStatusBanner
        exhausted={false}
        reconnecting={true}
        maintenance={true}
        systemHealthStatus="DEGRADED"
        disconnectElapsed={5}
        onReconnect={vi.fn()}
      />,
    )

    expect(screen.getByTestId('reconnecting-banner')).toBeInTheDocument()
    expect(screen.queryByTestId('maintenance-banner')).not.toBeInTheDocument()
  })

  it('never stacks: only one live region is in the DOM at any time', () => {
    const cases = [
      { exhausted: false, reconnecting: false, maintenance: true },
      { exhausted: false, reconnecting: true, maintenance: false },
      { exhausted: true, reconnecting: false, maintenance: false },
      { exhausted: true, reconnecting: true, maintenance: true },
      { exhausted: false, reconnecting: true, maintenance: true },
    ] as const

    for (const c of cases) {
      const { unmount } = render(
        <SystemStatusBanner
          {...c}
          systemHealthStatus="DEGRADED"
          disconnectElapsed={0}
          onReconnect={vi.fn()}
        />,
      )

      const alerts = document.querySelectorAll('[role="alert"]')
      const statuses = document.querySelectorAll('[role="status"]')
      // Exactly one live region — never two banners stacked.
      expect(alerts.length + statuses.length).toBe(1)

      unmount()
    }
  })

  it('uses degraded-health copy in the reconnecting banner when system health is DEGRADED', () => {
    render(
      <SystemStatusBanner
        exhausted={false}
        reconnecting={true}
        maintenance={false}
        systemHealthStatus="DEGRADED"
        disconnectElapsed={0}
        onReconnect={vi.fn()}
      />,
    )

    const banner = screen.getByTestId('reconnecting-banner')
    expect(banner).toHaveTextContent('System update in progress')
  })

  it('uses unknown-health copy in the reconnecting banner when system health is unknown', () => {
    render(
      <SystemStatusBanner
        exhausted={false}
        reconnecting={true}
        maintenance={false}
        systemHealthStatus={null}
        disconnectElapsed={0}
        onReconnect={vi.fn()}
      />,
    )

    const banner = screen.getByTestId('reconnecting-banner')
    expect(banner).toHaveTextContent('Unable to reach server')
  })

  it('clicking the Reconnect button invokes the supplied handler', () => {
    const onReconnect = vi.fn()
    render(
      <SystemStatusBanner
        exhausted={true}
        reconnecting={false}
        maintenance={false}
        systemHealthStatus="UP"
        disconnectElapsed={0}
        onReconnect={onReconnect}
      />,
    )

    screen.getByTestId('reconnect-button').click()
    expect(onReconnect).toHaveBeenCalledTimes(1)
  })
})
