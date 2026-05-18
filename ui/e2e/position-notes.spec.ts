import { test, expect, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

interface PositionNoteFixture {
  id: string
  bookId: string
  instrumentId: string
  note: string
  author: string
  createdAt: string
}

test.describe('Position notes popover', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('open popover, add a note, see it in the popover', async ({ page }) => {
    const notes: PositionNoteFixture[] = []

    // Override the default empty-list handler with a stateful in-memory store.
    await page.unroute('**/api/v1/positions/*/notes*')
    await page.route('**/api/v1/positions/*/notes*', async (route: Route) => {
      const request = route.request()
      const url = new URL(request.url())
      const method = request.method()
      const bookId = decodeURIComponent(url.pathname.split('/').slice(-2)[0])

      if (method === 'GET') {
        const instrumentFilter = url.searchParams.get('instrumentId')
        const filtered = instrumentFilter
          ? notes.filter((n) => n.instrumentId === instrumentFilter)
          : notes
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(filtered.filter((n) => n.bookId === bookId)),
        })
        return
      }

      if (method === 'POST') {
        const body = JSON.parse(request.postData() ?? '{}') as {
          instrumentId: string
          note: string
        }
        const created: PositionNoteFixture = {
          id: `note-${notes.length + 1}`,
          bookId,
          instrumentId: body.instrumentId,
          note: body.note,
          author: 'demo-user',
          createdAt: new Date().toISOString(),
        }
        notes.unshift(created)
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(created),
        })
        return
      }

      await route.fulfill({ status: 405 })
    })

    await page.route('**/api/v1/positions/notes/*', async (route: Route) => {
      if (route.request().method() === 'DELETE') {
        const id = route.request().url().split('/').pop()!
        const idx = notes.findIndex((n) => n.id === id)
        if (idx >= 0) notes.splice(idx, 1)
        await route.fulfill({ status: 204 })
        return
      }
      await route.fulfill({ status: 405 })
    })

    await page.goto('/')

    // Positions tab is the default landing — wait for the AAPL row.
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    // Open the popover for AAPL.
    await page.getByTestId('position-note-icon-AAPL').click()

    const popover = page.getByTestId('position-note-popover-AAPL')
    await expect(popover).toBeVisible()
    await expect(popover).toContainText('No notes yet')

    // Submit a new note.
    await popover
      .getByTestId('position-note-input-AAPL')
      .fill('Earnings call this Thursday')
    await popover.getByTestId('position-note-submit-AAPL').click()

    // The note should appear in the popover.
    await expect(popover).toContainText('Earnings call this Thursday')
    await expect(popover).toContainText('demo-user')

    // The icon should now show a count of 1.
    await expect(page.getByTestId('position-note-icon-AAPL')).toContainText('1')
  })

  test('popover closes when Escape is pressed', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="position-row-AAPL"]')

    await page.getByTestId('position-note-icon-AAPL').click()
    await expect(page.getByTestId('position-note-popover-AAPL')).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(
      page.getByTestId('position-note-popover-AAPL'),
    ).not.toBeVisible()
  })
})
