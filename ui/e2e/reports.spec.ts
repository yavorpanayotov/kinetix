import { test, expect } from '@playwright/test'
import { mockAllApiRoutes, TEST_REPORT_TEMPLATES, TEST_REPORT_OUTPUT } from './fixtures'

test.describe('Reports Tab', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await page.goto('/')
    await page.getByTestId('tab-reports').click()
  })

  test('renders the Reports tab and shows template selector', async ({ page }) => {
    await expect(page.getByTestId('report-template-select')).toBeVisible()
    await expect(page.getByTestId('report-book-input')).toBeVisible()
    await expect(page.getByTestId('report-generate-button')).toBeVisible()
  })

  test('template selector contains all available templates', async ({ page }) => {
    const select = page.getByTestId('report-template-select')
    await expect(select).toBeVisible()

    for (const template of TEST_REPORT_TEMPLATES) {
      await expect(select.locator(`option[value="${template.templateId}"]`)).toHaveText(
        template.name,
      )
    }
  })

  test('Generate button is disabled when no template is selected', async ({ page }) => {
    const generateButton = page.getByTestId('report-generate-button')
    await expect(generateButton).toBeDisabled()

    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await expect(generateButton).toBeEnabled()
  })

  test('Generate button is disabled when book ID is cleared', async ({ page }) => {
    const generateButton = page.getByTestId('report-generate-button')

    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').clear()
    await expect(generateButton).toBeDisabled()

    await page.getByTestId('report-book-input').fill('BOOK-1')
    await expect(generateButton).toBeEnabled()
  })

  test('clicking Generate shows report output panel with row count', async ({ page }) => {
    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')
    await page.getByTestId('report-generate-button').click()

    await expect(page.getByTestId('report-output-panel')).toBeVisible()
    await expect(page.getByTestId('report-output-meta')).toContainText(
      `${TEST_REPORT_OUTPUT.rowCount} rows`,
    )
  })

  test('Download CSV button appears after report generation', async ({ page }) => {
    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')
    await page.getByTestId('report-generate-button').click()

    await expect(page.getByTestId('report-download-csv-button')).toBeVisible()
  })

  test('clicking Download CSV triggers a file download', async ({ page }) => {
    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')
    await page.getByTestId('report-generate-button').click()

    await expect(page.getByTestId('report-download-csv-button')).toBeVisible()

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('report-download-csv-button').click()
    const download = await downloadPromise

    expect(download.suggestedFilename()).toMatch(/^report-.*\.csv$/)
  })

  test('generated report appears in report history', async ({ page }) => {
    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')
    await page.getByTestId('report-generate-button').click()

    await expect(page.getByTestId('report-history-panel')).toBeVisible()
    await expect(
      page.getByTestId(`report-history-item-${TEST_REPORT_OUTPUT.outputId}`),
    ).toBeVisible()
  })

  test('optional date field is accepted and sent with the request', async ({ page }) => {
    let requestBody: Record<string, unknown> = {}

    await page.route('**/api/v1/reports/generate', async (route) => {
      const body = route.request().postDataJSON() as Record<string, unknown>
      requestBody = body
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(TEST_REPORT_OUTPUT),
      })
    })

    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')
    await page.getByTestId('report-date-input').fill('2025-01-15')
    await page.getByTestId('report-generate-button').click()

    await expect(page.getByTestId('report-output-panel')).toBeVisible()
    expect(requestBody['date']).toBe('2025-01-15')
  })

  test('shows error message when report generation fails', async ({ page }) => {
    await page.unroute('**/api/v1/reports/generate')
    await page.route('**/api/v1/reports/generate', (route) => {
      route.fulfill({
        status: 422,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Template not found' }),
      })
    })

    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')
    await page.getByTestId('report-generate-button').click()

    await expect(page.getByTestId('report-generate-error')).toBeVisible()
    await expect(page.getByTestId('report-generate-error')).toContainText(
      'Failed to generate report',
    )
  })

  // Plan §4.3 — when the gateway returns its canonical 500 with
  // `{error:'upstream_error', message:'Report generation failed'}` (the
  // exact shape the live deploy emits for the §4.1 Reports 500), the
  // toast must render the upstream `message` field, not just the HTTP
  // status. This pins the M4 contract end-to-end through the browser.
  test('renders the upstream gateway message in the toast on a 500 upstream_error', async ({
    page,
  }) => {
    await page.unroute('**/api/v1/reports/generate')
    await page.route('**/api/v1/reports/generate', (route) => {
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          error: 'upstream_error',
          message: 'Report generation failed',
        }),
      })
    })

    await page.getByTestId('report-template-select').selectOption('tpl-risk-summary')
    await page.getByTestId('report-book-input').fill('BOOK-1')
    await page.getByTestId('report-generate-button').click()

    const toast = page.getByTestId('report-generate-error')
    await expect(toast).toBeVisible()
    // The upstream message field MUST appear verbatim — the operator
    // needs the actual cause, not a generic "500".
    await expect(toast).toContainText('Report generation failed')
    // a11y: the toast is announced as an alert.
    await expect(toast).toHaveAttribute('role', 'alert')
  })
})
