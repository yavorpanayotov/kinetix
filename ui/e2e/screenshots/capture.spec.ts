import { test, expect, type Page } from '@playwright/test'
import {
  mockAllApiRoutes,
  mockCounterpartyRiskRoutes,
  chatMockCanned,
} from '../fixtures'

// ---------------------------------------------------------------------------
// Screenshot capture — drives the running UI with deterministic mocked routes
// (the same fixtures the e2e suite uses) and writes PNGs to docs/screenshots/.
//
// This is not a behavioural test; it is a documentation artefact generator
// for the case studies and README. Re-run after a relevant UI change:
//   cd ui && npx playwright test e2e/screenshots/capture.spec.ts --project=chromium
//
// Filenames are deterministic so each run overwrites cleanly with no churn.
// ---------------------------------------------------------------------------

const SHOTS = '../docs/screenshots'

test.describe('UI screenshots', () => {
  test('counterparty-risk tab', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page)

    await page.goto('/')
    await page.getByTestId('tab-counterparty-risk').click()
    await expect(page.getByTestId('counterparty-risk-dashboard')).toBeVisible()
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await page.screenshot({
      path: `${SHOTS}/counterparty-risk-tab.png`,
      fullPage: true,
    })
  })

  test('copilot narrative with citation', async ({ page }: { page: Page }) => {
    await mockAllApiRoutes(page)
    await chatMockCanned(page)

    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('copilot-launcher').click()
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('Why did my VaR move?')
    await page.keyboard.press('Enter')

    // Wait for the streamed narrative and its citation list to render.
    await expect(page.getByTestId('streaming-narrative-text')).toBeVisible()
    await expect(page.getByTestId('citation-list')).toBeVisible()
    await expect(page.getByTestId('citation-list-item').first()).toBeVisible()

    // The copilot palette is a fixed-position overlay — capture the
    // viewport (not full page) so the modal is in frame.
    await page.screenshot({
      path: `${SHOTS}/copilot-narrative.png`,
      fullPage: false,
    })
  })
})
