import { test, expect, Page } from '@playwright/test'
import { mockAllApiRoutes, mockLimitsRoutes } from './fixtures'

async function goToRiskTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-risk').click()
}

test.describe('Limits Panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders limits grouped by hierarchy level (FIRM through TRADER)', async ({ page }) => {
    await mockLimitsRoutes(page, {
      limits: [
        {
          id: 'l-firm',
          level: 'FIRM',
          entityId: 'firm-1',
          limitType: 'VAR',
          limitValue: '1000000',
          intradayLimit: null,
          overnightLimit: null,
          active: true,
        },
        {
          id: 'l-desk',
          level: 'DESK',
          entityId: 'desk-eq',
          limitType: 'NOTIONAL',
          limitValue: '5000000',
          intradayLimit: '4500000',
          overnightLimit: null,
          active: true,
        },
        {
          id: 'l-trader',
          level: 'TRADER',
          entityId: 'trader-a',
          limitType: 'POSITION',
          limitValue: '10000',
          intradayLimit: null,
          overnightLimit: null,
          active: true,
        },
      ],
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="limits-panel"]')

    await expect(page.getByTestId('limits-group-FIRM')).toBeVisible()
    await expect(page.getByTestId('limits-group-DESK')).toBeVisible()
    await expect(page.getByTestId('limits-group-TRADER')).toBeVisible()

    await expect(page.getByTestId('limits-row-l-firm')).toContainText('firm-1')
    await expect(page.getByTestId('limits-row-l-firm')).toContainText('VAR')
    await expect(page.getByTestId('limits-row-l-firm')).toContainText('1,000,000')
    await expect(page.getByTestId('limits-row-l-desk')).toContainText('4,500,000')
  })

  test('inactive limits render with the inactive indicator instead of the active dot', async ({ page }) => {
    await mockLimitsRoutes(page, {
      limits: [
        {
          id: 'l-active',
          level: 'FIRM',
          entityId: 'firm-active',
          limitType: 'VAR',
          limitValue: '1000000',
          intradayLimit: null,
          overnightLimit: null,
          active: true,
        },
        {
          id: 'l-disabled',
          level: 'FIRM',
          entityId: 'firm-disabled',
          limitType: 'VAR',
          limitValue: '500000',
          intradayLimit: null,
          overnightLimit: null,
          active: false,
        },
      ],
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="limits-panel"]')

    const activeRow = page.getByTestId('limits-row-l-active')
    const disabledRow = page.getByTestId('limits-row-l-disabled')
    await expect(activeRow).toContainText('●')
    await expect(disabledRow).toContainText('○')
  })

  test('the level filter narrows the visible groups to the chosen hierarchy level', async ({ page }) => {
    await mockLimitsRoutes(page, {
      limits: [
        {
          id: 'l-firm',
          level: 'FIRM',
          entityId: 'firm-1',
          limitType: 'VAR',
          limitValue: '1000000',
          intradayLimit: null,
          overnightLimit: null,
          active: true,
        },
        {
          id: 'l-desk',
          level: 'DESK',
          entityId: 'desk-eq',
          limitType: 'NOTIONAL',
          limitValue: '5000000',
          intradayLimit: null,
          overnightLimit: null,
          active: true,
        },
      ],
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="limits-panel"]')

    await page.getByTestId('limits-level-filter').selectOption('DESK')

    await expect(page.getByTestId('limits-group-DESK')).toBeVisible()
    await expect(page.getByTestId('limits-group-FIRM')).toBeHidden()
  })

  test('shows the empty state when no limits are configured', async ({ page }) => {
    // Default fixture stub returns [] — no override needed.
    await goToRiskTab(page)
    await page.waitForSelector('text=No limits defined')

    await expect(page.getByText('No limits defined')).toBeVisible()
  })

  test('intraday and overnight cells render current + utilisation% when the server populates them', async ({ page }) => {
    // Trader-review P0 — the limits screen used to leave intraday /
    // overnight cells as bare ceilings (or em-dash when the ceiling was
    // null), so the trader had no signal for how close to the wall each
    // book was. With `current` + `utilisationPct` on the response, the
    // cells now show e.g. "$32,000,000 (71.1%)".
    await mockLimitsRoutes(page, {
      limits: [
        {
          id: 'l-book-eq-growth',
          level: 'BOOK',
          entityId: 'equity-growth',
          limitType: 'NOTIONAL',
          limitValue: '40000000',
          intradayLimit: '45000000',
          overnightLimit: '38000000',
          current: '32000000',
          utilisationPct: 71.1,
          active: true,
        },
        {
          id: 'l-firm-var',
          level: 'FIRM',
          entityId: 'firm-1',
          limitType: 'VAR',
          limitValue: '5000000',
          intradayLimit: '5000000',
          overnightLimit: null,
          current: null,
          utilisationPct: null,
          active: true,
        },
      ],
    })

    await goToRiskTab(page)
    await page.waitForSelector('[data-testid="limits-panel"]')

    // Utilisation-populated row — both cells show "$XX,XXX,XXX (NN.N%)".
    const intradayCell = page.getByTestId('limits-cell-intraday-l-book-eq-growth')
    await expect(intradayCell).toContainText('32,000,000')
    await expect(intradayCell).toContainText('71.1%')
    const overnightCell = page.getByTestId('limits-cell-overnight-l-book-eq-growth')
    await expect(overnightCell).toContainText('32,000,000')
    await expect(overnightCell).toContainText('71.1%')

    // VAR row — server can't compute utilisation, UI falls back to bare
    // ceiling (intraday) / em-dash (overnight is null).
    const varIntraday = page.getByTestId('limits-cell-intraday-l-firm-var')
    await expect(varIntraday).toContainText('5,000,000')
    await expect(varIntraday).not.toContainText('%')
    const varOvernight = page.getByTestId('limits-cell-overnight-l-firm-var')
    await expect(varOvernight).toContainText('—')
  })
})
