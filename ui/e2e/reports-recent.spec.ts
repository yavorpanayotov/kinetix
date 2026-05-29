import { test, expect } from '@playwright/test'
import type { Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Trader-review P2 #24 — Reports tab needs a "Recent Reports" panel
 * listing the last N reports (timestamp / user / status / download link).
 *
 * Status pills cover RUNNING / COMPLETE / FAILED so a trader can tell at
 * a glance which reports are queued, finished, or broken. Reverse-chrono
 * order — newest first.
 *
 * Endpoint contract pinned by:
 *   gateway RecentReportsAcceptanceTest (GET /api/v1/reports/recent)
 */
const SAMPLE_RECENT_REPORTS = [
  {
    outputId: 'out-3',
    templateId: 'tpl-risk-summary',
    timestamp: '2026-05-28T10:30:00Z',
    user: 'trader1',
    status: 'COMPLETE',
    downloadUrl: '/api/v1/reports/out-3/csv',
    rowCount: 42,
  },
  {
    outputId: 'out-2',
    templateId: 'tpl-stress-summary',
    timestamp: '2026-05-28T09:15:00Z',
    user: 'trader2',
    status: 'RUNNING',
    downloadUrl: '/api/v1/reports/out-2/csv',
    rowCount: 0,
  },
  {
    outputId: 'out-1',
    templateId: 'tpl-pnl-attribution',
    timestamp: '2026-05-27T17:00:00Z',
    user: 'riskops',
    status: 'FAILED',
    downloadUrl: '/api/v1/reports/out-1/csv',
    rowCount: 0,
  },
]

test.describe('Recent Reports panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    // Override the recent-reports endpoint with a populated list. Must be
    // registered after mockAllApiRoutes so it takes priority over any
    // wildcard /api/v1/reports/* handler.
    await page.route('**/api/v1/reports/recent*', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(SAMPLE_RECENT_REPORTS),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-reports').click()
  })

  test('renders the Recent Reports panel with one row per report', async ({ page }) => {
    const panel = page.getByTestId('recent-reports-panel')
    await expect(panel).toBeVisible()

    for (const row of SAMPLE_RECENT_REPORTS) {
      await expect(page.getByTestId(`recent-report-row-${row.outputId}`)).toBeVisible()
    }
  })

  test('rows are rendered newest-first (reverse chronological)', async ({ page }) => {
    const rows = page.locator('[data-testid^="recent-report-row-"]')
    await expect(rows).toHaveCount(SAMPLE_RECENT_REPORTS.length)

    const firstRow = rows.first()
    await expect(firstRow).toHaveAttribute('data-testid', 'recent-report-row-out-3')
    const lastRow = rows.last()
    await expect(lastRow).toHaveAttribute('data-testid', 'recent-report-row-out-1')
  })

  test('renders status pills for COMPLETE, RUNNING, FAILED', async ({ page }) => {
    const completePill = page.getByTestId('recent-report-status-out-3')
    await expect(completePill).toBeVisible()
    await expect(completePill).toHaveText(/COMPLETE/i)

    const runningPill = page.getByTestId('recent-report-status-out-2')
    await expect(runningPill).toBeVisible()
    await expect(runningPill).toHaveText(/RUNNING/i)

    const failedPill = page.getByTestId('recent-report-status-out-1')
    await expect(failedPill).toBeVisible()
    await expect(failedPill).toHaveText(/FAILED/i)
  })

  test('each row shows timestamp and requesting user', async ({ page }) => {
    const row = page.getByTestId('recent-report-row-out-3')
    await expect(row).toContainText('trader1')
    // Timestamp may be locale-formatted; assert the year/month at least.
    await expect(row).toContainText('2026')
  })

  test('COMPLETE rows expose a download link to the CSV endpoint', async ({ page }) => {
    const link = page.getByTestId('recent-report-download-out-3')
    await expect(link).toBeVisible()
    await expect(link).toHaveAttribute('href', '/api/v1/reports/out-3/csv')
  })

  test('renders an empty-state message when there are no recent reports', async ({ page }) => {
    await page.unroute('**/api/v1/reports/recent*')
    await page.route('**/api/v1/reports/recent*', (route: Route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    })

    await page.reload()
    await page.getByTestId('tab-reports').click()

    await expect(page.getByTestId('recent-reports-empty')).toBeVisible()
  })
})
