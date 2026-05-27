import { test, expect } from '@playwright/test'

// Risk threshold indicators announce utilisation and alert state via
// aria-label (kx-1oq9).
//
// Risk dashboards surface position limit utilisation as a coloured bar (green
// at low utilisation, amber as it approaches the limit, red when the limit
// is breached). Sighted traders read the colour; screen-reader users need
// the same information programmatically. WCAG 1.4.1 (Use of Color) requires
// that information conveyed by colour also be available through other means.
//
// The convention is an aria-label of the form
// "Utilization 78% of limit (red alert)" so the percentage *and* the alert
// level are both announced as a single status string. This spec verifies
// the markup contract using page.setContent so it runs without the full app
// stack.

const SAMPLE_HTML = `
<!doctype html>
<html lang="en">
  <body>
    <main>
      <h1>Counterparty limits</h1>

      <div
        data-testid="risk-threshold-green"
        role="meter"
        aria-label="Utilization 42% of limit (green normal)"
        aria-valuenow="42"
        aria-valuemin="0"
        aria-valuemax="100"
        data-alert="green"
      >
        <div style="width: 42%; background: #22c55e; height: 8px;"></div>
      </div>

      <div
        data-testid="risk-threshold-amber"
        role="meter"
        aria-label="Utilization 78% of limit (amber warning)"
        aria-valuenow="78"
        aria-valuemin="0"
        aria-valuemax="100"
        data-alert="amber"
      >
        <div style="width: 78%; background: #f59e0b; height: 8px;"></div>
      </div>

      <div
        data-testid="risk-threshold-red"
        role="meter"
        aria-label="Utilization 105% of limit (red alert)"
        aria-valuenow="105"
        aria-valuemin="0"
        aria-valuemax="100"
        data-alert="red"
      >
        <div style="width: 100%; background: #ef4444; height: 8px;"></div>
      </div>
    </main>
  </body>
</html>
`

test.describe('risk threshold indicators announce utilisation and alert state', () => {
  test('exposes role="meter" so AT recognises the indicator as a gauge', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    const meters = page.getByRole('meter')
    await expect(meters).toHaveCount(3)
  })

  test('amber threshold aria-label includes the percentage and "amber warning"', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    const amber = page.getByTestId('risk-threshold-amber')
    await expect(amber).toHaveAttribute(
      'aria-label',
      'Utilization 78% of limit (amber warning)',
    )
  })

  test('red threshold aria-label includes the percentage and "red alert"', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    const red = page.getByTestId('risk-threshold-red')
    await expect(red).toHaveAttribute(
      'aria-label',
      'Utilization 105% of limit (red alert)',
    )
  })

  test('green threshold aria-label includes the percentage and "green normal"', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    const green = page.getByTestId('risk-threshold-green')
    await expect(green).toHaveAttribute(
      'aria-label',
      'Utilization 42% of limit (green normal)',
    )
  })

  test('aria-valuenow matches the utilisation percentage so AT meters announce a value', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    await expect(page.getByTestId('risk-threshold-green')).toHaveAttribute(
      'aria-valuenow',
      '42',
    )
    await expect(page.getByTestId('risk-threshold-amber')).toHaveAttribute(
      'aria-valuenow',
      '78',
    )
    await expect(page.getByTestId('risk-threshold-red')).toHaveAttribute(
      'aria-valuenow',
      '105',
    )
  })

  test('aria-valuemin and aria-valuemax bracket the meter scale', async ({ page }) => {
    await page.setContent(SAMPLE_HTML)
    const meters = page.getByRole('meter')
    const count = await meters.count()
    for (let i = 0; i < count; i++) {
      await expect(meters.nth(i)).toHaveAttribute('aria-valuemin', '0')
      await expect(meters.nth(i)).toHaveAttribute('aria-valuemax', '100')
    }
  })

  test('every aria-label includes both the utilisation percentage and an alert phrase', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    const meters = page.getByRole('meter')
    const count = await meters.count()
    for (let i = 0; i < count; i++) {
      const label = await meters.nth(i).getAttribute('aria-label')
      expect(label, `meter ${i} is missing aria-label`).not.toBeNull()
      expect(label).toMatch(/Utilization \d+% of limit/)
      expect(label).toMatch(/\((green normal|amber warning|red alert)\)/)
    }
  })

  test('locates meters by accessible name so getByRole resolves the alert level', async ({
    page,
  }) => {
    await page.setContent(SAMPLE_HTML)
    await expect(
      page.getByRole('meter', { name: 'Utilization 105% of limit (red alert)' }),
    ).toBeVisible()
  })
})
