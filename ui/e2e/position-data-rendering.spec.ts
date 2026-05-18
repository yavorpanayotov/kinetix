import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

const WORKSPACE_KEY = 'kinetix:workspace'

// Reveal the PositionGrid "Details" columns (Quantity / Avg Cost / Market Price)
// before navigation so the tests below that assert detail values do not have
// to click the toggle every time. Risk-first defaults hide them.
async function preEnablePositionDetails(page: import('@playwright/test').Page) {
  await page.addInitScript(
    (key) => {
      localStorage.setItem(key, JSON.stringify({ showPositionDetails: true }))
    },
    WORKSPACE_KEY,
  )
}

test.describe('Position Data Rendering', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('displays all three test positions as separate rows', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    await expect(page.getByTestId('position-row-AAPL')).toBeVisible()
    await expect(page.getByTestId('position-row-GOOGL')).toBeVisible()
    await expect(page.getByTestId('position-row-EUR_USD')).toBeVisible()

    const rows = page.locator('tbody tr')
    await expect(rows).toHaveCount(3)
  })

  test('renders instrument name and asset class in correct columns', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    const aaplRow = page.getByTestId('position-row-AAPL')
    await expect(aaplRow.locator('td').nth(0)).toHaveText('port-1')
    await expect(aaplRow.locator('td').nth(1)).toHaveText('AAPL')
    await expect(aaplRow.locator('td').nth(2)).toHaveText('Apple Inc')
    await expect(aaplRow.locator('td').nth(3)).toHaveText('Stock')
    await expect(aaplRow.locator('td').nth(4)).toHaveText('EQUITY')

    const googlRow = page.getByTestId('position-row-GOOGL')
    await expect(googlRow.locator('td').nth(1)).toHaveText('GOOGL')
    await expect(googlRow.locator('td').nth(2)).toHaveText('Alphabet Inc')
    await expect(googlRow.locator('td').nth(4)).toHaveText('EQUITY')

    const eurRow = page.getByTestId('position-row-EUR_USD')
    await expect(eurRow.locator('td').nth(1)).toHaveText('EUR_USD')
    await expect(eurRow.locator('td').nth(4)).toHaveText('FX')
  })

  test('formats quantity without trailing zeros', async ({ page }) => {
    await preEnablePositionDetails(page)
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    const aaplQty = page.getByTestId('position-row-AAPL').locator('td').nth(5)
    await expect(aaplQty).toHaveText('100')

    const googlQty = page.getByTestId('position-row-GOOGL').locator('td').nth(5)
    await expect(googlQty).toHaveText('50')

    const eurQty = page.getByTestId('position-row-EUR_USD').locator('td').nth(5)
    await expect(eurQty).toHaveText('10,000')
  })

  test('formats monetary values with currency symbols', async ({ page }) => {
    await preEnablePositionDetails(page)
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    const aaplRow = page.getByTestId('position-row-AAPL')
    // Avg Cost
    await expect(aaplRow.locator('td').nth(6)).toHaveText('$150.00')
    // Market Price
    await expect(aaplRow.locator('td').nth(7)).toHaveText('$155.00')
    // Market Value
    await expect(aaplRow.locator('td').nth(8)).toHaveText('$15,500.00')

    const googlRow = page.getByTestId('position-row-GOOGL')
    await expect(googlRow.locator('td').nth(8)).toHaveText('$142,500.00')
  })

  test('displays P&L values with currency formatting', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="pnl-AAPL"]')

    await expect(page.getByTestId('pnl-AAPL')).toHaveText('$500.00')
    await expect(page.getByTestId('pnl-GOOGL')).toHaveText('$2,500.00')
    await expect(page.getByTestId('pnl-EUR_USD')).toHaveText('$50.00')
  })

  test('shows all column headers when no columns hidden and Details revealed', async ({ page }) => {
    // Clear any stored column visibility preferences and enable Details so all
    // PositionGrid columns are visible at once.
    await page.addInitScript(() => {
      localStorage.removeItem('kinetix:column-visibility')
      localStorage.setItem(
        'kinetix:workspace',
        JSON.stringify({ showPositionDetails: true }),
      )
    })

    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    const headers = ['Book', 'Instrument', 'Name', 'Type', 'Asset Class', 'Quantity', 'Avg Cost', 'Market Price', 'Market Value', 'Unrealized P&L']
    for (const header of headers) {
      await expect(page.locator('th', { hasText: header })).toBeVisible()
    }
  })

  test('does not show risk column headers when no risk data available', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    await expect(page.getByTestId('header-group-risk')).not.toBeVisible()
    await expect(page.getByTestId('sort-delta')).not.toBeVisible()
    await expect(page.getByTestId('sort-gamma')).not.toBeVisible()
    await expect(page.getByTestId('sort-vega')).not.toBeVisible()
    await expect(page.getByTestId('sort-var-pct')).not.toBeVisible()
  })

  test('instrument search filters positions by instrumentId', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="instrument-search"]')

    await page.getByTestId('instrument-search').fill('AAPL')

    await expect(page.getByTestId('position-row-AAPL')).toBeVisible()
    await expect(page.getByTestId('position-row-GOOGL')).not.toBeVisible()
    await expect(page.getByTestId('position-row-EUR_USD')).not.toBeVisible()
  })

  test('instrument search filters positions by displayName', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="instrument-search"]')

    await page.getByTestId('instrument-search').fill('Alphabet')

    await expect(page.getByTestId('position-row-GOOGL')).toBeVisible()
    await expect(page.getByTestId('position-row-AAPL')).not.toBeVisible()
    await expect(page.getByTestId('position-row-EUR_USD')).not.toBeVisible()
  })

  test('clearing instrument search restores all positions', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="instrument-search"]')

    await page.getByTestId('instrument-search').fill('AAPL')
    await expect(page.getByTestId('position-row-GOOGL')).not.toBeVisible()

    await page.getByTestId('instrument-search').clear()
    await expect(page.getByTestId('position-row-AAPL')).toBeVisible()
    await expect(page.getByTestId('position-row-GOOGL')).toBeVisible()
    await expect(page.getByTestId('position-row-EUR_USD')).toBeVisible()
  })

  test('displays summary cards with correct totals', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="book-summary"]')

    const summary = page.getByTestId('book-summary')

    // Positions count
    await expect(summary.locator('text=Positions').locator('..')).toContainText('3')

    // Market Value = 15500 + 142500 + 10850 = 168850 (compact format)
    await expect(summary).toContainText('$168.8K')

    // P&L = 500 + 2500 + 50 = 3050 (compact format)
    await expect(summary).toContainText('$3K')
  })
})
