import { test, expect, type Page } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Plan 2.16 — order-ticket-holiday-warning.spec.ts.
 *
 * Phase 2 ships with launch-venue regular-session cutoffs only; the holiday
 * calendar lands in phase 2.5. During the gap, the order ticket renders a
 * maintenance-style WARNING banner whenever a DAY or GTD order is routed to
 * a venue without a calendar entry — the trader must verify the session is
 * actually open before submitting. The banner disappears once a launch
 * venue is selected, or when the TIF is non-day-bound (IOC/FOK/GTC).
 */

async function navigateToPlaceOrder(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.getByTestId('trades-subtab-place').click()
}

test.describe('PlaceOrderPanel — holiday calendar warning', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await navigateToPlaceOrder(page)
  })

  test('NYSE + DAY shows no holiday warning (launch venue is covered)', async ({ page }) => {
    await page.getByTestId('place-order-venue').selectOption('NYSE')
    await page.getByTestId('place-order-tif').selectOption('DAY')

    await expect(page.getByTestId('place-order-holiday-warning')).toHaveCount(0)
  })

  test('OTHER + DAY shows the holiday warning banner with venue-specific copy', async ({
    page,
  }) => {
    await page.getByTestId('place-order-venue').selectOption('OTHER')
    await page.getByTestId('place-order-tif').selectOption('DAY')

    const banner = page.getByTestId('place-order-holiday-warning')
    await expect(banner).toBeVisible()
    await expect(banner).toHaveAttribute('role', 'alert')
    await expect(banner).toContainText(
      'Holiday calendar incomplete for OTHER — verify session is open before submitting',
    )
  })

  test('OTHER + GTD also shows the warning (GTD orders are equally exposed)', async ({
    page,
  }) => {
    await page.getByTestId('place-order-venue').selectOption('OTHER')
    await page.getByTestId('place-order-tif').selectOption('GTD')

    await expect(page.getByTestId('place-order-holiday-warning')).toBeVisible()
  })

  test('OTHER + IOC does NOT show the warning (no day-bound exposure)', async ({ page }) => {
    await page.getByTestId('place-order-venue').selectOption('OTHER')
    await page.getByTestId('place-order-tif').selectOption('IOC')

    await expect(page.getByTestId('place-order-holiday-warning')).toHaveCount(0)
  })

  test('switching from OTHER back to NYSE clears the warning', async ({ page }) => {
    await page.getByTestId('place-order-venue').selectOption('OTHER')
    await page.getByTestId('place-order-tif').selectOption('DAY')
    await expect(page.getByTestId('place-order-holiday-warning')).toBeVisible()

    await page.getByTestId('place-order-venue').selectOption('LSE')
    await expect(page.getByTestId('place-order-holiday-warning')).toHaveCount(0)
  })
})
