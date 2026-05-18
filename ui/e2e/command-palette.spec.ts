import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

test.describe('Command Palette - Cmd+K / Ctrl+K (plan §7.1)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('Cmd+K opens the palette, typing a tab name and pressing Enter switches tabs', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Sanity: Positions is the default active tab.
    await expect(page.getByTestId('tab-positions')).toHaveAttribute(
      'aria-selected',
      'true',
    )

    // Open the palette. ControlOrMeta resolves to Meta on Mac, Control elsewhere.
    await page.keyboard.press('ControlOrMeta+k')

    const palette = page.getByTestId('command-palette')
    await expect(palette).toBeVisible()
    const input = page.getByTestId('command-palette-input')
    await expect(input).toBeFocused()

    // Type a tab name and activate it.
    await input.fill('Trades')
    await expect(page.getByTestId('command-palette-item-tab:trades')).toBeVisible()
    await page.keyboard.press('Enter')

    // The palette should close and the Trades tab should be active.
    await expect(palette).not.toBeVisible()
    await expect(page.getByTestId('tab-trades')).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  test('Escape closes the palette without switching tabs', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    await page.keyboard.press('Escape')

    await expect(page.getByTestId('command-palette')).not.toBeVisible()
    // Positions is still selected.
    await expect(page.getByTestId('tab-positions')).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })
})
