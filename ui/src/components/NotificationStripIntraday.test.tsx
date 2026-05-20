import { describe, test, expect, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationStrip } from './NotificationStrip'
import type { CopilotPushEvent } from '../api/copilot'

/**
 * Plan §7.9 — intraday Copilot push events rendered as items in
 * <NotificationStrip>.
 *
 * The pushes surfaced by `useCopilotWebSocket()` are passed to the strip
 * via the `intradayPushes` prop. Each renders as an inbox row with a `Zap`
 * icon and a time-of-trigger label derived from `generated_at`. More than
 * five pushes collapse the excess into a single "N more" badge. The
 * severity colour scheme is the one the strip already uses for its
 * notification rows — no parallel mapping.
 */

const BRIEF_SEEN_KEY = 'kinetix:morning-brief:last-seen-date'

function todayUtc(): string {
  return new Date().toISOString().slice(0, 10)
}

function makePush(overrides: Partial<CopilotPushEvent> = {}): CopilotPushEvent {
  return {
    alert_type: 'var_limit',
    severity: 'warning',
    book_id: 'fx-main',
    headline: 'VaR approaching limit on FX-MAIN',
    context_bullets: ['95% VaR at 92% of limit'],
    sources: [],
    session_id: 'sess-1',
    generated_at: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    ...overrides,
  }
}

/** Build `n` distinct pushes (unique session_id + headline). */
function makePushes(n: number): CopilotPushEvent[] {
  return Array.from({ length: n }, (_, i) =>
    makePush({
      session_id: `sess-${i}`,
      headline: `Intraday alert ${i}`,
      generated_at: new Date(Date.now() - (i + 1) * 60 * 1000).toISOString(),
    }),
  )
}

/** Open the inbox by clicking the toggle. */
async function openInbox(): Promise<void> {
  const user = userEvent.setup()
  await user.click(screen.getByTestId('notification-strip-toggle'))
}

describe('NotificationStrip — intraday push items', () => {
  beforeEach(() => {
    window.localStorage.clear()
    // Stamp today so the morning-brief auto-expand never fires here.
    window.localStorage.setItem(BRIEF_SEEN_KEY, todayUtc())
  })

  test('renders an intraday-push row per event with a Zap icon', async () => {
    render(<NotificationStrip items={[]} intradayPushes={makePushes(2)} />)
    await openInbox()
    const rows = screen.getAllByTestId(/^intraday-push-item-/)
    expect(rows).toHaveLength(2)
    for (const row of rows) {
      expect(
        within(row).getByTestId('intraday-push-zap-icon'),
      ).toBeInTheDocument()
    }
  })

  test('renders a time-of-trigger label derived from generated_at', async () => {
    const push = makePush({
      session_id: 'sess-x',
      headline: 'Single alert',
      generated_at: new Date(Date.now() - 7 * 60 * 1000).toISOString(),
    })
    render(<NotificationStrip items={[]} intradayPushes={[push]} />)
    await openInbox()
    const row = screen.getByTestId('intraday-push-item-sess-x')
    expect(
      within(row).getByTestId('intraday-push-time'),
    ).toHaveTextContent('7m ago')
  })

  test('a push makes the strip non-empty even with zero items', () => {
    render(<NotificationStrip items={[]} intradayPushes={makePushes(1)} />)
    expect(
      screen.queryByTestId('notification-strip-empty'),
    ).not.toBeInTheDocument()
    expect(screen.getByTestId('notification-strip-toggle')).toBeInTheDocument()
  })

  describe('overflow', () => {
    test('five or fewer pushes render every row and no overflow badge', async () => {
      render(<NotificationStrip items={[]} intradayPushes={makePushes(5)} />)
      await openInbox()
      expect(screen.getAllByTestId(/^intraday-push-item-/)).toHaveLength(5)
      expect(
        screen.queryByTestId('intraday-push-overflow'),
      ).not.toBeInTheDocument()
    })

    test('more than five pushes collapse the excess into a "N more" badge', async () => {
      render(<NotificationStrip items={[]} intradayPushes={makePushes(8)} />)
      await openInbox()
      // Only the first five render as rows.
      expect(screen.getAllByTestId(/^intraday-push-item-/)).toHaveLength(5)
      const badge = screen.getByTestId('intraday-push-overflow')
      expect(badge).toHaveTextContent('3 more')
    })

    test('exactly six pushes collapse to a "1 more" badge', async () => {
      render(<NotificationStrip items={[]} intradayPushes={makePushes(6)} />)
      await openInbox()
      expect(screen.getAllByTestId(/^intraday-push-item-/)).toHaveLength(5)
      expect(
        screen.getByTestId('intraday-push-overflow'),
      ).toHaveTextContent('1 more')
    })
  })

  describe('severity colour contract', () => {
    test('a warning push uses the amber severity colour', async () => {
      render(
        <NotificationStrip
          items={[]}
          intradayPushes={[
            makePush({ session_id: 's-warn', severity: 'warning' }),
          ]}
        />,
      )
      await openInbox()
      const row = screen.getByTestId('intraday-push-item-s-warn')
      const dot = within(row).getByTestId('intraday-push-severity-dot')
      expect(dot.className).toMatch(/amber/)
    })

    test('a critical push uses the red severity colour', async () => {
      render(
        <NotificationStrip
          items={[]}
          intradayPushes={[
            makePush({ session_id: 's-crit', severity: 'critical' }),
          ]}
        />,
      )
      await openInbox()
      const row = screen.getByTestId('intraday-push-item-s-crit')
      const dot = within(row).getByTestId('intraday-push-severity-dot')
      expect(dot.className).toMatch(/red/)
    })

    test('an unknown severity falls back to the info (slate) colour', async () => {
      render(
        <NotificationStrip
          items={[]}
          intradayPushes={[
            makePush({ session_id: 's-unknown', severity: 'mystery' }),
          ]}
        />,
      )
      await openInbox()
      const row = screen.getByTestId('intraday-push-item-s-unknown')
      const dot = within(row).getByTestId('intraday-push-severity-dot')
      expect(dot.className).toMatch(/slate/)
    })
  })

  test('intraday-push severities count toward the collapsed-bar chips', () => {
    render(
      <NotificationStrip
        items={[]}
        intradayPushes={[
          makePush({ session_id: 's-1', severity: 'critical' }),
          makePush({ session_id: 's-2', severity: 'warning' }),
        ]}
      />,
    )
    expect(screen.getByTestId('notification-chip-critical')).toHaveTextContent(
      '1',
    )
    expect(screen.getByTestId('notification-chip-warning')).toHaveTextContent(
      '1',
    )
  })
})
