import { test, expect } from '@playwright/test'
import { chatMockCanned, chatMockCannedWithToolCalls, mockAllApiRoutes } from './fixtures'

// ---------------------------------------------------------------------------
// Copilot chat — Cmd+K command palette copilot zone (plans/ai-v2.md §5.8)
// ---------------------------------------------------------------------------
//
// With `copilotMode` enabled, the Cmd+K command palette doubles as an inline
// copilot: a free-form question that matches no command is routed to the v2
// chat endpoint (POST /api/v1/insights/chat) and answered below the command
// list via <StreamingNarrative> + <CitationList>. A canned (offline) answer
// surfaces a "Demo mode" badge.
//
// These specs drive the running app against a deterministic mocked SSE
// stream (`chatMockCanned`) so the copilot surface is exercised end-to-end
// without a live ai-insights-service.

test.describe('Copilot chat', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await chatMockCanned(page)
  })

  test('Cmd+K opens the palette and reveals the copilot zone', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')

    await expect(page.getByTestId('command-palette')).toBeVisible()
    await expect(
      page.getByTestId('command-palette-copilot-zone'),
    ).toBeVisible()
    await expect(
      page.getByTestId('command-palette-copilot-hint'),
    ).toBeVisible()
  })

  test('a free-form question streams the copilot answer token by token', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    // A free-form question that matches no command in the palette.
    await input.fill('zzznomatch why did my VaR move')
    await page.keyboard.press('Enter')

    await expect(
      page.getByTestId('command-palette-copilot-response'),
    ).toBeVisible()
    await expect(
      page.getByTestId('streaming-narrative-text'),
    ).toHaveText('Your VaR rose on tech beta.')
  })

  test('clicking a citation expands its parameters', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch why did my VaR move')
    await page.keyboard.press('Enter')

    // Wait for the stream to complete so the citation list renders.
    await expect(
      page.getByTestId('command-palette-copilot-citations'),
    ).toBeVisible()
    await expect(page.getByTestId('citation-list')).toBeVisible()

    const items = page.getByTestId('citation-list-item')
    await expect(items).toHaveCount(1)

    const firstItem = items.first()
    await expect(firstItem).toContainText('get_book_var')

    // The params JSON lives inside a collapsed <details>; expanding the
    // "Parameters" <summary> reveals the get_book_var params.
    const params = firstItem.locator('pre')
    await expect(params).toBeHidden()
    await firstItem.getByText('Parameters').click()
    await expect(params).toBeVisible()
    await expect(params).toContainText('book_id')
    await expect(params).toContainText('port-1')
  })

  test('a canned answer shows the Demo mode badge', async ({ page }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch why did my VaR move')
    await page.keyboard.press('Enter')

    await expect(
      page.getByTestId('command-palette-copilot-demo-badge'),
    ).toHaveText('Demo mode')
  })

  test('reasoning panel appears when done chunk carries tool_calls, can be expanded, and shows tool names', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    // Override the default canned mock with one that includes tool_calls.
    await chatMockCannedWithToolCalls(page)

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch why did VaR move')
    await page.keyboard.press('Enter')

    // Wait for the stream to complete so the reasoning panel is rendered.
    await expect(page.getByTestId('streaming-narrative-text')).toHaveText(
      'Your VaR rose on tech beta.',
    )

    // The collapsible panel must be present but initially collapsed.
    const panel = page.getByTestId('tool-call-list')
    await expect(panel).toBeVisible()

    // Expand it by clicking the summary.
    await panel.locator('summary').first().click()

    // At least one tool-call-row must become visible.
    await expect(page.getByTestId('tool-call-row').first()).toBeVisible()
    await expect(page.getByTestId('tool-call-row').first()).toContainText('get_book_var')
  })

  test('Escape closes the palette and abandons the stream cleanly', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()

    const input = page.getByTestId('command-palette-input')
    await input.fill('zzznomatch why did my VaR move')
    await page.keyboard.press('Enter')
    await expect(
      page.getByTestId('command-palette-copilot-response'),
    ).toBeVisible()

    // Escape abandons the in-flight (or just-completed) stream and closes
    // the palette without crashing.
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('command-palette')).not.toBeVisible()

    // A second open starts fresh — no leftover copilot response.
    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()
    await expect(
      page.getByTestId('command-palette-copilot-hint'),
    ).toBeVisible()
    await expect(
      page.getByTestId('command-palette-copilot-response'),
    ).toHaveCount(0)
  })
})
