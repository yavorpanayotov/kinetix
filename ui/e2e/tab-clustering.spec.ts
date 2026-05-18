import { test, expect } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * Tab clustering — plan §2.1. The 11 top-level tabs are visually grouped
 * into three clusters with thin presentational dividers between the
 * cluster boundaries:
 *
 *   Trading: Positions · Trades · P&L
 *   Risk:    Risk · EOD · Scenarios · Counterparty Risk
 *   Ops:     Regulatory · Reports · Alerts · System
 *
 * The unit suite covers the wiring exhaustively; this Playwright test
 * proves the cluster order and dividers survive a real browser render.
 */
test.describe('tab clustering (plan §2.1)', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
  })

  test('renders tabs in the Trading -> Risk -> Ops cluster order', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    const renderedIds = await page
      .getByTestId('tab-bar')
      .locator('[role="tab"]')
      .evaluateAll(els =>
        els.map(el => el.getAttribute('data-testid') ?? ''),
      )

    expect(renderedIds).toEqual([
      'tab-positions',
      'tab-trades',
      'tab-pnl',
      'tab-risk',
      'tab-eod',
      'tab-scenarios',
      'tab-counterparty-risk',
      'tab-regulatory',
      'tab-reports',
      'tab-alerts',
      'tab-system',
    ])
  })

  test('renders presentational dividers between the cluster boundaries', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    const dividers = page
      .getByTestId('tab-bar')
      .getByTestId('tab-cluster-divider')
    // Two dividers: one before the Risk cluster, one before the Ops cluster.
    await expect(dividers).toHaveCount(2)

    // Dividers are presentational only — aria-hidden so AT skips them.
    const ariaHidden = await dividers.evaluateAll(els =>
      els.map(el => el.getAttribute('aria-hidden')),
    )
    expect(ariaHidden).toEqual(['true', 'true'])
  })

  test('the Risk cluster divider sits between P&L and Risk', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    const between = await page.evaluate(() => {
      const pnl = document.querySelector('[data-testid="tab-pnl"]')
      const risk = document.querySelector('[data-testid="tab-risk"]')
      const dividers = Array.from(
        document.querySelectorAll('[data-testid="tab-cluster-divider"]'),
      )
      if (!pnl || !risk || dividers.length < 1) return null
      const riskDivider = dividers[0]
      return (
        Boolean(
          pnl.compareDocumentPosition(riskDivider) &
            Node.DOCUMENT_POSITION_FOLLOWING,
        ) &&
        Boolean(
          riskDivider.compareDocumentPosition(risk) &
            Node.DOCUMENT_POSITION_FOLLOWING,
        )
      )
    })
    expect(between).toBe(true)
  })

  test('the Ops cluster divider sits between Counterparty Risk and Regulatory', async ({
    page,
  }) => {
    await page.goto('/')
    await page.waitForSelector('[data-testid="tab-positions"]')

    const between = await page.evaluate(() => {
      const cp = document.querySelector(
        '[data-testid="tab-counterparty-risk"]',
      )
      const reg = document.querySelector('[data-testid="tab-regulatory"]')
      const dividers = Array.from(
        document.querySelectorAll('[data-testid="tab-cluster-divider"]'),
      )
      if (!cp || !reg || dividers.length < 2) return null
      const opsDivider = dividers[1]
      return (
        Boolean(
          cp.compareDocumentPosition(opsDivider) &
            Node.DOCUMENT_POSITION_FOLLOWING,
        ) &&
        Boolean(
          opsDivider.compareDocumentPosition(reg) &
            Node.DOCUMENT_POSITION_FOLLOWING,
        )
      )
    })
    expect(between).toBe(true)
  })
})
