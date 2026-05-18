import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

test.describe('Keyboard Navigation - Tab Traversal and Focus Management', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('Tab traversal reaches header controls: book selector, workspace view picker, dark mode toggle', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="dark-mode-toggle"]')

    // Start tabbing from the top of the page.
    // Use cycle-detection instead of a fixed iteration count so the test
    // does not break when new focusable elements are added to the header.
    await page.keyboard.press('Tab')

    const visitedTestIds = new Set<string>()
    for (let i = 0; i < 200; i++) {
      const testId = await page.evaluate(() =>
        document.activeElement?.getAttribute('data-testid'),
      )
      if (testId) {
        if (visitedTestIds.has(testId) && visitedTestIds.size > 5) break
        visitedTestIds.add(testId)
      }
      await page.keyboard.press('Tab')
    }

    expect(visitedTestIds.has('hierarchy-selector-toggle')).toBe(true)
    expect(visitedTestIds.has('dark-mode-toggle')).toBe(true)
    expect(visitedTestIds.has('workspace-view-toggle')).toBe(true)
  })

  test('ArrowRight moves focus between tabs', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Focus the active tab (Positions)
    await page.getByTestId('tab-positions').focus()
    await expect(page.getByTestId('tab-positions')).toBeFocused()

    // Press ArrowRight to move to the next tab (Trades)
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('tab-trades')).toBeFocused()

    // Press ArrowRight again to move to P&L
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('tab-pnl')).toBeFocused()
  })

  test('ArrowLeft moves focus between tabs in reverse', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Focus the Trades tab
    await page.getByTestId('tab-trades').focus()
    await expect(page.getByTestId('tab-trades')).toBeFocused()

    // Press ArrowLeft to go back to Positions
    await page.keyboard.press('ArrowLeft')
    await expect(page.getByTestId('tab-positions')).toBeFocused()
  })

  test('ArrowLeft wraps from the first tab to the last tab', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Focus the first tab (Positions)
    await page.getByTestId('tab-positions').focus()

    // Press ArrowLeft to wrap to the last tab (System)
    await page.keyboard.press('ArrowLeft')
    await expect(page.getByTestId('tab-system')).toBeFocused()
  })

  test('ArrowRight wraps from the last tab to the first tab', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-system"]')

    // Focus the last tab (System)
    await page.getByTestId('tab-system').focus()

    // Press ArrowRight to wrap to the first tab (Positions)
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('tab-positions')).toBeFocused()
  })

  test('Enter activates a focused tab', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Focus the Trades tab and press Enter to activate it
    await page.getByTestId('tab-trades').focus()
    await page.keyboard.press('Enter')

    // The Trades tab should now be the active (selected) tab
    await expect(page.getByTestId('tab-trades')).toHaveAttribute(
      'aria-selected',
      'true',
    )

    // The main content should reflect the Trades tab panel
    const main = page.locator('main[role="tabpanel"]')
    await expect(main).toHaveAttribute('aria-labelledby', 'tab-trades')
  })

  test('focus can reach all interactive controls without a mouse', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="dark-mode-toggle"]')

    // Tab through all interactive elements using cycle-detection
    const visitedTestIds = new Set<string>()
    for (let i = 0; i < 200; i++) {
      await page.keyboard.press('Tab')
      const testId = await page.evaluate(() =>
        document.activeElement?.getAttribute('data-testid'),
      )
      if (testId) {
        if (visitedTestIds.has(testId) && visitedTestIds.size > 5) break
        visitedTestIds.add(testId)
      }
    }

    // Verify key interactive controls are reachable
    const expectedControls = [
      'hierarchy-selector-toggle',
      'dark-mode-toggle',
      'workspace-view-toggle',
      'csv-export-button',
      'column-settings-button',
    ]
    for (const control of expectedControls) {
      expect(
        visitedTestIds.has(control),
      ).toBe(true)
    }
  })

  test('no keyboard trap: focus does not get stuck on any single element', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="dark-mode-toggle"]')

    // Tab through elements and verify that focus changes
    let previousTestId: string | null = null
    let stuckCount = 0

    for (let i = 0; i < 200; i++) {
      await page.keyboard.press('Tab')
      const currentTestId = await page.evaluate(() =>
        document.activeElement?.getAttribute('data-testid') ??
        document.activeElement?.tagName ?? null,
      )
      if (currentTestId === previousTestId) {
        stuckCount++
      } else {
        stuckCount = 0
      }
      // Focus should never stay on the same element for more than 2
      // consecutive Tab presses (allowing for shift in sub-elements)
      expect(stuckCount).toBeLessThan(3)
      previousTestId = currentTestId
    }
  })

  test('focus outline is visible on interactive elements', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="dark-mode-toggle"]')

    // Focus the dark mode toggle via keyboard
    await page.getByTestId('dark-mode-toggle').focus()

    // Verify focus is visible via outline or ring style
    const outlineStyle = await page.getByTestId('dark-mode-toggle').evaluate(
      (el) => {
        const style = getComputedStyle(el)
        return {
          outline: style.outline,
          outlineStyle: style.outlineStyle,
          boxShadow: style.boxShadow,
        }
      },
    )

    // The element should have either a visible outline or a box-shadow (ring)
    // Tailwind's focus:ring-* uses box-shadow, while focus:outline uses outline
    const hasVisibleOutline =
      outlineStyle.outlineStyle !== 'none' && outlineStyle.outline !== ''
    const hasBoxShadowRing =
      outlineStyle.boxShadow !== 'none' && outlineStyle.boxShadow !== ''

    expect(hasVisibleOutline || hasBoxShadowRing).toBe(true)
  })
})
