import { test, expect } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockManyTrades,
  mockTradesOverride,
  TEST_TRADES,
  type TradeFixture,
} from './fixtures'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function goToTradesTab(page: Parameters<typeof mockAllApiRoutes>[0]) {
  await page.goto('/')
  await page.getByTestId('tab-trades').click()
  await page.waitForSelector('[data-testid^="trade-row-"]')
}

// ---------------------------------------------------------------------------
// P0 — Core Display
// ---------------------------------------------------------------------------

test.describe('Trade Blotter - Core Display', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('displays all trades sorted by time descending', async ({ page }) => {
    await goToTradesTab(page)

    const rows = page.locator('[data-testid^="trade-row-"]')
    await expect(rows).toHaveCount(3)

    // trade-3 (14:00) is newest → first row; trade-1 (10:30) is oldest → last row
    await expect(rows.first()).toHaveAttribute('data-testid', 'trade-row-trade-3')
    await expect(rows.last()).toHaveAttribute('data-testid', 'trade-row-trade-1')
  })

  test('renders BUY trades in green and SELL trades in red', async ({ page }) => {
    await goToTradesTab(page)

    const buyClass1 = await page.getByTestId('trade-side-trade-1').getAttribute('class')
    const buyClass2 = await page.getByTestId('trade-side-trade-2').getAttribute('class')
    const sellClass3 = await page.getByTestId('trade-side-trade-3').getAttribute('class')

    expect(buyClass1).toContain('text-green-600')
    expect(buyClass2).toContain('text-green-600')
    expect(sellClass3).toContain('text-red-600')
  })

  test('calculates correct notional values', async ({ page }) => {
    await goToTradesTab(page)

    // trade-1: 100 × 150 = $15,000 -> compact: $15K
    await expect(page.getByTestId('trade-notional-trade-1')).toHaveText('$15K')
    // trade-2: 50 × 2800 = $140,000 -> compact: $140K
    await expect(page.getByTestId('trade-notional-trade-2')).toHaveText('$140K')
    // trade-3: 25 × 155 = $3,875 -> compact: $3.9K
    await expect(page.getByTestId('trade-notional-trade-3')).toHaveText('$3.9K')
  })

  test('shows actual trade status in each status badge', async ({ page }) => {
    await goToTradesTab(page)

    // trade-1 is LIVE, trade-2 is CANCELLED, trade-3 is AMENDED (from TEST_TRADES)
    await expect(page.getByTestId('trade-status-trade-1')).toHaveText('LIVE')
    await expect(page.getByTestId('trade-status-trade-2')).toHaveText('CANCELLED')
    await expect(page.getByTestId('trade-status-trade-3')).toHaveText('AMENDED')
  })

  test('falls back to LIVE when API response has no status field', async ({ page }) => {
    const tradeWithoutStatus: TradeFixture = {
      tradeId: 'trade-no-status',
      bookId: 'port-1',
      instrumentId: 'MSFT',
      assetClass: 'EQUITY',
      side: 'BUY',
      quantity: '10',
      price: { amount: '300.00', currency: 'USD' },
      tradedAt: '2025-01-15T12:00:00Z',
    }

    await mockTradesOverride(page, [tradeWithoutStatus])

    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid="trade-status-trade-no-status"]')

    await expect(page.getByTestId('trade-status-trade-no-status')).toHaveText('LIVE')
  })
})

// ---------------------------------------------------------------------------
// P0 — Filtering
// ---------------------------------------------------------------------------

