import { test, expect, type Page, type Route } from '@playwright/test'
import { mockAllApiRoutes, mockRiskTabRoutes, TEST_VAR_RESULT } from './fixtures'

// ---------------------------------------------------------------------------
// Copilot grounded numbers (kx-fant)
// ---------------------------------------------------------------------------
//
// The data-grounded canned client quotes the book's LIVE VaR rather than a
// fixture constant, so the figure the Copilot states must match the figure on
// the VaR dashboard. This spec proves the user-visible half of that contract:
// the dashboard renders the book's VaR, and the Copilot — fed a grounded-shape
// SSE (mode "canned-grounded", a get_book_var citation whose result_value is
// the book's VaR) — states the same figure to the dollar.
//
// The SSE is mocked (the backend grounding logic itself is proven by the
// ai-insights-service Python unit + route-acceptance tests); this spec guards
// that the UI faithfully renders the grounded figure and that it agrees with
// the dashboard.

// TEST_VAR_RESULT.varValue is '125000.50' → formatMoney renders "$125,000.50";
// the grounded narrative formats whole dollars as "$125,000". Both agree to
// the dollar, which is what "numbers match the dashboard" means for a viewer.
const VAR_DOLLARS = '$125,000'

async function groundedVarChatMock(page: Page): Promise<void> {
  const citation = {
    tool: 'get_book_var',
    params: { book_id: 'port-1' },
    result_field: 'total_var',
    result_value: 125000.5,
    result_currency: 'USD',
    as_of_timestamp: '2026-05-19T08:00:00Z',
    data_source: 'risk-orchestrator',
    freshness_seconds: 30,
    quality_flags: [],
  }
  const body = [
    'data: {"delta":"Portfolio VaR for port-1 is ","done":false}\n\n',
    `data: {"delta":"${VAR_DOLLARS} ","done":false}\n\n`,
    'data: {"delta":"at 95% confidence.","done":false}\n\n',
    'event: source\ndata: ' + JSON.stringify([citation]) + '\n\n',
    'data: ' +
      JSON.stringify({
        done: true,
        session_id: 'sess-e2e',
        conversation_id: 'conv-e2e',
        model: 'canned-grounded',
        mode: 'canned-grounded',
        citations: [citation],
      }) +
      '\n\n',
  ].join('')

  await page.unroute('**/api/v1/insights/chat')
  await page.route('**/api/v1/insights/chat', async (route: Route) => {
    await route.fulfill({ status: 200, contentType: 'text/event-stream', body })
  })
}

test.describe('Copilot grounded numbers', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockRiskTabRoutes(page, { varResult: TEST_VAR_RESULT })
    await groundedVarChatMock(page)
  })

  test('the Copilot states the same VaR figure shown on the dashboard', async ({
    page,
  }) => {
    await page.goto('/')
    await page.getByTestId('tab-risk').click()
    await page.waitForSelector('[data-testid="var-dashboard"]')

    // The dashboard's headline VaR carries the full figure in its title.
    const varValue = page.getByTestId('var-value')
    await expect(varValue).toBeVisible()
    const title = await varValue.getAttribute('title')
    expect(title).toContain(VAR_DOLLARS)

    // Ask the Copilot; it streams the grounded answer.
    await page.keyboard.press('ControlOrMeta+k')
    await expect(page.getByTestId('command-palette')).toBeVisible()
    await page
      .getByTestId('command-palette-input')
      .fill('zzznomatch what is my VaR')
    await page.keyboard.press('Enter')

    const narrative = page.getByTestId('streaming-narrative-text')
    await expect(narrative).toContainText(`Portfolio VaR for port-1 is ${VAR_DOLLARS}`)

    // The Copilot figure equals the dashboard figure to the dollar.
    expect(title).toContain(VAR_DOLLARS)
    await expect(narrative).toContainText(VAR_DOLLARS)

    // And the answer is provenance-backed by the VaR tool.
    await expect(page.getByTestId('citation-list')).toBeVisible()
    await expect(page.getByTestId('citation-list-item').first()).toContainText(
      'get_book_var',
    )
  })
})
