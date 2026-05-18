import { test, expect } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import { mockAllApiRoutes } from './fixtures'

/**
 * E2E coverage for per-alert triage actions (UI overhaul §3.1, §3.1b.2).
 *
 * Acknowledge / Escalate / Resolve all round-trip through mocked endpoints
 * so we can assert the status badge transitions optimistically and after
 * the server response.
 */

const TRIGGERED_ALERT = {
  id: 'alert-ack-1',
  ruleId: 'rule-1',
  ruleName: 'VaR Critical Limit',
  type: 'VAR_BREACH',
  severity: 'CRITICAL',
  message: 'VaR breach on book-1',
  currentValue: 250000,
  threshold: 100000,
  bookId: 'book-1',
  triggeredAt: '2025-01-15T09:00:00Z',
  status: 'TRIGGERED',
}

async function mockAlertsList(page: Page): Promise<void> {
  await page.unroute('**/api/v1/notifications/alerts*')
  await page.route('**/api/v1/notifications/alerts*', (route: Route) => {
    if (route.request().method() === 'GET') {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([TRIGGERED_ALERT]),
      })
    } else {
      route.fallback()
    }
  })
}

async function mockAcknowledgeSuccess(page: Page): Promise<void> {
  await page.route(
    '**/api/v1/notifications/alerts/*/acknowledge',
    (route: Route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ ...TRIGGERED_ALERT, status: 'ACKNOWLEDGED' }),
        })
      } else {
        route.fallback()
      }
    },
  )
}

async function mockAcknowledgeFailure(page: Page): Promise<void> {
  await page.route(
    '**/api/v1/notifications/alerts/*/acknowledge',
    (route: Route) => {
      if (route.request().method() === 'POST') {
        route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            error: 'Conflict',
            message: 'Alert is already acknowledged',
          }),
        })
      } else {
        route.fallback()
      }
    },
  )
}

