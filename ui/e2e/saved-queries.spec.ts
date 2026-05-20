import { test, expect } from '@playwright/test'
import { briefMockCanned, chatMockCanned, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Saved queries — copilot saved-query chips (plans/ai-v2.md §8.3)
// ---------------------------------------------------------------------------
//
// A *saved query* is a named, reusable copilot prompt. Five undeletable
// built-in defaults ship with a Lock icon; user queries are saved from the
// Cmd+K palette into `localStorage` (key `kinetix:copilot:saved-queries`,
// capped at twelve). The built-in chips render at the top of the
// notification inbox and as a "Copilot" group in the Cmd+K empty-query
// state; user chips render alongside them and are deletable.
//
// These specs drive the running app against a deterministic mocked SSE
// stream (`chatMockCanned`) so clicking a chip exercises the copilot
// surface end-to-end without a live ai-insights-service.

const SAVED_QUERIES_KEY = 'kinetix:copilot:saved-queries'
const BRIEF_SEEN_KEY = 'kinetix:morning-brief:last-seen-date'

test.describe('Saved queries', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await chatMockCanned(page)
    // A morning brief gives the notification strip content so its
    // expand toggle renders — the inbox (and its saved-query chips)
    // would otherwise be unreachable from a fully-empty strip.
    await briefMockCanned(page)
    // Start each test with no user-saved queries, and stamp the brief
    // as seen today so it does not auto-expand the strip out from under
    // the explicit toggle clicks below.
    await page.addInitScript(
      ([savedKey, briefKey]) => {
        window.localStorage.removeItem(savedKey)
        window.localStorage.setItem(
          briefKey,
          new Date().toISOString().slice(0, 10),
        )
      },
      [SAVED_QUERIES_KEY, BRIEF_SEEN_KEY],
    )
  })

  test('the five built-in chips render in the notification inbox', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Expand the notification strip to reveal the inbox + saved-query chips.
    await page.getByTestId('notification-strip-toggle').click()

    const chips = page.getByTestId('saved-query-chips')
    await expect(chips).toBeVisible()
    await expect(chips.getByTestId('saved-query-chip-limit-breaches')).toBeVisible()
    await expect(
      chips.getByTestId('saved-query-chip-pnl-vs-yesterday'),
    ).toBeVisible()
    await expect(
      chips.getByTestId('saved-query-chip-var-week-drivers'),
    ).toBeVisible()
    await expect(
      chips.getByTestId('saved-query-chip-top-positions-risk-contribution'),
    ).toBeVisible()
    await expect(
      chips.getByTestId('saved-query-chip-vol-dislocations'),
    ).toBeVisible()
  })

  test('a built-in chip is locked and has no delete control', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')
    await page.getByTestId('notification-strip-toggle').click()

    // The Lock icon is present; no delete (✕) control.
    await expect(
      page.getByTestId('saved-query-chip-lock-limit-breaches'),
    ).toBeVisible()
    await expect(
      page.getByTestId('saved-query-chip-delete-limit-breaches'),
    ).toHaveCount(0)
  })

  test('the Cmd+K empty-query state shows a Copilot group of saved queries', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    // Empty query — the "Copilot" group renders the built-in chips.
    await expect(
      page.getByTestId('command-palette-group-Copilot'),
    ).toBeVisible()
    const group = page.getByTestId('command-palette-saved-queries')
    await expect(
      group.getByTestId('saved-query-chip-limit-breaches'),
    ).toBeVisible()
    await expect(
      group.getByTestId('saved-query-chip-vol-dislocations'),
    ).toBeVisible()
  })

  test('clicking a built-in chip in the inbox opens the palette and runs the query', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')
    await page.getByTestId('notification-strip-toggle').click()

    await page
      .getByTestId('saved-query-chips')
      .getByTestId('saved-query-chip-run-limit-breaches')
      .click()

    // The palette opens and the copilot streams the canned answer.
    await expect(page.getByTestId('command-palette')).toBeVisible()
    await expect(
      page.getByTestId('command-palette-copilot-response'),
    ).toBeVisible()
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )
  })

  test('a free-form query can be saved from the palette and adds a user chip', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch my custom copilot question')

    // The save affordance appears once a query is typed.
    const saveBtn = page.getByTestId('command-palette-save-query')
    await expect(saveBtn).toBeVisible()
    await saveBtn.click()
    await expect(
      page.getByTestId('command-palette-saved-query-notice'),
    ).toContainText('Saved')

    // Clearing the input returns to the empty-query state — the new user
    // chip is now in the Copilot group, deletable (no Lock icon).
    await input.fill('')
    const group = page.getByTestId('command-palette-saved-queries')
    const userChip = group
      .locator('[data-testid^="saved-query-chip-user-"]')
      .first()
    await expect(userChip).toBeVisible()
    await expect(userChip).toContainText('zzznomatch my custom copilot question')

    // Persisted to localStorage.
    const stored = await page.evaluate(
      (key) => window.localStorage.getItem(key),
      SAVED_QUERIES_KEY,
    )
    expect(stored).toContain('zzznomatch my custom copilot question')
  })

  test('a user chip can be deleted from the palette', async ({ page }) => {
    // Seed one user-saved query.
    await page.addInitScript(
      ([key]) => {
        window.localStorage.setItem(
          key,
          JSON.stringify([
            { id: 'user-seed', label: 'Seeded query', prompt: 'why?' },
          ]),
        )
      },
      [SAVED_QUERIES_KEY],
    )

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const group = page.getByTestId('command-palette-saved-queries')
    await expect(
      group.getByTestId('saved-query-chip-user-seed'),
    ).toBeVisible()

    await group.getByTestId('saved-query-chip-delete-user-seed').click()
    await expect(
      group.getByTestId('saved-query-chip-user-seed'),
    ).toHaveCount(0)

    // Removed from localStorage.
    const stored = await page.evaluate(
      (key) => window.localStorage.getItem(key),
      SAVED_QUERIES_KEY,
    )
    expect(stored).not.toContain('user-seed')
  })

  test('saving past twelve user queries is blocked with a limit notice', async ({
    page,
  }) => {
    // Seed exactly twelve user-saved queries — the cap.
    await page.addInitScript(
      ([key]) => {
        const twelve = Array.from({ length: 12 }, (_, i) => ({
          id: `user-${i}`,
          label: `Query ${i}`,
          prompt: `prompt ${i}`,
        }))
        window.localStorage.setItem(key, JSON.stringify(twelve))
      },
      [SAVED_QUERIES_KEY],
    )

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch one too many')
    await page.getByTestId('command-palette-save-query').click()

    await expect(
      page.getByTestId('command-palette-saved-query-notice'),
    ).toContainText('limit reached')

    // The thirteenth query was NOT persisted.
    const stored = await page.evaluate(
      (key) => window.localStorage.getItem(key),
      SAVED_QUERIES_KEY,
    )
    expect(stored).not.toContain('one too many')
  })
})