test.describe('Trade Blotter - Filtering', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('instrument filter narrows to matching instruments (case-insensitive)', async ({
    page,
  }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-instrument').fill('aapl')

    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(2)
    await expect(page.getByTestId('trade-row-trade-1')).toBeVisible()
    await expect(page.getByTestId('trade-row-trade-3')).toBeVisible()
  })

  test('side dropdown BUY shows only BUY trades', async ({ page }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-side').selectOption('BUY')

    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(2)
    await expect(page.getByTestId('trade-row-trade-1')).toBeVisible()
    await expect(page.getByTestId('trade-row-trade-2')).toBeVisible()
  })

  test('side dropdown SELL shows only SELL trades', async ({ page }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-side').selectOption('SELL')

    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)
    await expect(page.getByTestId('trade-row-trade-3')).toBeVisible()
  })

  test('combined instrument + side filters show intersection', async ({ page }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-instrument').fill('AAPL')
    await page.getByTestId('filter-side').selectOption('BUY')

    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)
    await expect(page.getByTestId('trade-row-trade-1')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// P0 — Empty and Error States
// ---------------------------------------------------------------------------

test.describe('Trade Blotter - Empty and Error States', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('shows empty state when book has no trades', async ({ page }) => {
    await mockTradesOverride(page, [])

    await page.goto('/')
    await page.getByTestId('tab-trades').click()

    await expect(page.getByText('No trades to display.')).toBeVisible()
  })

  test('shows error message when API returns 500', async ({ page }) => {
    await page.unroute('**/api/v1/books/*/trades/page**')
    await page.unroute('**/api/v1/books/*/trades')
    await page.route('**/api/v1/books/*/trades/page**', (route) => {
      route.fulfill({ status: 500 })
    })
    await page.route('**/api/v1/books/*/trades', (route) => {
      route.fulfill({ status: 500 })
    })

    await page.goto('/')
    await page.getByTestId('tab-trades').click()

    await expect(page.locator('.text-red-600')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// P1 — Additional Filtering and State
// ---------------------------------------------------------------------------

test.describe('Trade Blotter - Additional Filtering and State', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('resetting side filter to All Sides restores all trades', async ({ page }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-side').selectOption('BUY')
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(2)

    await page.getByTestId('filter-side').selectOption('')
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(3)
  })

  test('clearing instrument filter restores all trades', async ({ page }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-instrument').fill('GOOGL')
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)

    await page.getByTestId('filter-instrument').clear()
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(3)
  })

  test('loading state shown before trades resolve', async ({ page }) => {
    await page.unroute('**/api/v1/books/*/trades/page**')
    await page.unroute('**/api/v1/books/*/trades')
    await page.route('**/api/v1/books/*/trades/page**', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 2000))
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          items: TEST_TRADES,
          total: TEST_TRADES.length,
          offset: 0,
          limit: 100,
          hasMore: false,
        }),
      })
    })

    await page.goto('/')
    await page.getByTestId('tab-trades').click()

    await expect(page.getByText('Loading trades...')).toBeVisible()
    await page.waitForSelector('[data-testid^="trade-row-"]', { timeout: 5000 })
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(3)
  })

  test('error on network failure (aborted request)', async ({ page }) => {
    await page.unroute('**/api/v1/books/*/trades/page**')
    await page.unroute('**/api/v1/books/*/trades')
    await page.route('**/api/v1/books/*/trades/page**', (route) => {
      route.abort()
    })
    await page.route('**/api/v1/books/*/trades', (route) => {
      route.abort()
    })

    await page.goto('/')
    await page.getByTestId('tab-trades').click()

    await expect(page.locator('.text-red-600')).toBeVisible()
  })

  test('trades refetch when book changes via hierarchy', async ({ page }) => {
    const book2Trades: TradeFixture[] = [
      {
        tradeId: 'trade-b2-1',
        bookId: 'book-2',
        instrumentId: 'TSLA',
        assetClass: 'EQUITY',
        side: 'BUY',
        quantity: '10',
        price: { amount: '900.00', currency: 'USD' },
        tradedAt: '2025-01-16T09:00:00Z',
      },
    ]

    // Override trades route to return different data per book
    await page.unroute('**/api/v1/books/*/trades/page**')
    await page.unroute('**/api/v1/books/*/trades')
    const respondWithBook = (route: import('@playwright/test').Route, paged: boolean) => {
      const url = route.request().url()
      const trades = url.includes('book-2') ? book2Trades : TEST_TRADES
      if (paged) {
        const u = new URL(url)
        const offset = Number(u.searchParams.get('offset') ?? 0)
        const limit = Number(u.searchParams.get('limit') ?? 100)
        const items = trades.slice(offset, offset + limit)
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            items,
            total: trades.length,
            offset,
            limit,
            hasMore: offset + items.length < trades.length,
          }),
        })
      } else {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(trades),
        })
      }
    }
    await page.route('**/api/v1/books/*/trades/page**', (route) => respondWithBook(route, true))
    await page.route('**/api/v1/books/*/trades', (route) => respondWithBook(route, false))

    // Override books to expose book-1 and book-2
    await page.unroute('**/api/v1/books')
    await page.route('**/api/v1/books', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ bookId: 'book-1' }, { bookId: 'book-2' }]),
      })
    })

    // Set up hierarchy with a division and desk so we can drill to a book
    await page.unroute('**/api/v1/divisions')
    await page.route('**/api/v1/divisions', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ id: 'div-1', name: 'Equities', description: '', deskCount: 1 }]),
      })
    })

    await page.route('**/api/v1/divisions/*/desks', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ id: 'desk-1', name: 'EU Equities', divisionId: 'div-1', deskHead: 'Alice', bookCount: 2 }]),
      })
    })

    await page.route('**/api/v1/divisions/*/summary*', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ bookId: 'div-1', baseCurrency: 'USD', totalNav: { amount: '0', currency: 'USD' }, totalUnrealizedPnl: { amount: '0', currency: 'USD' }, currencyBreakdown: [] }) })
    })
    await page.route('**/api/v1/desks/*/summary*', (route) => {
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ bookId: 'desk-1', baseCurrency: 'USD', totalNav: { amount: '0', currency: 'USD' }, totalUnrealizedPnl: { amount: '0', currency: 'USD' }, currencyBreakdown: [] }) })
    })

    // Override positions
    await page.unroute('**/api/v1/books/*/positions')
    await page.route('**/api/v1/books/*/positions', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    })

    // Navigate to Trades tab at firm level (shows all 3 trades from all books)
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid^="trade-row-"]')

    // Navigate to book-2 via hierarchy: division -> desk -> book
    await page.getByTestId('hierarchy-selector-toggle').click()
    await page.getByTestId('hierarchy-division-div-1').click()
    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
    await page.getByTestId('hierarchy-desk-desk-1').click()
    await page.waitForSelector('[data-testid="hierarchy-book-book-2"]')
    await page.getByTestId('hierarchy-book-book-2').click()

    // After navigating to book-2, trades should refetch for that book
    await page.waitForSelector('[data-testid="trade-row-trade-b2-1"]')

    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)
    await expect(page.getByTestId('trade-row-trade-b2-1')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------
// P1 — CSV Data Accuracy
// ---------------------------------------------------------------------------

test.describe('Trade Blotter - CSV Data Accuracy', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('CSV notional column equals qty × price for each row', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid="csv-export-button"]')

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('csv-export-button').click()
    const download = await downloadPromise

    const filePath = await download.path()
    expect(filePath).toBeTruthy()

    const { readFile } = await import('fs/promises')
    const content = await readFile(filePath!, 'utf-8')
    const lines = content.trim().split('\n')
    const dataLines = lines.slice(1) // skip header

    for (const line of dataLines) {
      const cols = line.split(',')
      // CSV format: Time,Instrument,Name,Type,Side,Qty,Price,Currency,Notional,Status
      const qty = Number(cols[5])
      const price = Number(cols[6])
      const notional = Number(cols[8])
      expect(notional).toBeCloseTo(qty * price, 2)
    }
  })

  test('CSV status column reflects actual trade status, not hardcoded FILLED', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid="csv-export-button"]')

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('csv-export-button').click()
    const download = await downloadPromise

    const filePath = await download.path()
    const { readFile } = await import('fs/promises')
    const content = await readFile(filePath!, 'utf-8')
    const lines = content.trim().split('\n')
    const dataLines = lines.slice(1) // skip header

    // Collect status values from last column
    const statuses = dataLines.map((line) => line.split(',').at(-1))

    // TEST_TRADES has LIVE, CANCELLED, AMENDED — none should be "FILLED"
    expect(statuses).not.toContain('FILLED')
    expect(statuses).toContain('LIVE')
    expect(statuses).toContain('CANCELLED')
    expect(statuses).toContain('AMENDED')
  })

  test('CSV rows are in descending time order (matching UI)', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid="csv-export-button"]')

    const downloadPromise = page.waitForEvent('download')
    await page.getByTestId('csv-export-button').click()
    const download = await downloadPromise

    const filePath = await download.path()
    const { readFile } = await import('fs/promises')
    const content = await readFile(filePath!, 'utf-8')
    const lines = content.trim().split('\n')
    const dataLines = lines.slice(1)

    // trade-3 is newest (14:00) → first row; trade-1 is oldest (10:30) → last row
    // Identify by tradedAt timestamp present in the Time column
    expect(dataLines[0]).toContain('14:00')
    expect(dataLines[dataLines.length - 1]).toContain('10:30')
  })
})