test.describe('Per-alert triage actions', () => {
  test.beforeEach(async ({ page }) => {
    await mockAllApiRoutes(page)
    await mockAlertsList(page)
  })

  test('TRIGGERED alert exposes an Acknowledge button', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await expect(page.getByTestId('status-badge-alert-ack-1')).toHaveText(
      'TRIGGERED',
    )
    await expect(
      page.getByTestId('acknowledge-btn-alert-ack-1'),
    ).toBeVisible()
  })

  test('acknowledging a CRITICAL alert flips the status badge to ACKNOWLEDGED', async ({
    page,
  }) => {
    await mockAcknowledgeSuccess(page)
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('acknowledge-btn-alert-ack-1').click()
    await expect(
      page.getByTestId('acknowledge-form-alert-ack-1'),
    ).toBeVisible()

    // Empty note is acceptable for Acknowledge.
    await page.getByTestId('acknowledge-submit-alert-ack-1').click()

    await expect(page.getByTestId('status-badge-alert-ack-1')).toHaveText(
      'ACKNOWLEDGED',
    )
    // Acknowledge button disappears for acknowledged alerts.
    await expect(
      page.getByTestId('acknowledge-btn-alert-ack-1'),
    ).toHaveCount(0)
  })

  test('acknowledging with a note sends the note to the API', async ({
    page,
  }) => {
    let capturedBody: string | null = null
    await page.route(
      '**/api/v1/notifications/alerts/*/acknowledge',
      (route: Route) => {
        if (route.request().method() === 'POST') {
          capturedBody = route.request().postData()
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...TRIGGERED_ALERT, status: 'ACKNOWLEDGED' }),
          })
        } else {
          route.fallback()
        }
      },
    )

    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('acknowledge-btn-alert-ack-1').click()
    await page
      .getByTestId('acknowledge-note-alert-ack-1')
      .fill('investigating, owner=alice')
    await page.getByTestId('acknowledge-submit-alert-ack-1').click()

    await expect(page.getByTestId('status-badge-alert-ack-1')).toHaveText(
      'ACKNOWLEDGED',
    )
    expect(capturedBody).not.toBeNull()
    const parsed = JSON.parse(capturedBody as unknown as string)
    expect(parsed.notes).toBe('investigating, owner=alice')
    expect(typeof parsed.acknowledgedBy).toBe('string')
  })

  test('a failed Acknowledge reverts the optimistic status update', async ({
    page,
  }) => {
    await mockAcknowledgeFailure(page)
    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('acknowledge-btn-alert-ack-1').click()
    await page.getByTestId('acknowledge-submit-alert-ack-1').click()

    // After the API responds 409, the optimistic flip is reverted.
    await expect(page.getByTestId('status-badge-alert-ack-1')).toHaveText(
      'TRIGGERED',
    )
    // Acknowledge button is back, ready for retry.
    await expect(
      page.getByTestId('acknowledge-btn-alert-ack-1'),
    ).toBeVisible()
  })

  test('escalating an alert flips the badge to ESCALATED and posts reason + assignee', async ({
    page,
  }) => {
    let capturedBody: string | null = null
    await page.route(
      '**/api/v1/notifications/alerts/*/escalate',
      (route: Route) => {
        if (route.request().method() === 'POST') {
          capturedBody = route.request().postData()
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              ...TRIGGERED_ALERT,
              status: 'ESCALATED',
              escalatedAt: '2025-01-15T09:10:00Z',
              escalatedTo: 'risk-manager',
            }),
          })
        } else {
          route.fallback()
        }
      },
    )

    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('escalate-btn-alert-ack-1').click()
    await expect(
      page.getByTestId('escalate-form-alert-ack-1'),
    ).toBeVisible()

    await page
      .getByTestId('escalate-reason-alert-ack-1')
      .fill('unack for 30 minutes')
    await page
      .getByTestId('escalate-assignee-alert-ack-1')
      .fill('risk-manager')
    await page.getByTestId('escalate-submit-alert-ack-1').click()

    await expect(page.getByTestId('status-badge-alert-ack-1')).toHaveText(
      'ESCALATED',
    )
    expect(capturedBody).not.toBeNull()
    const parsed = JSON.parse(capturedBody as unknown as string)
    expect(parsed.reason).toBe('unack for 30 minutes')
    expect(parsed.assignee).toBe('risk-manager')
  })

  test('escalate validation: blank reason is rejected client-side', async ({
    page,
  }) => {
    let requested = false
    await page.route(
      '**/api/v1/notifications/alerts/*/escalate',
      (route: Route) => {
        if (route.request().method() === 'POST') {
          requested = true
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...TRIGGERED_ALERT, status: 'ESCALATED' }),
          })
        } else {
          route.fallback()
        }
      },
    )

    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('escalate-btn-alert-ack-1').click()
    await page.getByTestId('escalate-submit-alert-ack-1').click()

    // Form remains open, error surfaces, no API call made.
    await expect(
      page.getByTestId('escalate-form-alert-ack-1'),
    ).toBeVisible()
    await expect(
      page.getByTestId('escalate-reason-error-alert-ack-1'),
    ).toBeVisible()
    expect(requested).toBe(false)
  })

  test('resolving an alert flips the badge to RESOLVED and posts resolutionText', async ({
    page,
  }) => {
    let capturedBody: string | null = null
    // Use the test's "now" for resolvedAt so the resolved row stays in the
    // hot-list and not in the older-resolved (>24h) summary section.
    const resolvedAtIso = new Date().toISOString()
    await page.route(
      '**/api/v1/notifications/alerts/*/resolve',
      (route: Route) => {
        if (route.request().method() === 'POST') {
          capturedBody = route.request().postData()
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              ...TRIGGERED_ALERT,
              triggeredAt: resolvedAtIso,
              status: 'RESOLVED',
              resolvedAt: resolvedAtIso,
              resolvedReason: 'positions reduced',
            }),
          })
        } else {
          route.fallback()
        }
      },
    )

    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('resolve-btn-alert-ack-1').click()
    await expect(
      page.getByTestId('resolve-form-alert-ack-1'),
    ).toBeVisible()

    await page
      .getByTestId('resolve-text-alert-ack-1')
      .fill('positions reduced')
    await page.getByTestId('resolve-submit-alert-ack-1').click()

    // RESOLVED is hidden from the default queue view; surface it so we can
    // assert on the badge transition.
    await page.getByTestId('status-filter-resolved').click()
    await expect(page.getByTestId('status-badge-alert-ack-1')).toHaveText(
      'RESOLVED',
    )
    expect(capturedBody).not.toBeNull()
    const parsed = JSON.parse(capturedBody as unknown as string)
    expect(parsed.resolutionText).toBe('positions reduced')
  })

  test('resolve validation: blank resolutionText is rejected client-side', async ({
    page,
  }) => {
    let requested = false
    await page.route(
      '**/api/v1/notifications/alerts/*/resolve',
      (route: Route) => {
        if (route.request().method() === 'POST') {
          requested = true
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...TRIGGERED_ALERT, status: 'RESOLVED' }),
          })
        } else {
          route.fallback()
        }
      },
    )

    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('resolve-btn-alert-ack-1').click()
    await page.getByTestId('resolve-submit-alert-ack-1').click()

    await expect(page.getByTestId('resolve-form-alert-ack-1')).toBeVisible()
    await expect(
      page.getByTestId('resolve-text-error-alert-ack-1'),
    ).toBeVisible()
    expect(requested).toBe(false)
  })

  test('Cancel closes the acknowledge form without dispatching a request', async ({
    page,
  }) => {
    let acknowledgeRequested = false
    await page.route(
      '**/api/v1/notifications/alerts/*/acknowledge',
      (route: Route) => {
        if (route.request().method() === 'POST') {
          acknowledgeRequested = true
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ ...TRIGGERED_ALERT, status: 'ACKNOWLEDGED' }),
          })
        } else {
          route.fallback()
        }
      },
    )

    await page.goto('/')
    await page.getByTestId('tab-alerts').click()
    await page.waitForSelector('[data-testid="alerts-list"]')

    await page.getByTestId('acknowledge-btn-alert-ack-1').click()
    await page.getByTestId('acknowledge-cancel-alert-ack-1').click()

    await expect(
      page.getByTestId('acknowledge-form-alert-ack-1'),
    ).toHaveCount(0)
    await expect(page.getByTestId('status-badge-alert-ack-1')).toHaveText(
      'TRIGGERED',
    )
    expect(acknowledgeRequested).toBe(false)
  })
})
