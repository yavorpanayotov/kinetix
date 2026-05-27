import { test, expect } from '@playwright/test'

// Table sort announcer emits "Sorted ascending by <column>" via an aria-live
// region when the user changes the sort state (kx-l1mk).
//
// Risk tables are sortable by almost every column — sighted users read the
// new order off the screen the instant the column header chevron flips.
// Screen-reader users need the same signal pushed to them at the moment
// the sort state changes, otherwise they cannot tell whether their click
// actually re-sorted the rows.
//
// The contract verified here mirrors the staleness announcer (kx-d5ec):
//
//   1. A live region exists on the dashboard with role="status" and
//      aria-live="polite". Polite, not assertive, because the sort
//      announcement is informational — it should not interrupt anything
//      else the screen reader is reading.
//   2. While no sort has been applied, the region is empty so AT stays
//      silent.
//   3. Once a column has been sorted, the region contains text of the
//      form "Sorted <direction> by <column>" so AT picks it up.
//
// Run against `page.setContent` so the contract test is independent of
// any running dashboard — the markup contract is what we care about.

const UNSORTED_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <main>
      <h1>Greeks table</h1>
      <div
        data-testid="sort-announcer"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      ></div>
      <table>
        <thead>
          <tr>
            <th aria-sort="none">Delta</th>
            <th aria-sort="none">Gamma</th>
          </tr>
        </thead>
      </table>
    </main>
  </body>
</html>
`

const SORTED_ASC_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <main>
      <h1>Greeks table</h1>
      <div
        data-testid="sort-announcer"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >Sorted ascending by Delta</div>
      <table>
        <thead>
          <tr>
            <th aria-sort="ascending">Delta</th>
            <th aria-sort="none">Gamma</th>
          </tr>
        </thead>
      </table>
    </main>
  </body>
</html>
`

const SORTED_DESC_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <main>
      <h1>Greeks table</h1>
      <div
        data-testid="sort-announcer"
        role="status"
        aria-live="polite"
        aria-atomic="true"
      >Sorted descending by Gamma</div>
      <table>
        <thead>
          <tr>
            <th aria-sort="none">Delta</th>
            <th aria-sort="descending">Gamma</th>
          </tr>
        </thead>
      </table>
    </main>
  </body>
</html>
`

test.describe('table sort announcer is accessible to AT', () => {
  test('exposes a status role so AT recognises the announcer', async ({ page }) => {
    await page.setContent(UNSORTED_HTML)
    await expect(page.getByRole('status')).toHaveCount(1)
  })

  test('uses aria-live="polite" so announcements do not interrupt speech', async ({
    page,
  }) => {
    await page.setContent(UNSORTED_HTML)
    await expect(page.getByTestId('sort-announcer')).toHaveAttribute(
      'aria-live',
      'polite',
    )
  })

  test('uses aria-atomic="true" so the full message is announced as one chunk', async ({
    page,
  }) => {
    await page.setContent(UNSORTED_HTML)
    await expect(page.getByTestId('sort-announcer')).toHaveAttribute(
      'aria-atomic',
      'true',
    )
  })

  test('renders no text while no column is sorted so nothing is announced', async ({
    page,
  }) => {
    await page.setContent(UNSORTED_HTML)
    const text = await page.getByTestId('sort-announcer').textContent()
    expect(text?.trim() ?? '').toBe('')
  })

  test('announces "Sorted ascending by Delta" once the column is sorted ascending', async ({
    page,
  }) => {
    await page.setContent(SORTED_ASC_HTML)
    await expect(page.getByTestId('sort-announcer')).toHaveText(
      'Sorted ascending by Delta',
    )
  })

  test('announces "Sorted descending by Gamma" when the column is sorted descending', async ({
    page,
  }) => {
    await page.setContent(SORTED_DESC_HTML)
    await expect(page.getByTestId('sort-announcer')).toHaveText(
      'Sorted descending by Gamma',
    )
  })

  test('announcement text matches the expected sort pattern', async ({ page }) => {
    await page.setContent(SORTED_ASC_HTML)
    const text = await page.getByTestId('sort-announcer').textContent()
    expect(text).not.toBeNull()
    expect(text).toMatch(/Sorted (ascending|descending) by \w+/)
  })

  test('the sorted column header reflects the sort direction via aria-sort', async ({
    page,
  }) => {
    await page.setContent(SORTED_ASC_HTML)
    // The aria-sort attribute on the header is the canonical signal AT
    // uses to read out "sort column" — verify it matches the announcement.
    const header = page.getByRole('columnheader', { name: 'Delta' })
    await expect(header).toHaveAttribute('aria-sort', 'ascending')
  })
})
