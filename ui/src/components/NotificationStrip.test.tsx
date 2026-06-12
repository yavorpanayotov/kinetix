import { describe, test, expect, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationStrip } from './NotificationStrip'
import type { NotificationItem } from './NotificationStrip'
import type { MorningBrief } from '../api/brief'

const DISMISSED_KEY = 'kinetix:copilot-inbox:dismissed'
const BRIEF_SEEN_KEY = 'kinetix:morning-brief:last-seen-date'

function todayUtc(): string {
  return new Date().toISOString().slice(0, 10)
}

function makeBrief(): MorningBrief {
  return {
    book_id: 'fx-main',
    generated_at: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
    mode: 'canned',
    sections: [
      {
        title: 'Overnight VaR move',
        narrative: 'VaR rose overnight.',
        bullets: ['EURUSD vol up'],
        sources: [],
        severity: 'warning',
        status: 'ok',
      },
    ],
  }
}

function makeItems(): NotificationItem[] {
  return [
    {
      id: 'n-1',
      severity: 'critical',
      title: 'VaR limit breached',
      body: 'Book ALPHA exceeded the 95% VaR limit.',
      timestamp: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      source: 'Intraday alert',
    },
    {
      id: 'n-2',
      severity: 'warning',
      title: 'Stale price feed',
      timestamp: new Date(Date.now() - 30 * 60 * 1000).toISOString(),
      source: 'Morning brief',
    },
    {
      id: 'n-3',
      severity: 'warning',
      title: 'Counterparty exposure rising',
      timestamp: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    },
  ]
}

