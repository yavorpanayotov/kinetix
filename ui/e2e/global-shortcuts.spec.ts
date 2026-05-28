import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Targeted regression spec for the two global keyboard shortcuts reported as
 * "failed" in the live accessibility audit (kx-8zsb).  The audit's probe was
 * a false negative: it grepped for dialog text that the components do not
 * render ("Ask the copilot..." and "Keyboard shortcuts" as a static string in
 * the DOM), while the actual components surface those identifiers via
 * data-testid attributes.  These tests use the real testids so the detector
 * matches what the app actually renders.
 */
test.describe('Global keyboard shortcuts', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await page.goto('/')
    // Wait for the app shell to be ready before firing shortcuts
    await page.waitForSelector('[data-testid="tab-positions"]')
  })

  test('Cmd/Ctrl+K opens the Command Palette', async ({ page }) => {
    // ControlOrMeta resolves to Meta on macOS and Control elsewhere.
    // This is the canonical Playwright cross-platform chord.
    await page.keyboard.press('ControlOrMeta+k')

    const palette = page.getByTestId('command-palette')
    await expect(palette).toBeVisible()

    // The input inside the palette should receive focus immediately.
    const input = page.getByTestId('command-palette-input')
    await expect(input).toBeFocused()

    // Close cleanly for hygiene — Escape should dismiss without side effects.
    await page.keyboard.press('Escape')
    await expect(palette).not.toBeVisible()
  })

  test('? opens the Keyboard Shortcuts Overlay', async ({ page }) => {
    // The handler listens for e.key === '?' on window.  Playwright's
    // keyboard.press('?') dispatches exactly that key with no modifiers, which
    // mirrors what a user pressing Shift+/ actually produces in the browser.
    // Focus is on document.body (not an input) so the input-focus guard in
    // App.tsx passes and the overlay opens.
    await page.keyboard.press('?')

    const overlay = page.getByTestId('keyboard-shortcuts-overlay')
    await expect(overlay).toBeVisible()

    // Verify the overlay carries the correct accessible role and label so
    // screen readers and automated auditors can identify it.
    await expect(overlay).toHaveAttribute('role', 'dialog')
    await expect(overlay).toHaveAttribute('aria-label', 'Keyboard shortcuts')

    // Close via Escape — the overlay's own keydown handler should dismiss it.
    await page.keyboard.press('Escape')
    await expect(overlay).not.toBeVisible()
  })
})
