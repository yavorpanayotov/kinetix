import { test, expect, type Page } from '@playwright/test'
import { mockAllApiRoutes, mockCounterpartyRiskRoutes } from './fixtures'

// Trader-review P2 #27: a counterparty whose ISDA / netting agreement has
// expired must offer a remediation CTA, not just a passive "Agreement Expired"
// badge. The CTA lets a credit officer block new trades and open a remediation
// ticket without leaving the screen.

async function goToCounterpartyRiskTab(page: Page) {
  await page.goto('/')
  await page.getByTestId('tab-counterparty-risk').click()
}

const EXPOSURES_WITH_EXPIRED = [
  {
    counterpartyId: 'CP-DB',
    calculatedAt: '2026-03-24T10:00:00Z',
    currentNetExposure: 3_000_000,
    peakPfe: 2_500_000,
    cva: 9_000,
    cvaEstimated: false,
    currency: 'USD',
    pfeProfile: [],
    agreementStatus: 'EXPIRED',
  },
  {
    counterpartyId: 'CP-GS',
    calculatedAt: '2026-03-24T10:00:00Z',
    currentNetExposure: 2_000_000,
    peakPfe: 1_800_000,
    cva: 12_500,
    cvaEstimated: false,
    currency: 'USD',
    pfeProfile: [],
    agreementStatus: 'ACTIVE',
  },
]

test.describe('Counterparty Risk - expired agreement remediation CTA', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockCounterpartyRiskRoutes(page, EXPOSURES_WITH_EXPIRED)
  })

  test('shows a "Block new trades / open ticket" CTA on the expired row', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-DB"]')

    await expect(page.getByTestId('block-trades-cta-CP-DB')).toBeVisible()
  })

  test('does not show the CTA on rows whose agreement is active', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-GS"]')

    await expect(page.getByTestId('block-trades-cta-CP-GS')).toHaveCount(0)
  })

  test('clicking the CTA opens a ticket dialog scoped to the counterparty', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-DB"]')

    await page.getByTestId('block-trades-cta-CP-DB').click()

    await expect(page.getByTestId('block-trades-dialog')).toBeVisible()
    await expect(page.getByTestId('block-trades-dialog')).toContainText('CP-DB')
  })

  test('confirming the ticket records the remediation intent and shows a confirmation', async ({ page }) => {
    // No backend contract for ticketing exists yet, so the confirm step is a
    // self-contained client-side workflow that surfaces a success acknowledgement
    // (the eventual regulatory-service wiring is a follow-up — see the spec's
    // dialog copy). The user-visible behaviour is: confirm → success state.
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-DB"]')

    await page.getByTestId('block-trades-cta-CP-DB').click()
    await page.getByTestId('block-trades-confirm').click()

    await expect(page.getByTestId('block-trades-success')).toBeVisible()
    await expect(page.getByTestId('block-trades-success')).toContainText('CP-DB')
  })

  test('cancelling the dialog dismisses it without a confirmation', async ({ page }) => {
    await goToCounterpartyRiskTab(page)
    await page.waitForSelector('[data-testid="counterparty-row-CP-DB"]')

    await page.getByTestId('block-trades-cta-CP-DB').click()
    await expect(page.getByTestId('block-trades-dialog')).toBeVisible()

    await page.getByTestId('block-trades-cancel').click()
    await expect(page.getByTestId('block-trades-dialog')).toHaveCount(0)
    await expect(page.getByTestId('block-trades-success')).toHaveCount(0)
  })
})