describe('NotificationStrip', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  test('renders the empty state when there are no items', () => {
    render(<NotificationStrip items={[]} />)
    expect(screen.getByTestId('notification-strip-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('notification-strip-toggle')).not.toBeInTheDocument()
  })

  test('renders the error state when error prop is set', () => {
    render(<NotificationStrip items={makeItems()} error="feed down" />)
    const errorBar = screen.getByTestId('notification-strip-error')
    expect(errorBar).toBeInTheDocument()
    expect(errorBar).toHaveTextContent('feed down')
    expect(errorBar).toHaveAttribute('role', 'alert')
    // Error takes precedence — no populated bar.
    expect(screen.queryByTestId('notification-unread-count')).not.toBeInTheDocument()
  })

  test('collapsed bar shows unread count and severity chips', () => {
    render(<NotificationStrip items={makeItems()} />)
    expect(screen.getByTestId('notification-unread-count')).toHaveTextContent('3')
    expect(screen.getByTestId('notification-chip-critical')).toHaveTextContent('1')
    expect(screen.getByTestId('notification-chip-warning')).toHaveTextContent('2')
    expect(screen.queryByTestId('notification-chip-info')).not.toBeInTheDocument()
  })

  test('collapsed bar is 36px tall', () => {
    render(<NotificationStrip items={makeItems()} />)
    expect(screen.getByTestId('notification-strip')).toHaveClass('h-9')
  })

  test('clicking the toggle expands to show the inbox', async () => {
    const user = userEvent.setup()
    render(<NotificationStrip items={makeItems()} />)
    expect(screen.queryByTestId('notification-inbox')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('notification-strip-toggle'))
    expect(screen.getByTestId('notification-inbox')).toBeInTheDocument()
    expect(screen.getByTestId('notification-strip')).toHaveAttribute('data-expanded', 'true')
  })

  test('clicking the toggle again collapses', async () => {
    const user = userEvent.setup()
    render(<NotificationStrip items={makeItems()} />)
    await user.click(screen.getByTestId('notification-strip-toggle'))
    expect(screen.getByTestId('notification-inbox')).toBeInTheDocument()
    await user.click(screen.getByTestId('notification-strip-toggle'))
    expect(screen.queryByTestId('notification-inbox')).not.toBeInTheDocument()
    expect(screen.getByTestId('notification-strip')).toHaveAttribute('data-expanded', 'false')
  })

  test('controlled expansion via expanded prop', async () => {
    const user = userEvent.setup()
    let lastValue: boolean | null = null
    render(
      <NotificationStrip
        items={makeItems()}
        expanded={true}
        onExpandedChange={(v: boolean) => {
          lastValue = v
        }}
      />,
    )
    // Shown without clicking because controlled-expanded.
    expect(screen.getByTestId('notification-inbox')).toBeInTheDocument()
    await user.click(screen.getByTestId('notification-strip-toggle'))
    expect(lastValue).toBe(false)
  })

  test('expanded inbox lists one row per visible item', async () => {
    const user = userEvent.setup()
    render(<NotificationStrip items={makeItems()} />)
    await user.click(screen.getByTestId('notification-strip-toggle'))
    expect(screen.getByTestId('notification-item-n-1')).toBeInTheDocument()
    expect(screen.getByTestId('notification-item-n-2')).toBeInTheDocument()
    expect(screen.getByTestId('notification-item-n-3')).toBeInTheDocument()
  })

  test('renders live notification content — title, body and source — for a non-empty items prop', async () => {
    // Mirrors the shape App.tsx derives from `useNotifications` alerts: a
    // limit-breach alert mapped to title (rule name), body (message) and a
    // "Alert" source label.
    const liveItems: NotificationItem[] = [
      {
        id: 'evt-1',
        severity: 'critical',
        title: 'VaR Limit',
        body: 'VaR exceeded threshold',
        timestamp: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
        source: 'Alert',
      },
    ]
    const user = userEvent.setup()
    render(<NotificationStrip items={liveItems} />)
    // Collapsed bar reflects the live item.
    expect(screen.getByTestId('notification-unread-count')).toHaveTextContent('1')
    // Expanded inbox renders the row with its title, body and source.
    await user.click(screen.getByTestId('notification-strip-toggle'))
    const row = screen.getByTestId('notification-item-evt-1')
    expect(within(row).getByText('VaR Limit')).toBeInTheDocument()
    expect(within(row).getByText('VaR exceeded threshold')).toBeInTheDocument()
    expect(within(row).getByText('Alert')).toBeInTheDocument()
  })

  test('per-item dismiss removes the item and persists to localStorage', async () => {
    const user = userEvent.setup()
    render(<NotificationStrip items={makeItems()} />)
    await user.click(screen.getByTestId('notification-strip-toggle'))
    await user.click(screen.getByTestId('notification-dismiss-n-2'))
    expect(screen.queryByTestId('notification-item-n-2')).not.toBeInTheDocument()
    expect(screen.getByTestId('notification-unread-count')).toHaveTextContent('2')
    const stored = JSON.parse(
      window.localStorage.getItem(DISMISSED_KEY) ?? '[]',
    ) as string[]
    expect(stored).toContain('n-2')
  })

  test('dismiss-all clears the visible list and persists every id', async () => {
    const user = userEvent.setup()
    render(<NotificationStrip items={makeItems()} />)
    await user.click(screen.getByTestId('notification-strip-toggle'))
    await user.click(screen.getByTestId('notification-dismiss-all'))
    expect(screen.getByTestId('notification-inbox-empty')).toBeInTheDocument()
    const stored = JSON.parse(
      window.localStorage.getItem(DISMISSED_KEY) ?? '[]',
    ) as string[]
    expect(stored).toEqual(expect.arrayContaining(['n-1', 'n-2', 'n-3']))
    expect(screen.getByTestId('notification-strip-empty')).toBeInTheDocument()
  })

  test('dismissed ids from localStorage are filtered out on mount', () => {
    window.localStorage.setItem(DISMISSED_KEY, JSON.stringify(['n-1']))
    render(<NotificationStrip items={makeItems()} expanded={true} />)
    expect(screen.queryByTestId('notification-item-n-1')).not.toBeInTheDocument()
    expect(screen.getByTestId('notification-unread-count')).toHaveTextContent('2')
  })

  test('malformed localStorage value is tolerated', () => {
    window.localStorage.setItem(DISMISSED_KEY, 'not json{')
    expect(() =>
      render(<NotificationStrip items={makeItems()} expanded={true} />),
    ).not.toThrow()
    expect(screen.getByTestId('notification-item-n-1')).toBeInTheDocument()
    expect(screen.getByTestId('notification-item-n-2')).toBeInTheDocument()
    expect(screen.getByTestId('notification-item-n-3')).toBeInTheDocument()
  })

  test('inbox scroll container has max-height 320px', async () => {
    const user = userEvent.setup()
    render(<NotificationStrip items={makeItems()} />)
    await user.click(screen.getByTestId('notification-strip-toggle'))
    expect(screen.getByTestId('notification-inbox')).toHaveClass('max-h-80')
  })

  test('severity chip colours map correctly', () => {
    const items: NotificationItem[] = [
      { id: 'c', severity: 'critical', title: 'C', timestamp: new Date().toISOString() },
      { id: 'w', severity: 'warning', title: 'W', timestamp: new Date().toISOString() },
      { id: 'i', severity: 'info', title: 'I', timestamp: new Date().toISOString() },
    ]
    render(<NotificationStrip items={items} />)
    expect(screen.getByTestId('notification-chip-critical').className).toMatch(/red/)
    expect(screen.getByTestId('notification-chip-warning').className).toMatch(/amber/)
    expect(screen.getByTestId('notification-chip-info').className).toMatch(/slate/)
  })

  test('toggle exposes aria-expanded', async () => {
    const user = userEvent.setup()
    render(<NotificationStrip items={makeItems()} />)
    const toggle = screen.getByTestId('notification-strip-toggle')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  describe('morning brief', () => {
    test('renders the brief card inside the inbox when morningBrief is set', async () => {
      // last-seen is today so auto-expand does not fire — open manually.
      window.localStorage.setItem(BRIEF_SEEN_KEY, todayUtc())
      const user = userEvent.setup()
      render(<NotificationStrip items={makeItems()} morningBrief={makeBrief()} />)
      await user.click(screen.getByTestId('notification-strip-toggle'))
      const wrapper = screen.getByTestId('notification-inbox-brief')
      expect(wrapper).toBeInTheDocument()
      expect(
        within(wrapper).getByTestId('morning-brief-card'),
      ).toBeInTheDocument()
    })

    test('a brief makes the strip non-empty even with zero items', () => {
      window.localStorage.setItem(BRIEF_SEEN_KEY, todayUtc())
      render(<NotificationStrip items={[]} morningBrief={makeBrief()} />)
      // No "No notifications" empty bar — the brief is content.
      expect(
        screen.queryByTestId('notification-strip-empty'),
      ).not.toBeInTheDocument()
      expect(
        screen.getByTestId('notification-strip-toggle'),
      ).toBeInTheDocument()
    })

    test('the inbox is not empty when only a brief is present', async () => {
      window.localStorage.setItem(BRIEF_SEEN_KEY, todayUtc())
      const user = userEvent.setup()
      render(<NotificationStrip items={[]} morningBrief={makeBrief()} />)
      await user.click(screen.getByTestId('notification-strip-toggle'))
      expect(
        screen.queryByTestId('notification-inbox-empty'),
      ).not.toBeInTheDocument()
      expect(screen.getByTestId('morning-brief-card')).toBeInTheDocument()
    })

    test('auto-expands on the first inbox open of the trading day', () => {
      window.localStorage.removeItem(BRIEF_SEEN_KEY)
      render(<NotificationStrip items={[]} morningBrief={makeBrief()} />)
      expect(screen.getByTestId('notification-strip')).toHaveAttribute(
        'data-expanded',
        'true',
      )
      expect(screen.getByTestId('morning-brief-card')).toBeInTheDocument()
      // The last-seen date is stamped so a re-open today won't re-trigger.
      expect(window.localStorage.getItem(BRIEF_SEEN_KEY)).toBe(todayUtc())
    })

    test('does not auto-expand when last-seen-date is today', () => {
      window.localStorage.setItem(BRIEF_SEEN_KEY, todayUtc())
      render(<NotificationStrip items={[]} morningBrief={makeBrief()} />)
      expect(screen.getByTestId('notification-strip')).toHaveAttribute(
        'data-expanded',
        'false',
      )
      expect(
        screen.queryByTestId('morning-brief-card'),
      ).not.toBeInTheDocument()
    })

    test('collapses the open inbox when autoCollapseKey changes (tab switch)', async () => {
      window.localStorage.setItem(BRIEF_SEEN_KEY, todayUtc())
      const user = userEvent.setup()
      const { rerender } = render(
        <NotificationStrip items={makeItems()} morningBrief={makeBrief()} autoCollapseKey="positions" />,
      )
      await user.click(screen.getByTestId('notification-strip-toggle'))
      expect(screen.getByTestId('notification-strip')).toHaveAttribute('data-expanded', 'true')

      rerender(
        <NotificationStrip items={makeItems()} morningBrief={makeBrief()} autoCollapseKey="risk" />,
      )
      expect(screen.getByTestId('notification-strip')).toHaveAttribute('data-expanded', 'false')
    })

    test('does not auto-expand for a stale brief generated more than 24h ago', () => {
      // UX review: a 23-day-old Morning Brief auto-expanded at the top of
      // every tab, eating ~190px of every screen. A stale brief stays
      // reachable behind the toggle but never auto-opens.
      window.localStorage.removeItem(BRIEF_SEEN_KEY)
      const staleBrief = {
        ...makeBrief(),
        generated_at: new Date(Date.now() - 23 * 24 * 60 * 60 * 1000).toISOString(),
      }
      render(<NotificationStrip items={[]} morningBrief={staleBrief} />)
      expect(screen.getByTestId('notification-strip')).toHaveAttribute(
        'data-expanded',
        'false',
      )
      // The seen-date must NOT be stamped — a fresh brief arriving later
      // today should still auto-expand once.
      expect(window.localStorage.getItem(BRIEF_SEEN_KEY)).toBeNull()
    })

    test('does not auto-expand when there is no brief', () => {
      window.localStorage.removeItem(BRIEF_SEEN_KEY)
      render(<NotificationStrip items={makeItems()} />)
      expect(screen.getByTestId('notification-strip')).toHaveAttribute(
        'data-expanded',
        'false',
      )
    })

    test('dismissing all notifications keeps the brief reachable', async () => {
      window.localStorage.setItem(BRIEF_SEEN_KEY, todayUtc())
      const user = userEvent.setup()
      render(<NotificationStrip items={makeItems()} morningBrief={makeBrief()} />)
      await user.click(screen.getByTestId('notification-strip-toggle'))
      await user.click(screen.getByTestId('notification-dismiss-all'))
      // Notifications gone, but the brief card is still rendered.
      expect(
        screen.queryByTestId('notification-item-n-1'),
      ).not.toBeInTheDocument()
      expect(screen.getByTestId('morning-brief-card')).toBeInTheDocument()
    })
  })
})
