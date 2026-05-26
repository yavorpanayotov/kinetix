import { test, expect, type Route } from '@playwright/test'
import { chatMockCanned, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Copilot chat — book-boundary conversation reset (plans/ai-copilot-demo-polish.md §4.2)
// ---------------------------------------------------------------------------
//
// When the active book changes while the copilot has a non-empty conversation
// the CommandPalette must:
//   • clear the streamed state (no stale answer visible on reopen)
//   • show a one-line in-palette reset banner identifying the new book
//   • ensure the next POST /api/v1/insights/chat carries no stale conversation_id
//
// These specs drive the running app with deterministic mocked routes so there
// is no dependency on a live backend.

const TWO_BOOKS = [{ bookId: 'book-alpha' }, { bookId: 'book-beta' }]
const DIVISION = { id: 'div-1', name: 'Equities', description: '', deskCount: 1 }
const DESK = { id: 'desk-1', name: 'EU Desk', divisionId: 'div-1', deskHead: 'Alice', bookCount: 2 }
const SUMMARY = {
  bookId: 'firm',
  baseCurrency: 'USD',
  totalNav: { amount: '100000.00', currency: 'USD' },
  totalUnrealizedPnl: { amount: '1000.00', currency: 'USD' },
  currencyBreakdown: [],
}

/** Override routes so the hierarchy has two books reachable via a desk. */
async function mockTwoBookHierarchy(page: Parameters<typeof mockAllApiRoutes>[0]): Promise<void> {
  await page.unroute('**/api/v1/books')
  await page.route('**/api/v1/books', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(TWO_BOOKS),
    })
  })

  await page.unroute('**/api/v1/divisions')
  await page.route('**/api/v1/divisions', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([DIVISION]),
    })
  })

  await page.unroute('**/api/v1/divisions/*/desks')
  await page.route('**/api/v1/divisions/*/desks', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([DESK]),
    })
  })

  // Division and desk summaries used when navigating to those levels.
  await page.route('**/api/v1/divisions/*/summary*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...SUMMARY, bookId: 'div-1' }),
    })
  })

  await page.route('**/api/v1/desks/*/summary*', (route: Route) => {
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...SUMMARY, bookId: 'desk-1' }),
    })
  })
}

/**
 * Navigate the HierarchySelector to a specific book.
 *
 * On the first call from firm level: traverses Firm → Division → Desk → Book.
 * On subsequent calls when already at a book level the panel opens directly
 * at the desk view showing all books — click straight to the target book.
 */
async function navigateToBook(page: Parameters<typeof mockAllApiRoutes>[0], bookId: string): Promise<void> {
  const toggle = page.getByTestId('hierarchy-selector-toggle')
  await toggle.click()

  // If the panel already shows the book list (we're at desk/book level), skip
  // the division/desk navigation and click the book directly.
  const bookLocator = page.getByTestId(`hierarchy-book-${bookId}`)
  const divisionLocator = page.getByTestId('hierarchy-division-div-1')

  const isDivisionVisible = await divisionLocator.isVisible().catch(() => false)
  if (isDivisionVisible) {
    await divisionLocator.click()
    await page.waitForSelector('[data-testid="hierarchy-desk-desk-1"]')
    await page.getByTestId('hierarchy-desk-desk-1').click()
    await page.waitForSelector(`[data-testid="hierarchy-book-${bookId}"]`)
  }

  await bookLocator.click()
  // Clicking a book closes the panel (see handleBookClick → setOpen(false)).
}

test.describe('Copilot book-boundary reset', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockTwoBookHierarchy(page)
    await chatMockCanned(page)
  })

  test('switching book while copilot answer is visible shows reset banner on next palette open', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Navigate to the first book.
    await navigateToBook(page, 'book-alpha')

    // Open the palette and ask a question.
    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()
    await page.getByTestId('command-palette-input').fill('zzznomatch var question')
    await page.keyboard.press('Enter')

    // Wait for the streamed answer to complete.
    await expect(page.getByTestId('command-palette-copilot-response')).toBeVisible()
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )

    // Close the palette.
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('command-palette')).not.toBeVisible()

    // Switch to the second book via the hierarchy.
    await navigateToBook(page, 'book-beta')

    // Re-open the palette — the banner must appear.
    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()
    await expect(
      page.getByTestId('command-palette-book-reset-banner'),
    ).toBeVisible()
    await expect(
      page.getByTestId('command-palette-book-reset-banner'),
    ).toContainText('book-beta')
  })

  test('next chat request after book switch carries no stale conversation_id', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Navigate to the first book and send a chat message.
    await navigateToBook(page, 'book-alpha')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()
    await page.getByTestId('command-palette-input').fill('zzznomatch first question')
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    await page.keyboard.press('Escape')

    // Switch book.
    await navigateToBook(page, 'book-beta')

    // Re-open palette and send a second question; capture the request body.
    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const [chatRequest] = await Promise.all([
      page.waitForRequest('**/api/v1/insights/chat'),
      (async () => {
        await page.getByTestId('command-palette-input').fill('zzznomatch second question')
        await page.keyboard.press('Enter')
      })(),
    ])

    const body = JSON.parse(chatRequest.postData() ?? '{}') as Record<string, unknown>
    // A fresh conversation carries no conversation_id field (or it is null/undefined).
    expect(body['conversation_id']).toBeFalsy()
  })

  test('banner is dismissed when the user types in the search input', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await navigateToBook(page, 'book-alpha')

    await page.keyboard.press('ControlOrMeta+k')
    await page.getByTestId('command-palette-input').fill('zzznomatch question')
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
    await page.keyboard.press('Escape')

    await navigateToBook(page, 'book-beta')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette-book-reset-banner')).toBeVisible()

    // Typing dismisses the banner.
    await page.getByTestId('command-palette-input').fill('h')
    await expect(
      page.getByTestId('command-palette-book-reset-banner'),
    ).not.toBeVisible()
  })
})
