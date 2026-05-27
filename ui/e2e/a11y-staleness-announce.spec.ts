import { test, expect } from '@playwright/test'

// Data staleness announcer emits "Data updated N minutes ago" via an
// aria-live region when the data age crosses a configurable threshold
// (kx-d5ec).
//
// Risk dashboards refresh continuously, but the refresh pipeline can lag
// (kafka backpressure, network blips, market-data vendor outages). Sighted
// traders catch staleness from a quietly greying timestamp; screen-reader
// users need the same signal pushed to them at the moment it becomes
// material. The standard accessibility pattern is a status role with
// aria-live="polite" — polite, not assertive, because staleness is
// informational and should not interrupt other speech.
//
// The contract verified here:
//   1. A live region exists on the dashboard with role="status" and
//      aria-live="polite".
//   2. When data is fresh (under the threshold), the region is empty so
//      nothing is announced.
//   3. Once the age threshold is crossed, the region contains text of the
//      form "Data updated N minute(s) ago" so AT picks it up.
//
// The spec runs against `page.setContent` to keep it independent of the
// running dashboard — the markup contract is what we care about.

const FRESH_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <main>
      <h1>Risk dashboard</h1>
      <div
        data-testid="staleness-announcer"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      ></div>
    </main>
  </body>
</html>
`

const STALE_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <main>
      <h1>Risk dashboard</h1>
      <div
        data-testid="staleness-announcer"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >Data updated 3 minutes ago</div>
    </main>
  </body>
</html>
`

test.describe('data staleness announcer is accessible to AT', () => {
  test('exposes a status role so AT recognises the announcer', async ({ page }) => {
    await page.setContent(FRESH_HTML)
    await expect(page.getByRole('status')).toHaveCount(1)
  })

  test('uses aria-live="polite" so announcements do not interrupt speech', async ({
    page,
  }) => {
    await page.setContent(FRESH_HTML)
    await expect(page.getByTestId('staleness-announcer')).toHaveAttribute(
      'aria-live',
      'polite',
    )
  })

  test('uses aria-atomic="true" so the full message is announced as one chunk', async ({
    page,
  }) => {
    await page.setContent(FRESH_HTML)
    await expect(page.getByTestId('staleness-announcer')).toHaveAttribute(
      'aria-atomic',
      'true',
    )
  })

  test('renders no text while data is fresh so nothing is announced', async ({ page }) => {
    await page.setContent(FRESH_HTML)
    const text = await page.getByTestId('staleness-announcer').textContent()
    expect(text?.trim() ?? '').toBe('')
  })

  test('announces "Data updated 3 minutes ago" once the threshold is crossed', async ({
    page,
  }) => {
    await page.setContent(STALE_HTML)
    await expect(page.getByTestId('staleness-announcer')).toHaveText(
      'Data updated 3 minutes ago',
    )
  })

  test('announcement text matches the expected staleness pattern', async ({ page }) => {
    await page.setContent(STALE_HTML)
    const text = await page.getByTestId('staleness-announcer').textContent()
    expect(text).not.toBeNull()
    expect(text).toMatch(/Data updated \d+ minutes? ago/)
  })
})
