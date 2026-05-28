import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockEodTimelineRoutes,
  TEST_EOD_TIMELINE_RESPONSE,
} from './fixtures'

// ---------------------------------------------------------------------------
// Helper: navigate to the EOD History tab and open the detail drawer by
// clicking the first promotable row (2026-03-13 in the fixture).
// ---------------------------------------------------------------------------

async function openEodDrawer(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-eod').click()
  await page.waitForSelector('[data-testid="eod-timeline-tab"]')
  await page.getByTestId('eod-row-2026-03-13').click()
  await expect(page.getByTestId('eod-drill-panel')).toBeVisible()
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('EOD drawer closes', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
  })

  test('drawer closes when the user switches to a different top-level tab', async ({ page }) => {
    await openEodDrawer(page)

    // Switch to the Scenarios tab — its content is a peer of the EOD tab.
    await page.getByTestId('tab-scenarios').click()

    // The drawer overlay must be gone — otherwise it intercepts subsequent clicks.
    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()

    // The Scenarios tab content must be interactable (not blocked by the overlay).
    await expect(page.getByTestId('tab-scenarios')).toHaveAttribute('aria-selected', 'true')
  })

  test('drawer closes when the user presses Escape', async ({ page }) => {
    await openEodDrawer(page)

    await page.keyboard.press('Escape')

    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()
  })

  test('switching back to EOD History after closing drawer shows no drawer', async ({ page }) => {
    await openEodDrawer(page)

    // Switch away.
    await page.getByTestId('tab-positions').click()
    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()

    // Switch back — drawer must not reappear.
    await page.getByTestId('tab-eod').click()
    await page.waitForSelector('[data-testid="eod-timeline-tab"]')
    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()
  })
})
