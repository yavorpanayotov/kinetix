import { test, expect } from '@playwright/test'
import { mockAllApiRoutes, chatMockCanned } from './fixtures'

// ---------------------------------------------------------------------------
// CopilotLauncher — visible discovery affordance (docs/plans/ai-copilot-demo-polish.md §1)
//
// The launcher button ("Ask Kinetix") in the header right cluster gives demo
// viewers a click target to open the AI copilot without knowing ⌘K. These
// specs verify:
//  - The button is visible in the header in authenticated mode.
//  - Clicking it opens the CommandPalette in copilotMode.
//  - The ⌘K keyboard shortcut still works as a fallback.
//  - The keyboard chip glyph reflects the platform.
// ---------------------------------------------------------------------------

test.describe('CopilotLauncher', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await chatMockCanned(page)
  })

  test('launcher button is visible in the header', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    const launcher = page.getByTestId('copilot-launcher')
    await expect(launcher).toBeVisible()
    await expect(launcher).toContainText('Ask Kinetix')
  })

  test('clicking the launcher button opens the command palette', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await expect(page.getByTestId('command-palette')).not.toBeAttached()

    await page.getByTestId('copilot-launcher').click()

    await expect(page.getByTestId('command-palette')).toBeVisible()
  })

  test('command palette opens with the copilot zone visible when launched via button', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.getByTestId('copilot-launcher').click()

    await expect(page.getByTestId('command-palette')).toBeVisible()
    await expect(page.getByTestId('command-palette-copilot-zone')).toBeVisible()
    await expect(page.getByTestId('command-palette-copilot-hint')).toBeVisible()
  })

  test('⌘K / Ctrl+K keyboard shortcut still opens the palette as a fallback', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Ensure palette is not yet visible.
    await expect(page.getByTestId('command-palette')).not.toBeAttached()

    await page.keyboard.press('ControlOrMeta+k')

    await expect(page.getByTestId('command-palette')).toBeVisible()
  })

  test('launcher button keyboard chip displays the platform-appropriate shortcut', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    const chip = page.getByTestId('copilot-launcher-chip')
    await expect(chip).toBeVisible()

    // Chromium on macOS reports navigator.platform as "MacIntel".
    // On other platforms it will be "Win32", "Linux x86_64", etc.
    // We assert the chip contains a non-empty shortcut string without
    // hard-coding the platform so the spec passes on any OS.
    const chipText = await chip.textContent()
    expect(chipText).toMatch(/^(⌘K|Ctrl K)$/)
  })

  test('launcher button is visible regardless of the active tab', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Check on a different tab (Risk) to ensure the header is always present.
    await page.getByTestId('tab-risk').click()

    await expect(page.getByTestId('copilot-launcher')).toBeVisible()
  })
})
