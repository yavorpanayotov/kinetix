import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'
import type { Page, Route } from '@playwright/test'

/**
 * Checkbox 7.2 of docs/plans/ui-fix-v1.md — proves that, given the demo seed
 * data produced by the position-service / demo-orchestrator changes in
 * checkbox 7.1, the Trades > Reconciliation and Trades > Execution Cost
 * subtabs render non-empty grids rather than their empty states.
 *
 * The two GET endpoints are mocked with `page.route` (pattern from
 * fixtures.ts) returning seeded rows that mirror the shape the simulator
 * now writes.
 */

const SEEDED_EXECUTION_COSTS = [
  {
    orderId: 'demo-ord-001',
    bookId: 'port-1',
    instrumentId: 'JNJ',
    completedAt: '2026-05-18T12:00:00Z',
    arrivalPrice: '100.00',
    averageFillPrice: '100.12',
    side: 'BUY',
    totalQty: '500',
    slippageBps: '12.0000',
    marketImpactBps: '4.5000',
    timingCostBps: '1.2000',
    totalCostBps: '17.7000',
  },
  {
    orderId: 'demo-ord-002',
    bookId: 'port-1',
    instrumentId: 'KO',
    completedAt: '2026-05-18T12:05:00Z',
    arrivalPrice: '100.00',
    averageFillPrice: '99.91',
    side: 'SELL',
    totalQty: '320',
    slippageBps: '-9.0000',
    marketImpactBps: null,
    timingCostBps: null,
    totalCostBps: '-9.0000',
  },
]

const SEEDED_RECONCILIATIONS = [
  {
    reconciliationDate: '2026-05-18',
    bookId: 'port-1',
    status: 'BREAKS_FOUND',
    totalPositions: 3,
    matchedCount: 2,
    breakCount: 1,
    breaks: [
      {
        instrumentId: 'JNJ',
        internalQty: '500',
        primeBrokerQty: '490',
        breakQty: '10',
        breakNotional: '1000.00',
        severity: 'NORMAL',
        status: 'OPEN',
      },
    ],
    reconciledAt: '2026-05-18T18:00:00Z',
  },
]

async function mockSeededExecutionRoutes(page: Page) {
  await mockAllApiRoutes(page)
  // Override the default empty-list stubs with the seeded demo data.
  await page.unroute('**/api/v1/execution/cost/**')
  await page.route('**/api/v1/execution/cost/**', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(SEEDED_EXECUTION_COSTS),
    })
  })
  await page.unroute('**/api/v1/execution/reconciliation/**')
  await page.route('**/api/v1/execution/reconciliation/**', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(SEEDED_RECONCILIATIONS),
    })
  })
}

test.describe('Trades subtabs render seeded demo data', () => {
  test('Execution Cost subtab renders a non-empty grid', async ({ page }) => {
    await mockSeededExecutionRoutes(page)
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.getByTestId('trades-subtab-cost').click()

    // The grid renders with at least one seeded row — not the empty state.
    await expect(page.getByTestId('execution-cost-table')).toBeVisible()
    const rows = page.locator('[data-testid^="cost-row-"]')
    await expect(rows).toHaveCount(SEEDED_EXECUTION_COSTS.length)
    await expect(page.getByTestId('cost-row-demo-ord-001')).toBeVisible()
    await expect(page.getByTestId('cost-row-demo-ord-002')).toBeVisible()

    // The empty state must NOT be shown.
    await expect(page.getByText(/no execution cost data/i)).toHaveCount(0)
  })

  test('Reconciliation subtab renders a non-empty grid with a break row', async ({ page }) => {
    await mockSeededExecutionRoutes(page)
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.getByTestId('trades-subtab-reconciliation').click()

    // The reconciliation grid renders the seeded row — not the empty state.
    const reconRows = page.locator('[data-testid^="recon-row-"]')
    await expect(reconRows).toHaveCount(SEEDED_RECONCILIATIONS.length)
    await expect(page.getByTestId('recon-row-2026-05-18')).toBeVisible()
    await expect(page.getByText('BREAKS_FOUND')).toBeVisible()

    // The seeded reconciliation carries at least one break detail row.
    await expect(page.getByTestId('break-row-JNJ')).toBeVisible()

    // The empty state must NOT be shown.
    await expect(page.getByText(/no reconciliation data/i)).toHaveCount(0)
  })
})
