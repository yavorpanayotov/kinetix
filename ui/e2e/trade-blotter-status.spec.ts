import { test, expect } from '@playwright/test'
import { mockAllApiRoutes, mockTradesOverride, type TradeFixture } from './fixtures'

/**
 * Trader-review P2 §21: the Trade Blotter must distinguish FIX-style fill
 * states (WORKING / FILLED / PARTIAL / CANCELLED / REJECTED) and surface
 * `qtyFilled` / `qtyOpen` per row. This spec asserts that:
 *   - mixed-status rows render each respective fill state in the badge
 *     (text + colour family),
 *   - `qtyFilled` and `qtyOpen` are visible per row,
 *   - rows lacking an explicit upstream projection fall back to the
 *     derived FILLED / CANCELLED defaults driven by lifecycle status.
 */

const BOOK_ID = 'port-1'

const MIXED_TRADES: TradeFixture[] = [
  {
    tradeId: 't-working',
    bookId: BOOK_ID,
    instrumentId: 'AAPL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '1000',
    price: { amount: '150.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:00:00Z',
    status: 'LIVE',
    fillStatus: 'WORKING',
    qtyFilled: '0',
    qtyOpen: '1000',
  },
  {
    tradeId: 't-filled',
    bookId: BOOK_ID,
    instrumentId: 'MSFT',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '200',
    price: { amount: '300.00', currency: 'USD' },
    tradedAt: '2025-01-15T10:30:00Z',
    status: 'LIVE',
    // No explicit fillStatus — derived from LIVE → FILLED.
  },
  {
    tradeId: 't-partial',
    bookId: BOOK_ID,
    instrumentId: 'GOOGL',
    assetClass: 'EQUITY',
    side: 'BUY',
    quantity: '1000',
    price: { amount: '2800.00', currency: 'USD' },
    tradedAt: '2025-01-15T11:00:00Z',
    status: 'LIVE',
    fillStatus: 'PARTIAL',
    qtyFilled: '750',
    qtyOpen: '250',
  },
  {
    tradeId: 't-cancelled',
    bookId: BOOK_ID,
    instrumentId: 'NFLX',
    assetClass: 'EQUITY',
    side: 'SELL',
    quantity: '75',
    price: { amount: '450.00', currency: 'USD' },
    tradedAt: '2025-01-15T11:30:00Z',
    status: 'CANCELLED',
    // No explicit fillStatus — derived from CANCELLED → CANCELLED.
  },
  {
    tradeId: 't-rejected',
    bookId: BOOK_ID,
    instrumentId: 'TSLA',
    assetClass: 'EQUITY',
    side: 'SELL',
    quantity: '300',
    price: { amount: '200.00', currency: 'USD' },
    tradedAt: '2025-01-15T12:00:00Z',
    status: 'LIVE',
    fillStatus: 'REJECTED',
    qtyFilled: '0',
    qtyOpen: '0',
  },
]

async function goToBlotter(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.waitForSelector('[data-testid^="trade-row-"]')
}

test.describe('Trade Blotter — FIX-style fill states', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockTradesOverride(page, MIXED_TRADES)
  })

  test('renders mixed-status rows with the correct fillStatus text per row', async ({ page }) => {
    await goToBlotter(page)

    await expect(page.getByTestId('trade-status-t-working')).toHaveText('WORKING')
    await expect(page.getByTestId('trade-status-t-filled')).toHaveText('FILLED')
    await expect(page.getByTestId('trade-status-t-partial')).toHaveText('PARTIAL')
    await expect(page.getByTestId('trade-status-t-cancelled')).toHaveText('CANCELLED')
    await expect(page.getByTestId('trade-status-t-rejected')).toHaveText('REJECTED')
  })

  test('renders each fill state with its corresponding colour family', async ({ page }) => {
    await goToBlotter(page)

    // WORKING — neutral grey (order is in the market but not filled yet).
    const workingClass = await page.getByTestId('trade-status-t-working').getAttribute('class')
    expect(workingClass).toMatch(/slate|gray|grey/i)

    // FILLED — green (the trade has cleared and is on the book).
    const filledClass = await page.getByTestId('trade-status-t-filled').getAttribute('class')
    expect(filledClass).toMatch(/green/)

    // PARTIAL — amber (in-flight, attention-worthy).
    const partialClass = await page.getByTestId('trade-status-t-partial').getAttribute('class')
    expect(partialClass).toMatch(/amber|yellow/)

    // CANCELLED and REJECTED — red (terminal, no exposure).
    const cancelledClass = await page.getByTestId('trade-status-t-cancelled').getAttribute('class')
    expect(cancelledClass).toMatch(/red/)
    const rejectedClass = await page.getByTestId('trade-status-t-rejected').getAttribute('class')
    expect(rejectedClass).toMatch(/red/)
  })

  test('renders qtyFilled and qtyOpen per row, forwarding upstream values verbatim when present', async ({ page }) => {
    await goToBlotter(page)

    // WORKING — 0 filled / 1,000 open
    await expect(page.getByTestId('trade-qty-filled-t-working')).toHaveText('0')
    await expect(page.getByTestId('trade-qty-open-t-working')).toHaveText('1,000')

    // PARTIAL — 750 filled / 250 open
    await expect(page.getByTestId('trade-qty-filled-t-partial')).toHaveText('750')
    await expect(page.getByTestId('trade-qty-open-t-partial')).toHaveText('250')

    // REJECTED — 0 filled / 0 open
    await expect(page.getByTestId('trade-qty-filled-t-rejected')).toHaveText('0')
    await expect(page.getByTestId('trade-qty-open-t-rejected')).toHaveText('0')
  })

  test('derives qtyFilled = quantity / qtyOpen = 0 for booked LIVE rows without explicit projection', async ({ page }) => {
    await goToBlotter(page)

    // FILLED (derived from LIVE) — qtyFilled = quantity (200), qtyOpen = 0
    await expect(page.getByTestId('trade-qty-filled-t-filled')).toHaveText('200')
    await expect(page.getByTestId('trade-qty-open-t-filled')).toHaveText('0')
  })

  test('derives qtyFilled = 0 / qtyOpen = 0 for CANCELLED rows without explicit projection', async ({ page }) => {
    await goToBlotter(page)

    await expect(page.getByTestId('trade-qty-filled-t-cancelled')).toHaveText('0')
    await expect(page.getByTestId('trade-qty-open-t-cancelled')).toHaveText('0')
  })

  test('shows the Filled and Open column headers in the blotter table', async ({ page }) => {
    await goToBlotter(page)

    await expect(page.getByRole('columnheader', { name: 'Filled' })).toBeVisible()
    await expect(page.getByRole('columnheader', { name: 'Open' })).toBeVisible()
  })
})
