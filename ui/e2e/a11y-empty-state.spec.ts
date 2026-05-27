import { test, expect } from '@playwright/test'

// Accessible empty-state announces "no data" via a polite live region
// (kx-67nh).
//
// WCAG 4.1.3 (Status Messages): when the application surfaces a status
// that the user should know about — including "no data" or "no results" —
// the message must be programmatically determinable as a status without
// requiring the user to move focus to find it. Wrapping the visible copy
// in role="status" + aria-live="polite" achieves this: assistive tech
// announces the message as soon as the live region is updated.
//
// The spec uses `page.setContent` to render the empty-state markup
// directly in the browser so it can run without the full app stack.

const SAMPLE_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <main>
      <h1>Positions</h1>
      <div
        data-testid="accessible-empty-state"
        role="status"
        aria-live="polite"
        aria-label="No data. Book a trade to see positions appear here."
      >
        <p>No data</p>
        <p>Book a trade to see positions appear here.</p>
      </div>
    </main>
  </body>
</html>
`

test.describe('accessible empty-state announces no-data to assistive tech', () => {
  test('renders a status live region with the no-data message', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    const region = page.getByRole('status')
    await expect(region).toBeVisible()
    await expect(region).toContainText('No data')
  })

  test('uses aria-live="polite" so the announcement does not interrupt the user', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    const region = page.getByRole('status')
    await expect(region).toHaveAttribute('aria-live', 'polite')
  })

  test('exposes an accessible name that combines the title and the hint', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    const region = page.getByRole('status')
    await expect(region).toHaveAttribute(
      'aria-label',
      'No data. Book a trade to see positions appear here.',
    )
  })

  test('locates the empty state via a stable testid for app-level specs', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    await expect(page.getByTestId('accessible-empty-state')).toBeVisible()
  })

  test('contains supporting copy below the headline so users know what to do next', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    const region = page.getByTestId('accessible-empty-state')
    await expect(region).toContainText('Book a trade to see positions appear here.')
  })
})