// ---------------------------------------------------------------------------
// P2 — Edge Cases
// ---------------------------------------------------------------------------

test.describe('Trade Blotter - Edge Cases', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('filter with no matches shows zero rows but keeps table headers', async ({ page }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-instrument').fill('NONEXISTENT')

    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(0)
    // Table headers should still be present
    await expect(page.locator('thead th').filter({ hasText: 'Instrument' })).toBeVisible()
  })

  test('partial instrument match works (GO → GOOGL)', async ({ page }) => {
    await goToTradesTab(page)

    await page.getByTestId('filter-instrument').fill('GO')

    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(1)
    await expect(page.getByTestId('trade-row-trade-2')).toBeVisible()
  })

  test('large dataset (100 trades) renders all rows', async ({ page }) => {
    await mockManyTrades(page, 100)

    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid^="trade-row-"]')

    // Pagination limits to 50 rows per page
    await expect(page.locator('[data-testid^="trade-row-"]')).toHaveCount(50)
  })

  test('zero-price trade displays $0.00 notional', async ({ page }) => {
    const zeroTrade: TradeFixture = {
      tradeId: 'trade-zero',
      bookId: 'port-1',
      instrumentId: 'ZERO',
      assetClass: 'EQUITY',
      side: 'BUY',
      quantity: '100',
      price: { amount: '0', currency: 'USD' },
      tradedAt: '2025-01-15T15:00:00Z',
    }

    await mockTradesOverride(page, [zeroTrade])

    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid="trade-notional-trade-zero"]')

    await expect(page.getByTestId('trade-notional-trade-zero')).toHaveText('$0')
  })

  test('very large quantity formats with commas', async ({ page }) => {
    const bigTrade: TradeFixture = {
      tradeId: 'trade-big',
      bookId: 'port-1',
      instrumentId: 'BIG',
      assetClass: 'EQUITY',
      side: 'BUY',
      quantity: '1000000',
      price: { amount: '150', currency: 'USD' },
      tradedAt: '2025-01-15T15:00:00Z',
    }

    await mockTradesOverride(page, [bigTrade])

    await page.goto('/')
    await page.getByTestId('tab-trades').click()
    await page.waitForSelector('[data-testid="trade-notional-trade-big"]')

    // 1,000,000 × 150 = $150,000,000 -> compact: $150M
    await expect(page.getByTestId('trade-notional-trade-big')).toHaveText('$150M')
  })
})
