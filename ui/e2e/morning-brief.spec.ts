import { test, expect } from '@playwright/test'
import { briefMockCanned, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Morning brief — notification-strip morning-brief card (docs/plans/ai-v2.md §6.10)
// ---------------------------------------------------------------------------
//
// The notification strip (§6.9) sits between <SystemStatusBanner> and
// <RiskTickerStrip>. On mount the app fetches GET
// /api/v1/insights/brief/today; a `ready` response surfaces a
// <MorningBriefCard> at the top of the inbox. On the first inbox open of
// the trading day the strip auto-expands and scrolls the brief into view.
//
// These specs drive the running app against a deterministic mocked brief
// (`briefMockCanned`) so the morning-brief surface is exercised end-to-end
// without a live ai-insights-service.

const BRIEF_SEEN_KEY = 'kinetix:morning-brief:last-seen-date'

test.describe('Morning brief', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await briefMockCanned(page)
  })

  test('renders the morning brief card with its sections', async ({
    page,
  }) => {
    // Seed last-seen as today so the auto-expand does not fire — this
    // test asserts the manual-open path.
    await page.addInitScript(
      ([key]) => {
        const today = new Date().toISOString().slice(0, 10)
        window.localStorage.setItem(key, today)
      },
      [BRIEF_SEEN_KEY],
    )

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Strip is collapsed — expand it to reveal the inbox + brief.
    await page.getByTestId('notification-strip-toggle').click()

    const card = page.getByTestId('morning-brief-card')
    await expect(card).toBeVisible()
    await expect(card).toContainText('Morning Brief')
    await expect(card).toContainText('fx-main')

    await expect(page.getByTestId('brief-section-0')).toContainText(
      'Overnight VaR move',
    )
    await expect(page.getByTestId('brief-section-1')).toContainText(
      'Top movers',
    )
  })

  test('auto-expands on the first inbox open of the trading day', async ({
    page,
  }) => {
    // Clear the last-seen key BEFORE the app script runs so the strip
    // treats this as the first open of the day.
    await page.addInitScript(
      ([key]) => {
        window.localStorage.removeItem(key)
      },
      [BRIEF_SEEN_KEY],
    )

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // No manual click — the strip auto-expands because a brief is present
    // and the last-seen date differs from today.
    await expect(page.getByTestId('notification-strip')).toHaveAttribute(
      'data-expanded',
      'true',
    )
    await expect(page.getByTestId('notification-inbox')).toBeVisible()
    await expect(page.getByTestId('morning-brief-card')).toBeVisible()
  })

  test('dismissing notifications keeps the brief reachable', async ({
    page,
  }) => {
    // The morning brief is NOT a dismissable notification item. With zero
    // notification items the inbox is still non-empty because of the
    // brief, and expanding it always reveals the card.
    await page.addInitScript(
      ([key]) => {
        const today = new Date().toISOString().slice(0, 10)
        window.localStorage.setItem(key, today)
      },
      [BRIEF_SEEN_KEY],
    )

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // No "All caught up"/empty bar replaces the strip — the brief is content.
    await expect(
      page.getByTestId('notification-strip-empty'),
    ).toHaveCount(0)

    // Expand: the inbox is not empty, the brief card is reachable.
    await page.getByTestId('notification-strip-toggle').click()
    await expect(
      page.getByTestId('notification-inbox-empty'),
    ).toHaveCount(0)
    await expect(page.getByTestId('morning-brief-card')).toBeVisible()

    // Collapsing and re-expanding still shows the brief — it is not
    // dismissable and survives notification-inbox interactions.
    await page.getByTestId('notification-strip-toggle').click()
    await expect(page.getByTestId('notification-inbox')).toHaveCount(0)
    await page.getByTestId('notification-strip-toggle').click()
    await expect(page.getByTestId('morning-brief-card')).toBeVisible()
  })

  test('shows the Demo mode badge for a canned brief', async ({ page }) => {
    await page.addInitScript(
      ([key]) => {
        const today = new Date().toISOString().slice(0, 10)
        window.localStorage.setItem(key, today)
      },
      [BRIEF_SEEN_KEY],
    )

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('notification-strip-toggle').click()

    await expect(
      page.getByTestId('morning-brief-demo-badge'),
    ).toHaveText('Demo mode')
  })
})
