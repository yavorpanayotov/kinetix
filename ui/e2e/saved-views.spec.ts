import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Plan §2.3 — named multi-workspace saved views.
 *
 * Verifies the user can:
 *   - see the default saved view in the workspace picker,
 *   - save a new named view that captures the current tab,
 *   - switch between views and have the active tab restored,
 *   - persist views across reload.
 */
test.describe('saved views (plan §2.3)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('saved views are created, switched, and restored across reload', async ({ page }) => {
    await page.goto('/')
    // Clear any stale workspace from prior runs (Playwright keeps localStorage
    // per-context but we want a deterministic starting state).
    await page.evaluate(() => localStorage.removeItem('kinetix:workspace'))
    await page.reload()
    await page.waitForSelector('[data-testid="workspace-view-toggle"]')

    // Default state: the picker shows the "Default" view.
    await expect(page.getByTestId('workspace-view-toggle')).toContainText('Default')

    // Navigate to the Risk tab so the next saved view captures it as defaultTab.
    await page.getByTestId('tab-risk').click()
    await expect(page.getByTestId('tab-risk')).toHaveAttribute('aria-selected', 'true')

    // Save a new named view "Risk morning". The picker prompts via window.prompt;
    // accept the native dialog with our chosen name.
    page.once('dialog', async (dialog) => {
      expect(dialog.type()).toBe('prompt')
      await dialog.accept('Risk morning')
    })
    await page.getByTestId('workspace-view-toggle').click()
    await page.getByTestId('workspace-view-save-as-new').click()

    // The new view is active and visible in the toggle.
    await expect(page.getByTestId('workspace-view-toggle')).toContainText('Risk morning')

    // Switch back to the Default view via the picker — Positions tab should return.
    await page.getByTestId('workspace-view-toggle').click()
    const defaultOption = page.getByTestId('workspace-view-panel').getByText('Default', { exact: true })
    await defaultOption.click()

    await expect(page.getByTestId('workspace-view-toggle')).toContainText('Default')
    await expect(page.getByTestId('tab-positions')).toHaveAttribute('aria-selected', 'true')

    // Switch back to "Risk morning" — the Risk tab is restored.
    await page.getByTestId('workspace-view-toggle').click()
    await page.getByTestId('workspace-view-panel').getByText('Risk morning', { exact: true }).click()
    await expect(page.getByTestId('tab-risk')).toHaveAttribute('aria-selected', 'true')

    // Persistence across reload — the active view and its prefs are restored.
    await page.reload()
    await page.waitForSelector('[data-testid="workspace-view-toggle"]')
    await expect(page.getByTestId('workspace-view-toggle')).toContainText('Risk morning')
    await expect(page.getByTestId('tab-risk')).toHaveAttribute('aria-selected', 'true')

    // Storage shape sanity-check — both views are present in the envelope.
    const stored = await page.evaluate(() => localStorage.getItem('kinetix:workspace'))
    expect(stored).toBeTruthy()
    const parsed = JSON.parse(stored!)
    expect(parsed.views.map((v: { name: string }) => v.name).sort()).toEqual(['Default', 'Risk morning'])
  })
})
