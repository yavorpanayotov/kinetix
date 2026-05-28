import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockEodTimelineRoutes,
  TEST_EOD_TIMELINE_RESPONSE,
  TEST_EOD_TIMELINE_EMPTY,
  TEST_EOD_TIMELINE_LIVE_SEED_RESPONSE,
} from './fixtures'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function goToEodTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-eod').click()
  await page.waitForSelector('[data-testid="eod-timeline-tab"]')
}

// ---------------------------------------------------------------------------
// Tab loading
// ---------------------------------------------------------------------------

test.describe('EOD History tab — loading and empty states', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders EOD History tab and loads entries', async ({ page }) => {
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
    await goToEodTab(page)

    // Tab itself visible
    await expect(page.getByTestId('tab-eod')).toBeVisible()
    await expect(page.getByTestId('eod-timeline-tab')).toBeVisible()

    // Date range picker and trend chart visible
    await expect(page.getByTestId('eod-date-range-picker')).toBeVisible()
    await expect(page.getByTestId('eod-trend-chart')).toBeVisible()

    // Grid should have rows for the fixture entries
    await expect(page.getByTestId('eod-daily-grid')).toBeVisible()
    await expect(page.getByTestId('eod-row-2026-03-13')).toBeVisible()
    await expect(page.getByTestId('eod-row-2026-03-12')).toBeVisible()
  })

  test('shows empty state when no entries returned', async ({ page }) => {
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_EMPTY)
    await goToEodTab(page)

    await expect(page.getByTestId('eod-chart-empty')).toBeVisible()
    // Tab-level empty state (EodTimelineTab renders EmptyState instead of EodDailyGrid)
    await expect(page.getByRole('heading', { name: 'No EOD history for this period' })).toBeVisible()
  })

  test('shows error state with retry button on API failure', async ({ page }) => {
    await mockEodTimelineRoutes(page, {}, 500)
    await goToEodTab(page)

    await expect(page.getByTestId('eod-error-banner')).toBeVisible()
    await expect(page.getByTestId('eod-retry-btn')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// MISSING badge
// ---------------------------------------------------------------------------

test.describe('EOD History tab — MISSING badge', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows MISSING badge for dates with no official EOD', async ({ page }) => {
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
    await goToEodTab(page)

    // 2026-03-10 has null varValue in the fixture
    await expect(page.getByTestId('missing-badge-2026-03-10')).toBeVisible()
    await expect(page.getByTestId('missing-badge-2026-03-10')).toContainText('MISSING')
  })

  test('promoted rows do not show MISSING badge', async ({ page }) => {
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
    await goToEodTab(page)

    await expect(page.getByTestId('missing-badge-2026-03-13')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// DoD colour coding
// ---------------------------------------------------------------------------

test.describe('EOD History tab — day-over-day colour coding', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
  })

  test('colour codes day-over-day VaR changes', async ({ page }) => {
    await goToEodTab(page)

    // 2026-03-13 has varChange=+8000 → red cell with ▲
    const row13 = page.getByTestId('eod-row-2026-03-13')
    await expect(row13).toBeVisible()
    // The row should contain a DoD value with upward arrow
    await expect(row13).toContainText('▲')

    // 2026-03-11 has varChange=-5000 → green cell with ▼
    const row11 = page.getByTestId('eod-row-2026-03-11')
    await expect(row11).toBeVisible()
    await expect(row11).toContainText('▼')
  })
})

// ---------------------------------------------------------------------------
// Drill-down panel
// ---------------------------------------------------------------------------

test.describe('EOD History tab — drill-down panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
  })

  test('clicking a grid row opens the drill-down panel', async ({ page }) => {
    await goToEodTab(page)

    await page.getByTestId('eod-row-2026-03-13').click()

    await expect(page.getByTestId('eod-drill-panel')).toBeVisible()
    await expect(page.getByTestId('eod-drill-panel')).toContainText('2026-03-13')
  })

  test('clicking the close button closes the drill-down panel', async ({ page }) => {
    await goToEodTab(page)

    await page.getByTestId('eod-row-2026-03-13').click()
    await expect(page.getByTestId('eod-drill-panel')).toBeVisible()

    await page.getByTestId('eod-drill-close').click()
    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()
  })

  test('clicking a MISSING row does not open the drill-down panel', async ({ page }) => {
    await goToEodTab(page)

    await page.getByTestId('eod-row-2026-03-10').click()

    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()
  })

  test('clicking the backdrop closes the drill-down panel', async ({ page }) => {
    await goToEodTab(page)

    await page.getByTestId('eod-row-2026-03-13').click()
    await expect(page.getByTestId('eod-drill-panel')).toBeVisible()

    await page.getByTestId('eod-drill-backdrop').click()
    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Date range picker
// ---------------------------------------------------------------------------

test.describe('EOD History tab — date range picker', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
  })

  test('date range preset buttons are visible', async ({ page }) => {
    await goToEodTab(page)

    await expect(page.getByTestId('eod-preset-1W')).toBeVisible()
    await expect(page.getByTestId('eod-preset-1M')).toBeVisible()
    await expect(page.getByTestId('eod-preset-3M')).toBeVisible()
    await expect(page.getByTestId('eod-preset-YTD')).toBeVisible()
    await expect(page.getByTestId('eod-preset-Custom')).toBeVisible()
  })

  test('date range preset buttons re-fetch with correct parameters', async ({ page }) => {
    const requests: string[] = []
    await page.on('request', (req) => {
      if (req.url().includes('eod-timeline')) {
        requests.push(req.url())
      }
    })

    await goToEodTab(page)

    // Clicking 1W should trigger a new fetch
    await page.getByTestId('eod-preset-1W').click()

    // Wait briefly for fetch
    await page.waitForTimeout(200)

    // Should have made at least 2 requests: initial mount + after preset click
    expect(requests.length).toBeGreaterThanOrEqual(2)
    // The second request should have a from param ~7 days back
    expect(requests[requests.length - 1]).toContain('from=')
  })

  test('custom date range inputs appear when Custom is clicked', async ({ page }) => {
    await goToEodTab(page)

    await page.getByTestId('eod-preset-Custom').click()

    await expect(page.getByTestId('eod-custom-range-inputs')).toBeVisible()
    await expect(page.getByTestId('eod-custom-from')).toBeVisible()
    await expect(page.getByTestId('eod-custom-to')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// Grid sorting
// ---------------------------------------------------------------------------

test.describe('EOD History tab — grid sorting', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
  })

  test('grid is sortable by clicking column headers', async ({ page }) => {
    await goToEodTab(page)

    // Default sort is date descending — most recent at top
    const rows = page.locator('[data-testid^="eod-row-"]')
    const firstRowBefore = await rows.first().getAttribute('data-testid')
    expect(firstRowBefore).toBe('eod-row-2026-03-13')

    // Click date header to sort ascending
    await page.getByTestId('eod-sort-valuationDate').click()

    // Allow state update
    await page.waitForTimeout(100)

    const firstRowAfter = await rows.first().getAttribute('data-testid')
    expect(firstRowAfter).not.toBe('eod-row-2026-03-13')
  })
})

// ---------------------------------------------------------------------------
// Keyboard navigation
// ---------------------------------------------------------------------------

test.describe('EOD History tab — keyboard navigation', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_RESPONSE)
  })

  test('keyboard navigation: Enter on focused row opens drill panel', async ({ page }) => {
    await goToEodTab(page)

    // Focus the first non-missing row via keyboard
    const row = page.getByTestId('eod-row-2026-03-13')
    await row.focus()
    await row.press('Enter')

    await expect(page.getByTestId('eod-drill-panel')).toBeVisible()
  })

  test('Escape key closes the drill-down panel', async ({ page }) => {
    await goToEodTab(page)

    await page.getByTestId('eod-row-2026-03-13').click()
    await expect(page.getByTestId('eod-drill-panel')).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(page.getByTestId('eod-drill-panel')).not.toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// PV column — regression guard for the trader-review finding that PV was `—`
// on every promoted row in the live demo. The fixture used here mirrors the
// shape the live SEED produces; the assertion catches any future regression
// where PV stops flowing from valuation_jobs.pv_value through the EOD timeline
// projection into the grid.
// ---------------------------------------------------------------------------

test.describe('EOD History tab — PV column', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders the PV column header', async ({ page }) => {
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_LIVE_SEED_RESPONSE)
    await goToEodTab(page)

    await expect(page.getByTestId('eod-sort-pvValue')).toBeVisible()
    await expect(page.getByTestId('eod-sort-pvValue')).toContainText('PV')
  })

  test('shows a non-em-dash PV value formatted as currency on every promoted row', async ({ page }) => {
    await mockEodTimelineRoutes(page, TEST_EOD_TIMELINE_LIVE_SEED_RESPONSE)
    await goToEodTab(page)

    // Every promoted row in the live-seed fixture must surface PV as a
    // currency-formatted string (e.g. `$5,200,000.00`), not as an em-dash or
    // (per the live demo bug) the literal `—` escape rendered as text.
    for (const entry of TEST_EOD_TIMELINE_LIVE_SEED_RESPONSE.entries) {
      if (entry.promotedBy === null) continue // skip MISSING rows
      const pvCell = page.getByTestId(`eod-pv-cell-${entry.valuationDate}`)
      await expect(pvCell).toBeVisible()
      const cellText = (await pvCell.innerText()).trim()
      // Currency-formatted output from `Intl.NumberFormat('en-US', { style: 'currency' })`.
      expect(
        cellText,
        `PV cell for ${entry.valuationDate} should be currency, got: ${JSON.stringify(cellText)}`,
      ).toMatch(/^\$\d{1,3}(,\d{3})*\.\d{2}$/)
      // Belt-and-braces: explicitly NOT the em-dash sentinel and not the
      // broken literal escape that the bug surfaced in the live demo.
      expect(cellText).not.toBe('—')
      expect(cellText).not.toBe('\\u2014')
    }
  })
})
